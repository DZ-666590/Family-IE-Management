import itertools
import sys
import threading
import time
import types
import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import patch

import overseas_sources
from overseas import (
    InstrumentNotFound,
    OverseasMarketService,
    UpstreamUnavailable,
    normalize_overseas_candles,
)
from overseas_sources import build_hk_directory, parse_nasdaq_file
from server import Handler


HK = {
    "symbol": "00700",
    "name": "騰訊控股",
    "market": "HK",
    "currency": "HKD",
    "exchange": "HKEX",
    "timezone": "Asia/Hong_Kong",
}
US = {
    "symbol": "AAPL",
    "name": "Apple Inc. Common Stock",
    "market": "US",
    "currency": "USD",
    "exchange": "NASDAQ",
    "timezone": "America/New_York",
}


class MutableClock:
    def __init__(self, value):
        self.value = value

    def __call__(self):
        return self.value


def wait_for_state(service, market, wanted="READY"):
    deadline = time.monotonic() + 1
    while time.monotonic() < deadline:
        result = service.search(market, "")
        if result["state"] == wanted:
            return result
        time.sleep(0.005)
    raise AssertionError(f"directory did not reach {wanted}")


class DirectorySourceTest(unittest.TestCase):
    def test_read_only_workbook_resets_incorrect_declared_dimensions_before_iteration(self):
        values = [
            ("List of Securities", None, None, None),
            ("Last Updated: 8 September 2026", None, None, None),
            ("Stock Code", "Name of Securities", "Category", "Trading Currency"),
            (700, "TENCENT HOLDINGS", "Equity", "HKD"),
            (5, "HSBC HOLDINGS", "Equity", "HKD"),
        ]

        class WrongDimensionSheet:
            def __init__(self):
                self.reset = False

            def reset_dimensions(self):
                self.reset = True

            def iter_rows(self, values_only):
                return iter(values if self.reset else values[:4])

        sheet = WrongDimensionSheet()
        workbook = types.SimpleNamespace(active=sheet, close=lambda: None)
        fake_openpyxl = types.SimpleNamespace(load_workbook=lambda *args, **kwargs: workbook)
        with patch.dict(sys.modules, {"openpyxl": fake_openpyxl}):
            rows = overseas_sources._workbook_rows(
                b"xlsx",
                ("Stock Code", "Name of Securities", "Category", "Trading Currency"),
            )
        self.assertEqual([700, 5], [row["Stock Code"] for row in rows])

    def test_workbook_rejects_more_than_thirty_thousand_actual_data_rows(self):
        preamble = [
            ("List of Securities", None, None, None),
            ("Last Updated: 8 September 2026", None, None, None),
            ("Stock Code", "Name of Securities", "Category", "Trading Currency"),
        ]
        repeated_row = (700, "TENCENT HOLDINGS", "Equity", "HKD")
        values = itertools.chain(preamble, itertools.repeat(repeated_row, 30_001))
        with self.assertRaisesRegex(ValueError, "too many rows"):
            overseas_sources.workbook_values_to_rows(
                values,
                ("Stock Code", "Name of Securities", "Category", "Trading Currency"),
            )

    def test_workbook_uses_third_physical_row_after_two_preamble_rows_as_headers(self):
        parse_values = getattr(overseas_sources, "workbook_values_to_rows", lambda values, required: [])
        values = [
            ("List of Securities", None, None, None),
            ("Last Updated: 8 September 2026", None, None, None),
            ("Stock Code", "Name of Securities", "Category", "Trading Currency"),
            (700, "TENCENT HOLDINGS", "Equity", "HKD"),
        ]
        self.assertEqual(
            [{
                "Stock Code": 700,
                "Name of Securities": "TENCENT HOLDINGS",
                "Category": "Equity",
                "Trading Currency": "HKD",
            }],
            parse_values(values, ("Stock Code", "Name of Securities", "Category", "Trading Currency")),
        )

    def test_hk_english_rows_define_identity_and_currency_with_optional_chinese_name(self):
        result = build_hk_directory(
            [
                {"Stock Code": 700, "Name of Securities": "TENCENT HOLDINGS", "Category": "Equity", "Trading Currency": "HKD"},
                {"Stock Code": 80700, "Name of Securities": "TENCENT RMB", "Category": "Equity", "Trading Currency": "CNY"},
                {"Stock Code": 5, "Name of Securities": "HSBC WARRANT", "Category": "Derivative Warrant", "Trading Currency": "HKD"},
            ],
            [
                {"股份代號": 700, "股份名稱": "騰訊控股"},
                {"股份代號": 99999, "股份名稱": "不可新增的中文表項"},
            ],
            min_items=1,
        )
        self.assertEqual(["00700", "80700"], [item["symbol"] for item in result])
        self.assertEqual("騰訊控股", result[0]["name"])
        self.assertEqual("HKD", result[0]["currency"])
        self.assertEqual("CNY", result[1]["currency"])

    def test_nasdaq_parser_requires_header_footer_and_filters_non_equities(self):
        listed = (
            "Symbol|Security Name|Market Category|Test Issue|Financial Status|Round Lot Size|ETF|NextShares\n"
            "AAPL|Apple Inc. Common Stock|Q|N|N|100|N|N\n"
            "SPY|SPDR S&P 500 ETF Trust|Q|N|N|100|Y|N\n"
            "TST|Test Common Stock|Q|Y|N|100|N|N\n"
            "WXYZW|Example Warrants|Q|N|N|100|N|N\n"
            "File Creation Time: 0908202607:00\n"
        ).encode("ascii")
        items = parse_nasdaq_file(listed, listed=True)
        self.assertEqual([US], items)
        with self.assertRaises(ValueError):
            parse_nasdaq_file(listed.replace(b"File Creation Time:", b"Generated:"), listed=True)

    def test_otherlisted_parser_maps_exchange_code(self):
        other = (
            "ACT Symbol|Security Name|Exchange|CQS Symbol|ETF|Round Lot Size|Test Issue|NASDAQ Symbol\n"
            "IBM|International Business Machines Common Stock|N|IBM|N|100|N|IBM\n"
            "File Creation Time: 0908202607:00\n"
        ).encode("ascii")
        item = parse_nasdaq_file(other, listed=False)[0]
        self.assertEqual("IBM", item["symbol"])
        self.assertEqual("NYSE", item["exchange"])

    def test_otherlisted_excludes_declared_preferred_before_validating_decorated_symbol(self):
        other = (
            "ACT Symbol|Security Name|Exchange|CQS Symbol|ETF|Round Lot Size|Test Issue|NASDAQ Symbol\n"
            "ABR$D|Arbor Realty Trust 6.375% Series D Cumulative Redeemable Preferred Stock|N|ABR$D|N|100|N|ABR-D\n"
            "BAC$E|Bank of America Corporation Depositary Sh repstg 1/1000th Perp Pfd Ser E|N|BAC$E|N|100|N|BAC-E\n"
            "DBRG$H|DigitalBridge Group, Inc. 7.125% Series H|N|DBRG$H|N|100|N|DBRG-H\n"
            "SCE$L|SCE TRUST VI|N|SCE$L|N|100|N|SCE-L\n"
            "IBM|International Business Machines Common Stock|N|IBM|N|100|N|IBM\n"
            "File Creation Time: 0908202607:00\n"
        ).encode("ascii")
        try:
            symbols = [item["symbol"] for item in parse_nasdaq_file(other, listed=False)]
        except ValueError as exception:
            symbols = [str(exception)]
        self.assertEqual(["IBM"], symbols)

    def test_otherlisted_still_rejects_malformed_retained_common_equity_symbol(self):
        other = (
            "ACT Symbol|Security Name|Exchange|CQS Symbol|ETF|Round Lot Size|Test Issue|NASDAQ Symbol\n"
            "BAD$|Example Common Stock|N|BAD$|N|100|N|BAD$\n"
            "File Creation Time: 0908202607:00\n"
        ).encode("ascii")
        with self.assertRaisesRegex(ValueError, "invalid US symbol"):
            parse_nasdaq_file(other, listed=False)

    def test_us_raw_loader_uses_current_unadjusted_staticdata_endpoint(self):
        load_us = getattr(overseas_sources, "load_us_sina_rows", lambda symbol, download, decode: [])
        requested = []

        def download(url):
            requested.append(url)
            return b'var data="encoded";'

        decoded = [{
            "date": "2026-09-04", "open": 328.305, "high": 330,
            "low": 318, "close": 319.97, "volume": 100, "amount": 999,
        }]
        rows = load_us("AAPL", download=download, decode=lambda encoded: decoded)
        self.assertEqual(["https://finance.sina.com.cn/staticdata/us/AAPL"], requested)
        self.assertEqual(328.305, rows[0]["open"] if rows else None)
        self.assertIsNone(rows[0]["turnover"] if rows else "missing")

    def test_us_decoder_amount_is_not_exposed_as_verified_turnover(self):
        normalize = getattr(overseas_sources, "normalize_sina_records", lambda records, market: [])
        source = [{
            "date": "2026-03-06", "open": 10, "high": 12, "low": 9,
            "close": 11, "volume": 100, "amount": 123456,
        }]
        us_row = normalize(source, "US")[0] if normalize(source, "US") else None
        hk_row = normalize(source, "HK")[0] if normalize(source, "HK") else None
        self.assertEqual(None, us_row["turnover"] if us_row else "missing")
        self.assertEqual(123456, hk_row["turnover"] if hk_row else "missing")


