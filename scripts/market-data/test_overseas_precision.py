import unittest
from datetime import datetime, timezone
from overseas import normalize_overseas_candles

HK = {"market": "HK", "symbol": "00700", "name": "Tencent", "currency": "HKD", "exchange": "HKEX", "timezone": "Asia/Hong_Kong"}
US = {"market": "US", "symbol": "AAPL", "name": "Apple", "currency": "USD", "exchange": "NASDAQ", "timezone": "America/New_York"}
NOW = datetime(2026, 9, 8, 12, tzinfo=timezone.utc)

def row(close="477.20001", high="477.2", low="469.4", opening="475"):
    return {"date": "2026-06-17", "open": opening, "high": high, "low": low, "close": close, "volume": 100, "turnover": None}

class OverseasPrecisionTest(unittest.TestCase):
    def test_hk_tiny_close_tail_preserves_original_values(self):
        bar = normalize_overseas_candles(HK, [row()], NOW)["bars"][0]
        self.assertEqual(477.20001, bar["close"])
        self.assertEqual(477.2, bar["high"])

    def test_hk_low_boundary_tail_preserves_original_value(self):
        bar = normalize_overseas_candles(HK, [row("445.39999", "454", "445.4", "445.6")], NOW)["bars"][0]
        self.assertEqual(445.39999, bar["close"])

    def test_penny_large_errors_inverted_range_and_bad_open_stay_rejected(self):
        for bad in [row("477.21"), row("0.01999", "0.022", "0.02", "0.021"), row("477.2", "477.2", "477.20001", "477.2"), row("477.2", opening="477.20001")]:
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                normalize_overseas_candles(HK, [bad], NOW)

    def test_other_markets_do_not_use_hk_close_tolerance(self):
        with self.assertRaises(ValueError):
            normalize_overseas_candles(US, [row()], NOW)

    def test_discarded_ancient_and_unfinished_rows_do_not_poison_visible_window(self):
        old = dict(row(), date="2006-06-13", open=16.3, high=17.3, low=16.799, close=16.3)
        today = dict(old, date="2026-09-08")
        bars = normalize_overseas_candles(HK, [old, old, today, row("477.2")], NOW)["bars"]
        self.assertEqual(1, len(bars))

    def test_malformed_dates_and_retained_duplicates_remain_errors(self):
        for rows in [[dict(row(), date="bad")], [row("477.2"), row("477.2")]]:
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                normalize_overseas_candles(HK, rows, NOW)
