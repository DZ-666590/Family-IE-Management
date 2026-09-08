# Overseas Read-only Market Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add verified HK/US stock search, currency-correct reference closes and daily/weekly/monthly charts without overseas accounting writes.

**Architecture:** Independent in-memory overseas directory/candle caches in the loopback Python adapter; authenticated Java read-only proxy; frontend market tabs reusing the current selection styling and KLineChart renderer. Overseas instruments have market/symbol identity, never fabricated database IDs.

**Tech Stack:** Existing Python/AKShare/pandas/openpyxl/MiniRacer, Java 17 Spring Boot, React Query, Semi Select, KLineChart.

**Spec:** docs/superpowers/specs/2026-09-08-overseas-market-readonly-design.md

## Global Constraints

- 海外目录与行情只进入独立缓存，不写入 `securities`、`investment_trades`、报价快照或现金账本。
- 现有 A 股 `/api/securities/*` 与记账校验保持不变。海外数据源故障不阻止 A 股目录同步。
- 不关闭正式服务安全限制；实现时仅解析数据，不对来自上游的文本调用 Python `eval`。
- 香港按 `Asia/Hong_Kong`，美国按 `America/New_York` 处理交易日和夏令时。
- 海外页面固定说明“只读行情，暂不计入家庭资产”。
- Work only in existing stage2 worktree. Preserve staged/untracked `.idea` files. Explicit owned-path commits only, no push/merge/deployment without user authorization. No local application server or production database writes.

## Shared wire contracts

```typescript
type OverseasInstrument = {symbol:string;name:string;market:'HK'|'US';currency:string;exchange:string;timezone:string};
type OverseasSearch = {items:OverseasInstrument[];hasNext:boolean;updatedAt:string|null;stale:boolean;state:'SYNCING'|'READY'|'ERROR';error:string|null};
type OverseasCandles = {instrument:OverseasInstrument;symbol:string;source:'SINA';adjustment:'none';asOf:string|null;fetchedAt:string|null;stale:boolean;supported:boolean;bars:Array<{timestamp:number;open:number;high:number;low:number;close:number;volume:number;turnover:number|null}>};
```

Search paths: Python `/overseas/search`, Java `/api/overseas-market/search`, params `market=HK|US&q=`. Candle paths: Python `/overseas/candles`, Java `/api/overseas-market/candles`, params `market=HK|US&symbol=`. Java wraps the exact response in the existing ApiEnvelope. Python validation errors use 400, missing instruments 404, upstream failures 503; Java maps to established safe user-facing errors.

### Task 1: Overseas directory and candle adapter

**Files:** Create `scripts/market-data/overseas.py`, `overseas_sources.py`, `test_overseas.py`; modify `scripts/market-data/server.py` only for overseas route wiring; update `docs/operations/market-data-adapter.md` deployment files/dependency instructions.

**Interfaces:** Produce `OverseasMarketService.search(market,q)`, `.candles(market,symbol)`, injected directory/candle loaders and a clock for deterministic tests. Service errors must be converted by the existing HTTP handler, with no effect on A-share service state.

- [ ] Write literal tests before implementation. Use HK instrument `{symbol:'00700',name:'騰訊控股',market:'HK',currency:'HKD',exchange:'HKEX',timezone:'Asia/Hong_Kong'}`, US instrument AAPL/USD/NASDAQ/America/New_York. Assert unknown symbol rejection, query length >80 rejection, last complete trading day, missing turnover stays null, malformed OHLC rejection, and March US DST midnight timestamps (2026-03-06 00:00 NY = 1772773200000; derive/check independently before using fixture).
- [ ] Run `python3 -m unittest discover -s scripts/market-data -v`, record expected RED failures.
- [ ] Implement directory loaders with fixed trusted URLs, status checks, bounded bytes and connect/read timeouts. HKEX English XLSX: `https://www.hkex.com.hk/eng/services/trading/securities/securitieslists/ListOfSecurities.xlsx`; header row 2 contains Stock Code, Name of Securities, Category, Trading Currency. Include only Category=Equity, read explicit currency, zero-pad code to five digits. Optional Chinese name enrichment by joining official `https://www.hkex.com.hk/chi/services/trading/securities/securitieslists/ListOfSecurities_c.xlsx`; English identity/currency remains authoritative. Nasdaq sources: `https://www.nasdaqtrader.com/dynamic/SymDir/nasdaqlisted.txt` and `otherlisted.txt`; require correct headers and `File Creation Time:` footer, exclude Test Issue=Y, ETF=Y and named warrants/units/rights/preferred/debt. Parse both files before atomically publishing US directory; validate exact code and exchange metadata, cap directory 30000 entries and require credible counts (HK>=1000, US>=2000). Do not publish partial downloads as complete.
- [ ] Keep directory fetching nonblocking for first searches: return SYNCING and let a bounded background refresh publish the full validated directory. Refresh successful directories after 24h, retry failures no sooner than 60s; preserve valid cache for up to 7 days with stale=true. Independent market refresh jobs and A-share service state. Search returns at most20 exact/prefix/name matches and hasNext. Bound pending workers.
- [ ] Run raw SINA history decoding in an isolated subprocess with 20s timeout, max20MB output. Set MiniRacer flags `['--single-threaded','--jitless']` before any context creation. Reuse installed trusted decoder constants, only pass encoded data strings as arguments. HK raw AKShare `stock_hk_daily(...,adjust='')` has no eval branch; US must decode raw history without calling the high-level function that unconditionally fetches/evals factors. No executing remote Python/JS source. Normalize ascending daily rows; omit today/future in market timezone, limit to approximately740 calendar days, preserve null turnover if unavailable. Reject nonfinite/range errors and duplicate dates. Return source=SINA, adjustment=none and actual date metadata.
- [ ] Cache candle responses by market+symbol, TTL6h, max256 entries, stale fallback max7days. Coalesce same-key work and bound concurrent work; return explicit busy/unavailable rather than spawn unbounded processes. Resolve instrument against valid directory before requesting candles.
- [ ] Run Python suite GREEN plus py_compile. Root performs actual cloud probes from a temporary source copy under existing low-privilege/MemoryDenyWriteExecute settings; worker must not install or restart production services. Commit only owned source/tests/operations doc.