class OverseasDirectoryTest(unittest.TestCase):
    def service(self, loader=None, clock=None):
        directories = {"HK": [HK], "US": [US]}
        return OverseasMarketService(
            directory_loader=loader or (lambda market: directories[market]),
            candle_loader=lambda market, symbol: [],
            clock=clock or MutableClock(datetime(2026, 3, 9, 12, tzinfo=timezone.utc)),
            min_directory_items={"HK": 1, "US": 1},
        )

    def test_first_search_is_nonblocking_then_publishes_complete_directory(self):
        release = threading.Event()

        def loader(market):
            release.wait(1)
            return [HK]

        service = self.service(loader=loader)
        started = time.monotonic()
        first = service.search("HK", "騰訊")
        self.assertLess(time.monotonic() - started, 0.2)
        self.assertEqual("SYNCING", first["state"])
        self.assertEqual([], first["items"])
        release.set()
        ready = wait_for_state(service, "HK")
        self.assertEqual([HK], ready["items"])
        self.assertFalse(ready["stale"])

    def test_search_orders_exact_then_prefix_then_name_and_caps_twenty(self):
        items = [dict(US, symbol=f"A{i:02d}", name=f"Company {i}") for i in range(22)]
        items.append(dict(US, symbol="A", name="Exact"))
        service = self.service(loader=lambda market: items)
        wait_for_state(service, "US")
        result = service.search("US", "A")
        self.assertEqual("A", result["items"][0]["symbol"])
        self.assertEqual(20, len(result["items"]))
        self.assertTrue(result["hasNext"])

    def test_rejects_query_over_eighty_characters(self):
        with self.assertRaises(ValueError):
            self.service().search("US", "x" * 81)

    def test_failed_refresh_preserves_seven_day_cache_as_stale(self):
        clock = MutableClock(datetime(2026, 3, 1, tzinfo=timezone.utc))
        calls = 0

        def loader(market):
            nonlocal calls
            calls += 1
            if calls == 1:
                return [US]
            raise RuntimeError("network down")

        service = self.service(loader=loader, clock=clock)
        wait_for_state(service, "US")
        clock.value = datetime(2026, 3, 3, tzinfo=timezone.utc)
        stale = service.search("US", "AAPL")
        self.assertEqual("READY", stale["state"])
        self.assertTrue(stale["stale"])
        self.assertEqual([US], stale["items"])

    def test_failure_retry_delay_starts_when_slow_refresh_finishes(self):
        clock = MutableClock(datetime(2026, 3, 1, tzinfo=timezone.utc))
        failed = threading.Event()
        calls = 0

        def loader(market):
            nonlocal calls
            calls += 1
            clock.value += timedelta(seconds=61)
            failed.set()
            raise RuntimeError("network down")

        service = self.service(loader=loader, clock=clock)
        self.assertEqual("SYNCING", service.search("US", "")["state"])
        self.assertTrue(failed.wait(1))
        deadline = time.monotonic() + 1
        while service._directory_refreshing["US"] and time.monotonic() < deadline:
            time.sleep(0.005)
        second = service.search("US", "")
        self.assertEqual("ERROR", second["state"])
        self.assertEqual(1, calls)


