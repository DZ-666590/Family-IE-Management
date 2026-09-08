#!/usr/bin/env python3
"""Loopback-only A-share directory and BaoStock candle adapter."""

import argparse
import contextlib
import hashlib
from pathlib import Path
import json
import re
import subprocess
import sys
import threading
from collections import OrderedDict
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from overseas import (
    InstrumentNotFound,
    OverseasMarketService,
    UpstreamUnavailable as OverseasUpstreamUnavailable,
)


SYMBOL = re.compile(r"^[0-9]{6}\.(SH|SZ|BJ)$")
ADJUSTMENTS = {"none", "qfq"}
SHANGHAI = timezone(timedelta(hours=8))


class UpstreamUnavailable(RuntimeError):
    pass


def validate_symbol(raw):
    value = "" if raw is None else str(raw).strip().upper()
    if not SYMBOL.fullmatch(value):
        raise ValueError("symbol must be six digits followed by .SH, .SZ or .BJ")
    return value


def validate_adjust(raw):
    value = "" if raw is None else str(raw).strip().lower()
    if value not in ADJUSTMENTS:
        raise ValueError("adjust must be none or qfq")
    return value


def _decimal(raw, label, allow_zero=False, allow_signed=False):
    try:
        value = Decimal(str(raw))
    except (InvalidOperation, ValueError):
        raise ValueError(f"invalid {label}") from None
    if not value.is_finite() or (not allow_signed and value < 0) or (not allow_zero and value == 0):
        raise ValueError(f"invalid {label}")
    return value


def _json_number(value):
    integral = value.to_integral_value()
    return int(integral) if value == integral else float(value)


def normalize_candles(symbol, adjustment, rows, fetched_at):
    symbol = validate_symbol(symbol)
    adjustment = validate_adjust(adjustment)
    bars = []
    previous = None
    for row in rows:
        try:
            trading_day = date.fromisoformat(str(row["date"]))
            adjusted = adjustment == "qfq"
            open_price = _decimal(row["open"], "open", allow_zero=adjusted, allow_signed=adjusted)
            high = _decimal(row["high"], "high", allow_zero=adjusted, allow_signed=adjusted)
            low = _decimal(row["low"], "low", allow_zero=adjusted, allow_signed=adjusted)
            close = _decimal(row["close"], "close", allow_zero=adjusted, allow_signed=adjusted)
            volume = _decimal(row["volume"], "volume", allow_zero=True)
            turnover = _decimal(row["turnover"], "turnover", allow_zero=True)
        except (KeyError, TypeError, ValueError) as exception:
            raise ValueError("invalid candle row") from exception
        if low > high or not (low <= open_price <= high) or not (low <= close <= high):
            raise ValueError("invalid OHLC range")
        if volume != volume.to_integral_value():
            raise ValueError("volume must be whole shares")
        if previous is not None and trading_day <= previous:
            raise ValueError("candles must be unique and ascending")
        previous = trading_day
        timestamp = int(datetime.combine(trading_day, datetime.min.time(), SHANGHAI).timestamp() * 1000)
        bars.append({
            "timestamp": timestamp,
            "open": _json_number(open_price),
            "high": _json_number(high),
            "low": _json_number(low),
            "close": _json_number(close),
            "volume": int(volume),
            "turnover": _json_number(turnover),
        })
    fetched = fetched_at.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
    return {
        "symbol": symbol,
        "source": "BAOSTOCK",
        "adjustment": adjustment,
        "asOf": None if not rows else str(rows[-1]["date"]),
        "fetchedAt": fetched,
        "stale": False,
        "supported": symbol.endswith((".SH", ".SZ")),
        "bars": bars,
    }


