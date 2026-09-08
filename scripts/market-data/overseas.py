"""Independent read-only HK/US directory and raw candle service."""

import re
import threading
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, time as datetime_time, timedelta, timezone
from decimal import Decimal, InvalidOperation
from zoneinfo import ZoneInfo

from overseas_sources import load_directory, run_candle_worker


MARKET_METADATA = {
    "HK": {"timezone": "Asia/Hong_Kong", "currency": None, "exchanges": {"HKEX"}},
    "US": {
        "timezone": "America/New_York",
        "currency": "USD",
        "exchanges": {"NASDAQ", "NYSE", "NYSE AMERICAN", "NYSE ARCA", "CBOE BZX", "IEX"},
    },
}
HK_SYMBOL = re.compile(r"^[0-9]{5}$")
US_SYMBOL = re.compile(r"^[A-Z][A-Z0-9.-]{0,9}$")


class UpstreamUnavailable(RuntimeError):
    pass


class InstrumentNotFound(LookupError):
    pass


def _utc_iso(value):
    if value.tzinfo is None:
        raise ValueError("clock must return a timezone-aware datetime")
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def validate_market(raw):
    value = "" if raw is None else str(raw).strip().upper()
    if value not in MARKET_METADATA:
        raise ValueError("market must be HK or US")
    return value


def validate_overseas_symbol(market, raw):
    market = validate_market(market)
    value = "" if raw is None else str(raw).strip().upper()
    pattern = HK_SYMBOL if market == "HK" else US_SYMBOL
    if not pattern.fullmatch(value):
        raise ValueError(f"invalid {market} symbol")
    return value


def _decimal(raw, label, allow_zero=False):
    try:
        value = Decimal(str(raw))
    except (InvalidOperation, TypeError, ValueError):
        raise ValueError(f"invalid {label}") from None
    if not value.is_finite() or value < 0 or (not allow_zero and value == 0):
        raise ValueError(f"invalid {label}")
    return value


def _json_number(value):
    integral = value.to_integral_value()
    return int(integral) if value == integral else float(value)


def normalize_overseas_candles(instrument, rows, fetched_at):
    if not isinstance(rows, list):
        raise ValueError("invalid candle rows")
    market = validate_market(instrument.get("market"))
    symbol = validate_overseas_symbol(market, instrument.get("symbol"))
    zone = ZoneInfo(MARKET_METADATA[market]["timezone"])
    if fetched_at.tzinfo is None:
        raise ValueError("fetched_at must be timezone aware")
    local_today = fetched_at.astimezone(zone).date()
    earliest = local_today - timedelta(days=740)
    parsed_rows = []
    seen_dates = set()
    for row in rows:
        try:
            trading_day = date.fromisoformat(str(row["date"])[:10])
            open_price = _decimal(row["open"], "open")
            high = _decimal(row["high"], "high")
            low = _decimal(row["low"], "low")
            close = _decimal(row["close"], "close")
            volume = _decimal(row["volume"], "volume", allow_zero=True)
            raw_turnover = row.get("turnover")
            turnover = None if raw_turnover is None or str(raw_turnover).strip() == "" else _decimal(
                raw_turnover, "turnover", allow_zero=True)
        except (KeyError, TypeError, ValueError) as exception:
            raise ValueError("invalid candle row") from exception
        if trading_day in seen_dates:
            raise ValueError("duplicate candle date")
        seen_dates.add(trading_day)
        if low > high or not low <= open_price <= high or not low <= close <= high:
            raise ValueError("invalid OHLC range")
        if volume != volume.to_integral_value():
            raise ValueError("volume must be whole shares")
        if earliest <= trading_day < local_today:
            parsed_rows.append((trading_day, open_price, high, low, close, volume, turnover))

    bars = []
    for trading_day, open_price, high, low, close, volume, turnover in sorted(parsed_rows):
        timestamp = int(datetime.combine(trading_day, datetime_time.min, zone).timestamp() * 1000)
        bars.append({
            "timestamp": timestamp,
            "open": _json_number(open_price),
            "high": _json_number(high),
            "low": _json_number(low),
            "close": _json_number(close),
            "volume": int(volume),
            "turnover": None if turnover is None else _json_number(turnover),
        })
    return {
        "instrument": dict(instrument),
        "symbol": symbol,
        "source": "SINA",
        "adjustment": "none",
        "asOf": None if not parsed_rows else max(row[0] for row in parsed_rows).isoformat(),
        "fetchedAt": _utc_iso(fetched_at),
        "stale": False,
        "supported": True,
        "bars": bars,
    }