class OverseasCandleTest(unittest.TestCase):
    def service(self, rows, now=None):
        service = OverseasMarketService(
            directory_loader=lambda market: [HK] if market == "HK" else [US],
            candle_loader=lambda market, symbol: rows,
            clock=MutableClock(now or datetime(2026, 3, 9, 14, tzinfo=timezone.utc)),
            min_directory_items={"HK": 1, "US": 1},
        )
        wait_for_state(service, "HK")
        wait_for_state(service, "US")
        return service

    def test_rejects_unknown_symbol_before_loading_candles(self):
        service = self.service([])
        with self.assertRaises(InstrumentNotFound):
            service.candles("US", "MSFT")

    def test_us_midnight_uses_new_york_dst_and_omits_current_trading_day(self):
        rows = [
            {"date": "2026-03-06", "open": "10", "high": "12", "low": "9", "close": "11", "volume": "100", "turnover": None},
            {"date": "2026-03-09", "open": "11", "high": "13", "low": "10", "close": "12", "volume": "110", "turnover": "1200"},
        ]
        result = self.service(rows).candles("US", "AAPL")
        self.assertEqual(1772773200000, result["bars"][0]["timestamp"])
        self.assertEqual(1, len(result["bars"]))
        self.assertIsNone(result["bars"][0]["turnover"])
        self.assertEqual("2026-03-06", result["asOf"])
        self.assertEqual("SINA", result["source"])
        self.assertEqual("none", result["adjustment"])
        self.assertEqual(US, result["instrument"])

    def test_hong_kong_midnight_uses_hong_kong_timezone(self):
        rows = [{"date": "2026-03-06", "open": 10, "high": 12, "low": 9, "close": 11, "volume": 100}]
        result = self.service(rows).candles("HK", "00700")
        self.assertEqual(1772726400000, result["bars"][0]["timestamp"])
        self.assertIsNone(result["bars"][0]["turnover"])

    def test_rejects_malformed_ohlc(self):
        with self.assertRaises(ValueError):
            normalize_overseas_candles(
                US,
                [{"date": "2026-03-06", "open": 13, "high": 12, "low": 9, "close": 11, "volume": 100}],
                datetime(2026, 3, 9, 14, tzinfo=timezone.utc),
            )

    def test_rejects_duplicate_dates_even_when_input_is_unsorted(self):
        row = {"date": "2026-03-06", "open": 10, "high": 12, "low": 9, "close": 11, "volume": 100}
        with self.assertRaises(ValueError):
            normalize_overseas_candles(US, [row, dict(row)], datetime(2026, 3, 9, 14, tzinfo=timezone.utc))

    def test_malformed_upstream_rows_do_not_replace_stale_candle_cache(self):
        clock = MutableClock(datetime(2026, 3, 9, 14, tzinfo=timezone.utc))
        calls = 0

        def candle_loader(market, symbol):
            nonlocal calls
            calls += 1
            if calls == 1:
                return [{"date": "2026-03-06", "open": 10, "high": 12, "low": 9, "close": 11, "volume": 100}]
            return [{"date": "2026-03-06", "open": 13, "high": 12, "low": 9, "close": 11, "volume": 100}]

        service = OverseasMarketService(
            directory_loader=lambda market: [US],
            candle_loader=candle_loader,
            clock=clock,
            min_directory_items={"HK": 1, "US": 1},
            candle_ttl_seconds=0,
        )
        wait_for_state(service, "US")
        self.assertFalse(service.candles("US", "AAPL")["stale"])
        clock.value = datetime(2026, 3, 10, 14, tzinfo=timezone.utc)
        self.assertTrue(service.candles("US", "AAPL")["stale"])

    def test_busy_candle_admission_fails_without_spawning_more_work(self):
        entered = threading.Event()
        release = threading.Event()

        def loader(market, symbol):
            entered.set()
            release.wait(1)
            return []

        service = OverseasMarketService(
            directory_loader=lambda market: [US, dict(US, symbol="MSFT", name="Microsoft Corporation Common Stock")],
            candle_loader=loader,
            min_directory_items={"HK": 1, "US": 1},
            max_candle_workers=1,
            candle_admission_timeout_seconds=0.02,
        )
        wait_for_state(service, "US")
        first = threading.Thread(target=lambda: service.candles("US", "AAPL"))
        first.start()
        self.assertTrue(entered.wait(1))
        with self.assertRaises(UpstreamUnavailable):
            service.candles("US", "MSFT")
        release.set()
        first.join(1)

    def test_concurrent_same_symbol_callers_share_work_even_when_admission_is_full(self):
        entered = threading.Event()
        release = threading.Event()
        calls = 0

        def loader(market, symbol):
            nonlocal calls
            calls += 1
            entered.set()
            release.wait(1)
            return []

        service = OverseasMarketService(
            directory_loader=lambda market: [US],
            candle_loader=loader,
            min_directory_items={"HK": 1, "US": 1},
            max_candle_workers=1,
            candle_admission_timeout_seconds=0.02,
        )
        wait_for_state(service, "US")

        class RacingSemaphore:
            def __init__(self):
                self._barrier = threading.Barrier(2)
                self._actual = threading.BoundedSemaphore(1)

            def acquire(self, timeout=None):
                self._barrier.wait(1)
                return self._actual.acquire(timeout=timeout)

            def release(self):
                self._actual.release()

        service._candle_slots = RacingSemaphore()
        results = []
        errors = []

        def request():
            try:
                results.append(service.candles("US", "AAPL"))
            except Exception as exception:
                errors.append(exception)

        callers = [threading.Thread(target=request) for _ in range(2)]
        for caller in callers:
            caller.start()
        self.assertTrue(entered.wait(1))
        time.sleep(0.05)
        release.set()
        for caller in callers:
            caller.join(1)
        self.assertEqual([], errors)
        self.assertEqual(2, len(results))
        self.assertEqual(1, calls)

    def test_same_symbol_waiter_uses_fresh_cache_when_first_call_completes_before_admission(self):
        entered = threading.Event()
        release = threading.Event()
        calls = 0

        def loader(market, symbol):
            nonlocal calls
            calls += 1
            if calls == 1:
                entered.set()
                release.wait(1)
            return []

        service = OverseasMarketService(
            directory_loader=lambda market: [US],
            candle_loader=loader,
            min_directory_items={"HK": 1, "US": 1},
            max_candle_workers=1,
            candle_admission_timeout_seconds=1,
        )
        wait_for_state(service, "US")

        class CompletionOrderedSemaphore:
            def __init__(self):
                self._barrier = threading.Barrier(2)
                self._actual = threading.BoundedSemaphore(1)

            def acquire(self, timeout=None):
                self._barrier.wait(1)
                return self._actual.acquire(timeout=timeout)

            def release(self):
                self._actual.release()

        service._candle_slots = CompletionOrderedSemaphore()
        results = []
        callers = [
            threading.Thread(target=lambda: results.append(service.candles("US", "AAPL")))
            for _ in range(2)
        ]
        for caller in callers:
            caller.start()
        self.assertTrue(entered.wait(1))
        release.set()
        for caller in callers:
            caller.join(1)
        self.assertEqual(2, len(results))
        self.assertEqual(1, calls)