def run_isolated(command, timeout_seconds):
    try:
        result = subprocess.run(
            command,
            stdin=subprocess.DEVNULL,
            capture_output=True,
            text=True,
            timeout=timeout_seconds,
            check=False,
            env={"PATH": "/usr/bin:/bin:/usr/local/bin", "PYTHONNOUSERSITE": "1"},
        )
    except (subprocess.TimeoutExpired, OSError) as exception:
        raise UpstreamUnavailable("upstream worker timed out") from exception
    if result.returncode != 0 or len(result.stdout) > 20_000_000:
        raise UpstreamUnavailable("upstream worker failed")
    try:
        payload = json.loads(result.stdout)
    except json.JSONDecodeError as exception:
        raise UpstreamUnavailable("upstream worker returned invalid JSON") from exception
    if not isinstance(payload, dict):
        raise UpstreamUnavailable("upstream worker returned invalid payload")
    return payload


def _directory_worker():
    import akshare as ak

    loaders = [
        ("SH", ak.stock_info_sh_name_code, {"symbol": "主板A股"}, "证券代码", "证券简称"),
        ("SH", ak.stock_info_sh_name_code, {"symbol": "科创板"}, "证券代码", "证券简称"),
        ("SZ", ak.stock_info_sz_name_code, {"symbol": "A股列表"}, "A股代码", "A股简称"),
        ("BJ", ak.stock_info_bj_name_code, {}, "证券代码", "证券简称"),
    ]
    items = []
    for market, loader, arguments, code_column, name_column in loaders:
        with contextlib.redirect_stdout(sys.stderr):
            frame = loader(**arguments)
        for _, row in frame.iterrows():
            code = str(row[code_column]).strip().zfill(6)
            items.append({"tsCode": f"{code}.{market}", "name": str(row[name_column]).strip(), "market": market})
    return {"items": items}


def _candles_worker(symbol, adjustment):
    import baostock as bs

    code, market = symbol.split(".")
    if market == "BJ":
        return {"rows": []}
    with contextlib.redirect_stdout(sys.stderr):
        login = bs.login()
    if login.error_code != "0":
        raise RuntimeError("BaoStock login failed")
    try:
        end = datetime.now(SHANGHAI).date() - timedelta(days=1)
        start = end - timedelta(days=740)
        with contextlib.redirect_stdout(sys.stderr):
            result = bs.query_history_k_data_plus(
                f"{market.lower()}.{code}",
                "date,open,high,low,close,volume,amount",
                start_date=start.isoformat(),
                end_date=end.isoformat(),
                frequency="d",
                adjustflag="3" if adjustment == "none" else "2",
            )
        if result.error_code != "0":
            raise RuntimeError("BaoStock query failed")
        rows = []
        while True:
            with contextlib.redirect_stdout(sys.stderr):
                has_next = result.next()
            if not has_next:
                break
            with contextlib.redirect_stdout(sys.stderr):
                values = result.get_row_data()
            if len(values) != 7 or any(value == "" for value in values):
                continue
            rows.append(dict(zip(
                ("date", "open", "high", "low", "close", "volume", "turnover"), values)))
        return {"rows": rows}
    finally:
        with contextlib.redirect_stdout(sys.stderr):
            bs.logout()


