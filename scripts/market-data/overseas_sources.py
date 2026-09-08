#!/usr/bin/env python3
"""Trusted overseas directory sources and isolated raw SINA candle worker."""

import argparse
import csv
import io
import json
import math
import os
import re
import subprocess
import sys
import tempfile


HKEX_ENGLISH_URL = "https://www.hkex.com.hk/eng/services/trading/securities/securitieslists/ListOfSecurities.xlsx"
HKEX_CHINESE_URL = "https://www.hkex.com.hk/chi/services/trading/securities/securitieslists/ListOfSecurities_c.xlsx"
NASDAQ_LISTED_URL = "https://www.nasdaqtrader.com/dynamic/SymDir/nasdaqlisted.txt"
NASDAQ_OTHER_URL = "https://www.nasdaqtrader.com/dynamic/SymDir/otherlisted.txt"

MAX_DIRECTORY_BYTES = 10_000_000
MAX_WORKER_OUTPUT_BYTES = 20_000_000
MAX_DIRECTORY_ITEMS = 30_000

HK_CODE = re.compile(r"^[0-9]{1,5}$")
US_CODE = re.compile(r"^[A-Z][A-Z0-9.-]{0,9}$")
US_PREFERRED_CODE = re.compile(r"^[A-Z][A-Z0-9.-]{0,8}\$[A-Z]$")
US_NON_EQUITY_NAME = re.compile(
    r"\b(?:warrants?|units?|rights?|preferred|preference|pfd|notes?|bonds?|debentures?|debt)\b",
    re.IGNORECASE,
)
OTHER_EXCHANGES = {
    "A": "NYSE AMERICAN",
    "N": "NYSE",
    "P": "NYSE ARCA",
    "Z": "CBOE BZX",
    "V": "IEX",
}


def _download_bytes(url, max_bytes=MAX_DIRECTORY_BYTES, connect_timeout=5, read_timeout=70):
    import requests

    response = requests.get(url, stream=True, timeout=(connect_timeout, read_timeout))
    response.raise_for_status()
    content_length = response.headers.get("Content-Length")
    if content_length is not None and int(content_length) > max_bytes:
        raise ValueError("upstream response is too large")
    chunks = []
    size = 0
    for chunk in response.iter_content(chunk_size=64 * 1024):
        if not chunk:
            continue
        size += len(chunk)
        if size > max_bytes:
            raise ValueError("upstream response is too large")
        chunks.append(chunk)
    return b"".join(chunks)


def _workbook_rows(payload, required_headers):
    from openpyxl import load_workbook

    workbook = load_workbook(io.BytesIO(payload), read_only=True, data_only=True)
    try:
        sheet = workbook.active
        return workbook_values_to_rows(sheet.iter_rows(values_only=True), required_headers)
    finally:
        workbook.close()


def workbook_values_to_rows(values, required_headers):
    iterator = iter(values)
    next(iterator, None)  # title
    next(iterator, None)  # update timestamp
    raw_headers = next(iterator, None)
    if raw_headers is None:
        raise ValueError("workbook is missing header row 3")
    headers = ["" if value is None else str(value).strip() for value in raw_headers]
    if not set(required_headers).issubset(headers):
        raise ValueError("workbook has unexpected headers")
    return [
        {header: value for header, value in zip(headers, row_values) if header}
        for row_values in iterator
        if any(value is not None for value in row_values)
    ]


def _hk_symbol(raw):
    if isinstance(raw, bool):
        raise ValueError("invalid HK stock code")
    if isinstance(raw, float):
        if not raw.is_integer():
            raise ValueError("invalid HK stock code")
        raw = int(raw)
    value = str(raw).strip()
    if value.endswith(".0") and value[:-2].isdigit():
        value = value[:-2]
    if not HK_CODE.fullmatch(value) or int(value) <= 0:
        raise ValueError("invalid HK stock code")
    return value.zfill(5)