class OverseasRouteTest(unittest.TestCase):
    def call(self, path, service):
        handler = object.__new__(Handler)
        handler.path = path
        handler.overseas_service = service
        captured = []
        handler._json = lambda status, payload: captured.append((status, payload))
        handler.do_GET()
        return captured[0]

    def test_search_route_returns_exact_service_payload(self):
        expected = {
            "items": [HK], "hasNext": False, "updatedAt": "2026-03-09T00:00:00Z",
            "stale": False, "state": "READY", "error": None,
        }

        class Service:
            def search(self, market, query):
                return expected if (market, query) == ("HK", "00700") else None

        self.assertEqual((200, expected), self.call("/overseas/search?market=HK&q=00700", Service()))

    def test_candle_route_maps_missing_instrument_and_upstream_failure(self):
        class MissingService:
            def candles(self, market, symbol):
                raise InstrumentNotFound("overseas instrument not found")

        self.assertEqual(
            (404, {"error": "overseas instrument not found"}),
            self.call("/overseas/candles?market=US&symbol=MSFT", MissingService()),
        )

        class FailedService:
            def search(self, market, query):
                raise UpstreamUnavailable("boom")

        self.assertEqual(
            (503, {"error": "market data temporarily unavailable"}),
            self.call("/overseas/search?market=US&q=AAPL", FailedService()),
        )

    def test_route_rejects_extra_or_repeated_query_parameters(self):
        class Service:
            def search(self, market, query):
                raise AssertionError("invalid query reached service")

        for path in (
            "/overseas/search?market=US&q=AAPL&extra=1",
            "/overseas/search?market=US&market=HK&q=AAPL",
        ):
            self.assertEqual(400, self.call(path, Service())[0])


if __name__ == "__main__":
    unittest.main()