class MarketDataService:
    def __init__(self, directory_loader=None, candle_loader=None, cache_ttl_seconds=21600,
                 stale_ttl_seconds=604800, max_candle_entries=256, min_directory_items=5000,
                 required_directory_markets=None, upstream_admission_timeout_seconds=1):
        script = __file__
        self._directory_loader = directory_loader or (
            lambda: run_isolated([sys.executable, script, "--worker", "directory"], 45)["items"])
        self._candle_loader = candle_loader or (
            lambda symbol, adjust: run_isolated(
                [sys.executable, script, "--worker", "candles", symbol, adjust], 20)["rows"])
        self._ttl = cache_ttl_seconds
        self._stale_ttl = stale_ttl_seconds
        self._max_candles = max_candle_entries
        self._min_directory = min_directory_items
        self._required_markets = required_directory_markets or {"SH", "SZ", "BJ"}
        self._directory_cache = None
        self._candle_cache = OrderedDict()
        self._lock = threading.Lock()
        self._upstream_lock = threading.BoundedSemaphore(1)
        self._admission_timeout = upstream_admission_timeout_seconds

    def directory(self):
        now = datetime.now(timezone.utc)
        with self._lock:
            cached = self._directory_cache
            if cached and (now - cached[0]).total_seconds() < self._ttl:
                return dict(cached[1])
        if not self._upstream_lock.acquire(timeout=self._admission_timeout):
            raise UpstreamUnavailable("market adapter busy")
        try:
            with self._lock:
                cached = self._directory_cache
                if cached and (now - cached[0]).total_seconds() < self._ttl:
                    return dict(cached[1])
            try:
                items = self._validate_directory(self._directory_loader())
                result = {
                    "items": items,
                    "fetchedAt": now.isoformat().replace("+00:00", "Z"),
                    "stale": False,
                }
                with self._lock:
                    self._directory_cache = (now, result)
                return dict(result)
            except (UpstreamUnavailable, ValueError, KeyError, TypeError):
                if cached and (now - cached[0]).total_seconds() <= self._stale_ttl:
                    stale = dict(cached[1])
                    stale["stale"] = True
                    return stale
                raise UpstreamUnavailable("directory unavailable") from None
        finally:
            self._upstream_lock.release()

    def candles(self, raw_symbol, raw_adjust):
        symbol = validate_symbol(raw_symbol)
        adjustment = validate_adjust(raw_adjust)
        if symbol.endswith(".BJ"):
            now = datetime.now(timezone.utc)
            return normalize_candles(symbol, adjustment, [], now)
        key = (symbol, adjustment)
        now = datetime.now(timezone.utc)
        with self._lock:
            cached = self._candle_cache.get(key)
            if cached and (now - cached[0]).total_seconds() < self._ttl:
                self._candle_cache.move_to_end(key)
                return dict(cached[1])
        if not self._upstream_lock.acquire(timeout=self._admission_timeout):
            raise UpstreamUnavailable("market adapter busy")
        try:
            with self._lock:
                cached = self._candle_cache.get(key)
                if cached and (now - cached[0]).total_seconds() < self._ttl:
                    self._candle_cache.move_to_end(key)
                    return dict(cached[1])
            try:
                rows = self._candle_loader(symbol, adjustment)
                result = normalize_candles(symbol, adjustment, rows, now)
                with self._lock:
                    self._candle_cache[key] = (now, result)
                    self._candle_cache.move_to_end(key)
                    while len(self._candle_cache) > self._max_candles:
                        self._candle_cache.popitem(last=False)
                return dict(result)
            except (UpstreamUnavailable, ValueError, KeyError, TypeError):
                if cached and (now - cached[0]).total_seconds() <= self._stale_ttl:
                    stale = dict(cached[1])
                    stale["stale"] = True
                    return stale
                raise UpstreamUnavailable("candles unavailable") from None
        finally:
            self._upstream_lock.release()

    def _validate_directory(self, raw_items):
        if not isinstance(raw_items, list) or len(raw_items) < self._min_directory:
            raise ValueError("directory is incomplete")
        by_code = {}
        markets = set()
        for raw in raw_items:
            symbol = validate_symbol(raw.get("tsCode"))
            market = str(raw.get("market", "")).strip().upper()
            name = str(raw.get("name", "")).strip()
            if symbol.rsplit(".", 1)[1] != market or not name or len(name) > 100:
                raise ValueError("invalid directory item")
            if symbol in by_code:
                raise ValueError("duplicate directory item")
            by_code[symbol] = {"tsCode": symbol, "name": name, "market": market}
            markets.add(market)
        if not self._required_markets.issubset(markets):
            raise ValueError("directory is missing a market")
        return [by_code[key] for key in sorted(by_code)]


def release_health(directory):
    directory = Path(directory)
    try:
        marker = json.loads((directory / 'deployment.json').read_text())
        required = {'server.py', 'overseas.py', 'overseas_sources.py', 'requirements.txt'}
        if (marker.get('schema') != 1 or not re.fullmatch('[0-9a-f]{40}', marker.get('commit', ''))
                or set(marker.get('files', {})) != required):
            raise ValueError('Invalid release manifest')
        for name, digest in marker['files'].items():
            if hashlib.sha256((directory / name).read_bytes()).hexdigest() != digest:
                raise ValueError('Adapter integrity check failed')
        return {'status': 'ready', 'commit': marker['commit'], 'capabilities': ['HK', 'US']}
    except (OSError, ValueError, TypeError):
        return {'status': 'unversioned', 'commit': None, 'capabilities': []}


