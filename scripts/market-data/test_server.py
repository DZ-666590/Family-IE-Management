import json
import sys
import time
import unittest
import threading
from datetime import datetime, timezone

from server import (
    MarketDataService,
    UpstreamUnavailable,
    normalize_candles,
    run_isolated,
    validate_adjust,
    validate_symbol,
)


class ValidationTest(unittest.TestCase):
    def test_rejects_path_like_symbol_and_unknown_adjustment(self):
        with self.assertRaises(ValueError):
            validate_symbol("600000.SH/../../env")
        with self.assertRaises(ValueError):
            validate_adjust("hfq")

    def test_rejects_invalid_ohlc_range(self):
        with self.assertRaises(ValueError):
            normalize_candles(
                "600000.SH",
                "none",
                [{
                    "date": "2026-09-01", "open": "11", "high": "10", "low": "9",
                    "close": "10", "volume": "1", "turnover": "2",
                }],
                datetime(2026, 9, 2, tzinfo=timezone.utc),
            )

    def test_preserves_baostock_volume_as_shares(self):
        result = normalize_candles(
            "600000.SH",
            "none",
            [{
                "date": "2026-09-01", "open": "10.10", "high": "10.50", "low": "10.00",
                "close": "10.40", "volume": "123456", "turnover": "1280000.50",
            }],
            datetime(2026, 9, 2, tzinfo=timezone.utc),
        )
        self.assertEqual(123456, result["bars"][0]["volume"])
        self.assertEqual(1280000.50, result["bars"][0]["turnover"])
        self.assertEqual("BAOSTOCK", result["source"])

    def test_allows_signed_adjusted_prices_but_not_signed_raw_prices(self):
        row = {
            "date": "2026-09-01", "open": "-0.10", "high": "0.50", "low": "-0.20",
            "close": "0.40", "volume": "1", "turnover": "2",
        }
        self.assertEqual(-0.10, normalize_candles(
            "600000.SH", "qfq", [row], datetime(2026, 9, 2, tzinfo=timezone.utc))["bars"][0]["open"])
        with self.assertRaises(ValueError):
            normalize_candles(
                "600000.SH", "none", [row], datetime(2026, 9, 2, tzinfo=timezone.utc))


class CacheTest(unittest.TestCase):
    def test_returns_stale_complete_cache_when_refresh_fails(self):
        calls = 0

        def loader():
            nonlocal calls
            calls += 1
            if calls == 1:
                return [{"tsCode": "600000.SH", "name": "浦发银行", "market": "SH"}]
            raise UpstreamUnavailable("boom")

        service = MarketDataService(
            directory_loader=loader, cache_ttl_seconds=0, min_directory_items=1,
            required_directory_markets={"SH"})
        first = service.directory()
        second = service.directory()
        self.assertFalse(first["stale"])
        self.assertTrue(second["stale"])
        self.assertEqual(first["items"], second["items"])
        self.assertEqual(first["fetchedAt"], second["fetchedAt"])

    def test_failed_first_load_has_no_fabricated_cache(self):
        service = MarketDataService(
            directory_loader=lambda: (_ for _ in ()).throw(UpstreamUnavailable("boom")))
        with self.assertRaises(UpstreamUnavailable):
            service.directory()


class IsolationTest(unittest.TestCase):
    def test_subprocess_timeout_bounds_upstream_work(self):
        started = time.monotonic()
        with self.assertRaises(UpstreamUnavailable):
            run_isolated(
                [sys.executable, "-c", "import time; time.sleep(2)"],
                timeout_seconds=0.05,
            )
        self.assertLess(time.monotonic() - started, 1.0)

    def test_subprocess_requires_valid_json_object(self):
        with self.assertRaises(UpstreamUnavailable):
            run_isolated(
                [sys.executable, "-c", "print('[]')"],
                timeout_seconds=1,
            )

    def test_busy_upstream_admission_fails_within_bound(self):
        entered = threading.Event()
        release = threading.Event()

        def loader(symbol, adjustment):
            entered.set()
            release.wait(2)
            return []

        service = MarketDataService(
            candle_loader=loader, upstream_admission_timeout_seconds=0.05,
            min_directory_items=1, required_directory_markets={"SH"})
        first = threading.Thread(target=lambda: service.candles("600000.SH", "none"))
        first.start()
        self.assertTrue(entered.wait(1))
        started = time.monotonic()
        with self.assertRaises(UpstreamUnavailable):
            service.candles("000001.SZ", "none")
        self.assertLess(time.monotonic() - started, 0.5)
        release.set()
        first.join(1)


if __name__ == "__main__":
    unittest.main()