def build_hk_directory(english_rows, chinese_rows=None, min_items=1000):
    chinese_names = {}
    for row in chinese_rows or []:
        try:
            symbol = _hk_symbol(row.get("股份代號"))
            name = str(row.get("股份名稱") or "").strip()
        except (TypeError, ValueError):
            continue
        if name and len(name) <= 200:
            chinese_names[symbol] = name

    by_symbol = {}
    for row in english_rows:
        if str(row.get("Category") or "").strip().casefold() != "equity":
            continue
        symbol = _hk_symbol(row.get("Stock Code"))
        english_name = str(row.get("Name of Securities") or "").strip()
        currency = str(row.get("Trading Currency") or "").strip().upper()
        if not english_name or len(english_name) > 200 or not re.fullmatch(r"[A-Z]{3}", currency):
            raise ValueError("invalid HK equity metadata")
        if symbol in by_symbol:
            raise ValueError("duplicate HK equity")
        by_symbol[symbol] = {
            "symbol": symbol,
            "name": chinese_names.get(symbol, english_name),
            "market": "HK",
            "currency": currency,
            "exchange": "HKEX",
            "timezone": "Asia/Hong_Kong",
        }
    if not min_items <= len(by_symbol) <= MAX_DIRECTORY_ITEMS:
        raise ValueError("HK directory has an implausible size")
    return [by_symbol[symbol] for symbol in sorted(by_symbol)]


def load_hk_directory(download=_download_bytes, min_items=1000):
    english_rows = _workbook_rows(
        download(HKEX_ENGLISH_URL),
        ("Stock Code", "Name of Securities", "Category", "Trading Currency"),
    )
    chinese_rows = []
    try:
        chinese_rows = _workbook_rows(
            download(HKEX_CHINESE_URL),
            ("股份代號", "股份名稱", "分類", "交易貨幣"),
        )
    except Exception:
        # Chinese names are enrichment only; English identity and currency stay authoritative.
        chinese_rows = []
    return build_hk_directory(english_rows, chinese_rows, min_items=min_items)


def _is_named_equity(name):
    return bool(name) and len(name) <= 200 and not US_NON_EQUITY_NAME.search(name)


def parse_nasdaq_file(payload, listed):
    try:
        text = payload.decode("ascii")
    except (AttributeError, UnicodeDecodeError) as exception:
        raise ValueError("Nasdaq directory is not ASCII") from exception
    lines = [line.rstrip("\r") for line in text.splitlines() if line.strip()]
    listed_header = [
        "Symbol", "Security Name", "Market Category", "Test Issue", "Financial Status",
        "Round Lot Size", "ETF", "NextShares",
    ]
    other_header = [
        "ACT Symbol", "Security Name", "Exchange", "CQS Symbol", "ETF",
        "Round Lot Size", "Test Issue", "NASDAQ Symbol",
    ]
    expected_header = listed_header if listed else other_header
    if len(lines) < 3 or lines[0].split("|") != expected_header:
        raise ValueError("Nasdaq directory has unexpected headers")
    if not lines[-1].startswith("File Creation Time:"):
        raise ValueError("Nasdaq directory is missing completion footer")

    items = []
    reader = csv.DictReader(lines[1:-1], fieldnames=expected_header, delimiter="|")
    for row in reader:
        if None in row or row["Test Issue"] != "N" or row["ETF"] != "N":
            if None in row:
                raise ValueError("Nasdaq directory row has unexpected fields")
            continue
        symbol = row["Symbol" if listed else "ACT Symbol"].strip().upper()
        name = row["Security Name"].strip()
        if not _is_named_equity(name) or US_PREFERRED_CODE.fullmatch(symbol):
            continue
        if not US_CODE.fullmatch(symbol):
            raise ValueError("invalid US symbol")
        if listed:
            if row["Market Category"] not in {"Q", "G", "S"}:
                raise ValueError("invalid Nasdaq market category")
            exchange = "NASDAQ"
        else:
            exchange_code = row["Exchange"].strip().upper()
            if exchange_code not in OTHER_EXCHANGES:
                raise ValueError("invalid US exchange code")
            exchange = OTHER_EXCHANGES[exchange_code]
        items.append({
            "symbol": symbol,
            "name": name,
            "market": "US",
            "currency": "USD",
            "exchange": exchange,
            "timezone": "America/New_York",
        })
    return items


def load_us_directory(download=_download_bytes, min_items=2000):
    # Fetch both complete files before parsing or publishing either one.
    listed_payload = download(NASDAQ_LISTED_URL)
    other_payload = download(NASDAQ_OTHER_URL)
    combined = parse_nasdaq_file(listed_payload, listed=True) + parse_nasdaq_file(other_payload, listed=False)
    by_symbol = {}
    for item in combined:
        if item["symbol"] in by_symbol:
            raise ValueError("duplicate US symbol")
        by_symbol[item["symbol"]] = item
    if not min_items <= len(by_symbol) <= MAX_DIRECTORY_ITEMS:
        raise ValueError("US directory has an implausible size")
    return [by_symbol[symbol] for symbol in sorted(by_symbol)]