class Handler(BaseHTTPRequestHandler):
    service = None
    overseas_service = None
    release = None

    def do_GET(self):
        parsed = urlparse(self.path)
        try:
            if parsed.path == '/health' and not parsed.query:
                payload = self.release or {'status': 'unversioned', 'commit': None}
                self._json(200 if payload['status'] == 'ready' else 503, payload)
                return
            if parsed.path == "/directory" and not parsed.query:
                self._json(200, self.service.directory())
                return
            if parsed.path == "/candles":
                query = parse_qs(parsed.query, keep_blank_values=True)
                if set(query) - {"symbol", "adjust"} or len(query.get("symbol", [])) != 1 or len(query.get("adjust", [])) != 1:
                    raise ValueError("invalid query")
                self._json(200, self.service.candles(query["symbol"][0], query["adjust"][0]))
                return
            if parsed.path == "/overseas/search":
                query = parse_qs(parsed.query, keep_blank_values=True)
                if set(query) - {"market", "q"} or len(query.get("market", [])) != 1 or len(query.get("q", [])) != 1:
                    raise ValueError("invalid query")
                self._json(200, self.overseas_service.search(query["market"][0], query["q"][0]))
                return
            if parsed.path == "/overseas/candles":
                query = parse_qs(parsed.query, keep_blank_values=True)
                if set(query) - {"market", "symbol"} or len(query.get("market", [])) != 1 or len(query.get("symbol", [])) != 1:
                    raise ValueError("invalid query")
                self._json(200, self.overseas_service.candles(query["market"][0], query["symbol"][0]))
                return
            self._json(404, {"error": "not found"})
        except ValueError as exception:
            self._json(400, {"error": str(exception)})
        except InstrumentNotFound as exception:
            self._json(404, {"error": str(exception)})
        except (UpstreamUnavailable, OverseasUpstreamUnavailable):
            self._json(503, {"error": "market data temporarily unavailable"})

    def log_message(self, format_string, *args):
        sys.stderr.write("market-data " + (format_string % args) + "\n")

    def _json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class BoundedThreadingHTTPServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, handler, max_workers=8):
        self._workers = threading.BoundedSemaphore(max_workers)
        super().__init__(address, handler)

    def process_request(self, request, client_address):
        if not self._workers.acquire(timeout=0.1):
            try:
                body = b'{"error":"market adapter busy"}'
                request.sendall(
                    b"HTTP/1.1 503 Service Unavailable\r\nContent-Type: application/json\r\n"
                    + f"Content-Length: {len(body)}\r\nConnection: close\r\n\r\n".encode("ascii") + body)
            finally:
                self.shutdown_request(request)
            return
        super().process_request(request, client_address)

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._workers.release()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--worker", choices=("directory", "candles"))
    parser.add_argument("worker_args", nargs="*")
    arguments = parser.parse_args()
    if arguments.worker == "directory":
        print(json.dumps(_directory_worker(), ensure_ascii=False, separators=(",", ":")))
        return
    if arguments.worker == "candles":
        if len(arguments.worker_args) != 2:
            raise SystemExit(2)
        symbol = validate_symbol(arguments.worker_args[0])
        adjustment = validate_adjust(arguments.worker_args[1])
        print(json.dumps(_candles_worker(symbol, adjustment), ensure_ascii=False, separators=(",", ":")))
        return
    Handler.release = release_health(Path(__file__).resolve().parent)
    Handler.service = MarketDataService()
    Handler.overseas_service = OverseasMarketService()
    BoundedThreadingHTTPServer(("127.0.0.1", 8091), Handler).serve_forever()


if __name__ == "__main__":
    main()