### Task 2: Authenticated Java read-only proxy

**Files:** Create `src/main/java/com/familyfinance/market/OverseasInstrument.java`, `OverseasSearchResponse.java`, `OverseasCandleResponse.java`, `OverseasMarketService.java`, `OverseasMarketController.java`; modify `MarketDataClient.java`; create `src/test/java/com/familyfinance/market/OverseasMarketApiTest.java` and focused response-validation test if needed.

**Interfaces:** Consume shared wire contracts and loopback paths from Task1. Public endpoints are GET only. Reuse existing `SecurityService.requireMembership(Authentication)` and ApiEnvelope; never call security resolve/catalog import or persistence repositories for overseas instruments.

- [ ] Write tests for unauthenticated rejection, authenticated non-member rejection, successful HK/USD metadata preservation, malformed market/symbol/query rejection before upstream call, wrong currency/timezone/symbol/source/adjustment response rejection, null-turnover acceptance, upstream error conversion, and absence of overseas creation in existing securities/trades tables. Use controlled stub HTTP adapter or existing market test injection patterns.
- [ ] Run focused Maven tests and record expected RED. Do not run application-starting Windows/Unix smoke tests.
- [ ] Extend MarketDataClient with typed search/candle methods using its already restricted loopback base; bounded query construction, no arbitrary URL input. New record shapes mirror shared contracts, bar turnover is nullable. Validate shape, max counts, identity/timezone, OHLC/chronology and supported/stale fields at Java trust boundary; do not convert timestamps using server default timezone.
- [ ] Implement controller with membership check before provider calls and safe domain exception mapping. Preserve the A-share client/service contracts and all CNY account restrictions unchanged.
- [ ] Run new tests plus existing market client/candle service tests; `mvn -DskipTests package` only if needed for compilation, not as test proof. Commit only owned Java/test paths.

### Task 3: Market tabs, overseas selector and reusable chart

**Files:** Create `frontend/src/features/investment/OverseasMarketPanel.tsx`, `OverseasMarket.test.tsx`, optional focused `overseas-market.ts` contracts/validation; modify `InvestmentsPage.tsx`, `StockChart.tsx`, `chart-data.ts`, `investment-market.scss`. If sharing selection behavior, create `useStockDropdown.ts` and change StockPicker.tsx to use it; preserve all prior picker tests.

**Interfaces:** Consume `/api/overseas-market/search` and `/candles` wire contracts. Keep existing `ChartSecurity` type intact; let StockChart accept either existing ChartSecurity or OverseasInstrument, using type narrowing rather than fake numeric IDs. A-share query keys remain unchanged; overseas keys include market+symbol.

- [ ] Write tests proving switching A/HK/US clears incompatible selection/old data, USD/HKD shown instead of RMB, search error differs from empty results, SYNCING refetches only while needed, selecting real result directly displays chart, and overseas view has no mutation entry or POST/PATCH call. Cover US local week/month boundaries across DST and preserve null turnover through aggregation.
- [ ] Run focused frontend tests RED.
- [ ] Add market tabs inside行情. Preserve A-share mode and selected security. Overseas panels are read-only; hide page-level investment creation while viewing overseas mode and hide A-share manual quote cards there. Normal positions/trades/account tabs are unchanged.
- [ ] Use existing Semi Select and stock-picker visual classes, exact-width positioning, ResizeObserver and Escape containment. Extract shared hook for duplicated positioning behavior rather than copy it. Search by code/name; while SYNCING poll at5s with a bounded user-visible waiting state, ERROR has explicit retry and no fabricated options.
- [ ] Extend chart formatting/time aggregation with explicit timezone and currency. Function APIs `aggregateBars(bars,period,timezone='Asia/Shanghai')`, `selectBarsForRange(bars,period,range,timezone='Asia/Shanghai')`; preserve all old call sites. Overseas chart uses none adjustment only and displays source/date, actual instrument currency; volume in shares and missing turnover unavailable. Pass undefined rather than null to optional KLineChart turnover fields. Daily bars are full history for indicator warmup before viewport framing.
- [ ] Run typecheck, all frontend tests and production build once. Root verifies cloud isolated UI for actual US/HK samples, market switching, no accounting writes and desktop/mobile dropdown geometry. Commit only owned frontend/test paths.

## Completion

- [ ] Whole-feature review after task-scoped gates; no live/prod claims from unit tests.
- [ ] Record source coverage, actual sample dates, verification limits and adapter deployment instructions in acceptance documentation.
- [ ] Do not push or deploy automatically; hand off the completed stage2 commits with next step clearly stated.