def load_directory(market):
    if market == "HK":
        return load_hk_directory()
    if market == "US":
        return load_us_directory()
    raise ValueError("market must be HK or US")


def _set_safe_v8_flags():
    from py_mini_racer import MiniRacer

    # The Linux dependency used by AKShare reads this class value on first context creation.
    MiniRacer.v8_flags = ["--single-threaded", "--jitless"]
    return MiniRacer


def _plain_number(value):
    if value is None:
        return None
    if hasattr(value, "item"):
        value = value.item()
    if isinstance(value, float) and not math.isfinite(value):
        return None
    return value


def normalize_sina_records(records, market):
    rows = []
    for raw in records:
        raw_date = raw.get("date")
        if hasattr(raw_date, "isoformat"):
            raw_date = raw_date.isoformat()
        rows.append({
            "date": str(raw_date),
            "open": _plain_number(raw.get("open")),
            "high": _plain_number(raw.get("high")),
            "low": _plain_number(raw.get("low")),
            "close": _plain_number(raw.get("close")),
            "volume": _plain_number(raw.get("volume")),
            # SINA's documented US raw contract is OHLCV only. Its decoded amount
            # field is not verified turnover and must remain unavailable.
            "turnover": None if market == "US" else _plain_number(raw.get("amount", raw.get("turnover"))),
        })
    return rows


def load_us_sina_rows(symbol, download=_download_bytes, decode=None):
    symbol = str(symbol).strip().upper()
    if not US_CODE.fullmatch(symbol):
        raise ValueError("invalid US symbol")
    payload = download(f"https://finance.sina.com.cn/staticdata/us/{symbol}")
    text = payload.decode("utf-8")
    if "=" not in text or ";" not in text:
        raise ValueError("SINA history has unexpected framing")
    encoded = text.split("=", 1)[1].split(";", 1)[0].strip().strip('"')
    if not encoded or len(encoded) > MAX_WORKER_OUTPUT_BYTES:
        raise ValueError("SINA history payload is invalid")
    if decode is None:
        MiniRacer = _set_safe_v8_flags()
        from akshare.stock.cons import zh_js_decode

        context = MiniRacer()
        context.eval(zh_js_decode)  # Installed AKShare decoder constant, never remote source.

        def decode(value):
            return context.call("d", value)
    return normalize_sina_records(decode(encoded), "US")


def load_sina_rows(market, symbol):
    if market == "HK":
        _set_safe_v8_flags()
        import akshare as ak

        # Raw adjustment is the only stock_hk_daily branch that does not fetch/eval factors.
        frame = ak.stock_hk_daily(symbol=symbol, adjust="")
        return normalize_sina_records(frame.to_dict("records"), market)
    if market == "US":
        return load_us_sina_rows(symbol)
    raise ValueError("market must be HK or US")


def run_candle_worker(market, symbol, timeout_seconds=20):
    command = [sys.executable, os.path.abspath(__file__), "--worker", market, symbol]
    environment = {
        "PATH": "/usr/bin:/bin:/usr/local/bin",
        "PYTHONNOUSERSITE": "1",
        "PYTHONUNBUFFERED": "1",
    }
    with tempfile.TemporaryFile() as output:
        process = subprocess.Popen(
            command,
            stdin=subprocess.DEVNULL,
            stdout=output,
            stderr=subprocess.DEVNULL,
            env=environment,
        )
        try:
            process.wait(timeout=timeout_seconds)
        except subprocess.TimeoutExpired as exception:
            process.kill()
            process.wait()
            raise RuntimeError("SINA worker timed out") from exception
        output.seek(0, os.SEEK_END)
        size = output.tell()
        if process.returncode != 0 or size > MAX_WORKER_OUTPUT_BYTES:
            raise RuntimeError("SINA worker failed")
        output.seek(0)
        try:
            payload = json.loads(output.read().decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exception:
            raise RuntimeError("SINA worker returned invalid JSON") from exception
    if not isinstance(payload, dict) or not isinstance(payload.get("rows"), list):
        raise RuntimeError("SINA worker returned invalid payload")
    return payload["rows"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--worker", action="store_true")
    parser.add_argument("market", nargs="?")
    parser.add_argument("symbol", nargs="?")
    arguments = parser.parse_args()
    if not arguments.worker or arguments.market not in {"HK", "US"} or not arguments.symbol:
        raise SystemExit(2)
    print(json.dumps(
        {"rows": load_sina_rows(arguments.market, arguments.symbol)},
        ensure_ascii=False,
        separators=(",", ":"),
        allow_nan=False,
    ))


if __name__ == "__main__":
    main()