class OverseasMarketService:
    def __init__(
        self,
        directory_loader=None,
        candle_loader=None,
        clock=None,
        min_directory_items=None,
        directory_ttl_seconds=86400,
        directory_retry_seconds=60,
        stale_ttl_seconds=604800,
        candle_ttl_seconds=21600,
        max_candle_entries=256,
        max_candle_workers=2,
        candle_admission_timeout_seconds=1,
        candle_wait_timeout_seconds=22,
    ):
        self._directory_loader = directory_loader or load_directory
        self._candle_loader = candle_loader or run_candle_worker
        self._clock = clock or (lambda: datetime.now(timezone.utc))
        self._directory_minimums = min_directory_items or {"HK": 1000, "US": 2000}
        self._directory_ttl = directory_ttl_seconds
        self._directory_retry = directory_retry_seconds
        self._stale_ttl = stale_ttl_seconds
        self._candle_ttl = candle_ttl_seconds
        self._max_candles = max_candle_entries
        self._candle_admission_timeout = candle_admission_timeout_seconds
        self._candle_wait_timeout = candle_wait_timeout_seconds
        self._lock = threading.RLock()
        self._directory_cache = {"HK": None, "US": None}
        self._directory_attempt = {"HK": None, "US": None}
        self._directory_errors = {"HK": None, "US": None}
        self._directory_refreshing = {"HK": False, "US": False}
        self._directory_executor = ThreadPoolExecutor(max_workers=2, thread_name_prefix="overseas-directory")
        self._candle_cache = OrderedDict()
        self._candle_futures = {}
        self._candle_slots = threading.BoundedSemaphore(max_candle_workers)
        self._candle_executor = ThreadPoolExecutor(max_workers=max_candle_workers, thread_name_prefix="overseas-candle")

    def _now(self):
        value = self._clock()
        if not isinstance(value, datetime) or value.tzinfo is None:
            raise ValueError("clock must return a timezone-aware datetime")
        return value

    def _validate_directory(self, market, raw_items):
        minimum = self._directory_minimums[market]
        if not isinstance(raw_items, list) or not minimum <= len(raw_items) <= 30000:
            raise ValueError("directory is incomplete")
        metadata = MARKET_METADATA[market]
        by_symbol = {}
        for raw in raw_items:
            if not isinstance(raw, dict):
                raise ValueError("invalid directory item")
            symbol = validate_overseas_symbol(market, raw.get("symbol"))
            name = str(raw.get("name") or "").strip()
            item_market = str(raw.get("market") or "").strip().upper()
            currency = str(raw.get("currency") or "").strip().upper()
            exchange = str(raw.get("exchange") or "").strip().upper()
            item_timezone = str(raw.get("timezone") or "").strip()
            if (
                item_market != market
                or not name
                or len(name) > 200
                or not re.fullmatch(r"[A-Z]{3}", currency)
                or (metadata["currency"] is not None and currency != metadata["currency"])
                or exchange not in metadata["exchanges"]
                or item_timezone != metadata["timezone"]
                or symbol in by_symbol
            ):
                raise ValueError("invalid directory item")
            by_symbol[symbol] = {
                "symbol": symbol,
                "name": name,
                "market": market,
                "currency": currency,
                "exchange": exchange,
                "timezone": item_timezone,
            }
        return [by_symbol[symbol] for symbol in sorted(by_symbol)]

    def _start_directory_refresh_locked(self, market, now):
        if self._directory_refreshing[market]:
            return
        attempt = self._directory_attempt[market]
        if attempt is not None and (now - attempt).total_seconds() < self._directory_retry:
            return
        self._directory_refreshing[market] = True
        self._directory_attempt[market] = now
        self._directory_executor.submit(self._refresh_directory, market)

    def _refresh_directory(self, market):
        try:
            items = self._validate_directory(market, self._directory_loader(market))
            finished_at = self._now()
            with self._lock:
                self._directory_cache[market] = (finished_at, items)
                self._directory_errors[market] = None
        except Exception:
            failed_at = self._now()
            with self._lock:
                self._directory_errors[market] = "directory temporarily unavailable"
                self._directory_attempt[market] = failed_at
        finally:
            with self._lock:
                self._directory_refreshing[market] = False

    def search(self, raw_market, raw_query):
        market = validate_market(raw_market)
        query = "" if raw_query is None else str(raw_query).strip()
        if len(query) > 80:
            raise ValueError("query must be at most 80 characters")
        now = self._now()
        with self._lock:
            cached = self._directory_cache[market]
            age = None if cached is None else (now - cached[0]).total_seconds()
            if cached is not None and age <= self._stale_ttl:
                if age >= self._directory_ttl:
                    self._start_directory_refresh_locked(market, now)
                items = list(cached[1])
                updated_at = _utc_iso(cached[0])
                stale = age >= self._directory_ttl
                state = "READY"
                error = None
            else:
                self._start_directory_refresh_locked(market, now)
                items = []
                updated_at = None
                stale = False
                refreshing = self._directory_refreshing[market]
                state = "SYNCING" if refreshing else "ERROR"
                error = None if refreshing else self._directory_errors[market]

        folded = query.casefold()
        matches = []
        for item in items:
            symbol_folded = item["symbol"].casefold()
            name_folded = item["name"].casefold()
            if not folded:
                rank = 2
            elif symbol_folded == folded:
                rank = 0
            elif symbol_folded.startswith(folded):
                rank = 1
            elif folded in name_folded:
                rank = 2
            else:
                continue
            matches.append((rank, item["symbol"], item))
        matches.sort(key=lambda match: (match[0], match[1]))
        return {
            "items": [dict(match[2]) for match in matches[:20]],
            "hasNext": len(matches) > 20,
            "updatedAt": updated_at,
            "stale": stale,
            "state": state,
            "error": error,
        }

    def _resolve_instrument(self, market, symbol):
        now = self._now()
        with self._lock:
            cached = self._directory_cache[market]
            if cached is None or (now - cached[0]).total_seconds() > self._stale_ttl:
                self._start_directory_refresh_locked(market, now)
                raise UpstreamUnavailable("overseas directory is not ready")
            for item in cached[1]:
                if item["symbol"] == symbol:
                    return dict(item)
        raise InstrumentNotFound("overseas instrument not found")

    def _load_candles(self, instrument):
        rows = self._candle_loader(instrument["market"], instrument["symbol"])
        return normalize_overseas_candles(instrument, rows, self._now())

    def _fresh_candle_locked(self, key, now):
        cached = self._candle_cache.get(key)
        if cached is None or (now - cached[0]).total_seconds() >= self._candle_ttl:
            return None
        self._candle_cache.move_to_end(key)
        return dict(cached[1])

    def candles(self, raw_market, raw_symbol):
        market = validate_market(raw_market)
        symbol = validate_overseas_symbol(market, raw_symbol)
        instrument = self._resolve_instrument(market, symbol)
        key = (market, symbol)
        now = self._now()
        with self._lock:
            cached_result = self._fresh_candle_locked(key, now)
            if cached_result is not None:
                return cached_result
            future = self._candle_futures.get(key)

        if future is None:
            if not self._candle_slots.acquire(timeout=self._candle_admission_timeout):
                with self._lock:
                    cached_result = self._fresh_candle_locked(key, self._now())
                    future = self._candle_futures.get(key)
                if cached_result is not None:
                    return cached_result
                if future is None:
                    raise UpstreamUnavailable("overseas candle adapter busy")
            else:
                owns_slot = False
                with self._lock:
                    cached_result = self._fresh_candle_locked(key, self._now())
                    future = None if cached_result is not None else self._candle_futures.get(key)
                    if cached_result is None and future is None:
                        future = self._candle_executor.submit(self._load_candles, instrument)
                        self._candle_futures[key] = future
                        owns_slot = True

                        def complete(done, cache_key=key):
                            try:
                                result = done.result()
                            except Exception:
                                result = None
                            with self._lock:
                                if result is not None:
                                    completed_at = self._now()
                                    self._candle_cache[cache_key] = (completed_at, result)
                                    self._candle_cache.move_to_end(cache_key)
                                    while len(self._candle_cache) > self._max_candles:
                                        self._candle_cache.popitem(last=False)
                                self._candle_futures.pop(cache_key, None)
                            self._candle_slots.release()

                        future.add_done_callback(complete)
                if cached_result is not None:
                    self._candle_slots.release()
                    return cached_result
                if not owns_slot:
                    self._candle_slots.release()
        try:
            result = future.result(timeout=self._candle_wait_timeout)
            return dict(result)
        except Exception:
            now = self._now()
            with self._lock:
                cached = self._candle_cache.get(key)
                if cached is not None and (now - cached[0]).total_seconds() <= self._stale_ttl:
                    stale = dict(cached[1])
                    stale["stale"] = True
                    return stale
            raise UpstreamUnavailable("overseas candles unavailable") from None
