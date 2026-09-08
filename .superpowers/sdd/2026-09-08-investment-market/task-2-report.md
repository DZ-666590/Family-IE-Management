# Frontend implementation report

Implemented real catalog StockPicker (debounced search, loading/error/retry/empty distinctions, retains selected result); removed registration flow and its payload helper. InvestmentSetup uses durable backend status and calls existing OPENING form for existing holdings or explicit completion for empty holdings. Confirmed zero cash valid; members read-only. Existing data/actions preserved. Quote/position names open KLineChart, independent stock exploration from quotes. Day/week/month aggregate by Shanghai calendar; qfq/none; source/date/stale/unsupported/error states; MA/VOL and optional MACD; lazy-loaded chart and ResizeObserver disposal. Cash write invalidation includes investment setup. BAOSTOCK added to source contract.

RED: InvestmentMarket.test.tsx and StockChart.test.tsx first failed resolving new component imports before implementation (exit 1). Updated prior registration tests to assert selected-stock draft protection instead.
GREEN: npm run typecheck passed; focused 24/24; full npm test -- --run: 37 files, 217 tests passed. npm run build succeeded. Existing main chunk warning persists (616 kB uncompressed); KLineChart is a separate dynamically imported chunk (222 kB).

No local app startup, no browser validation yet; no production database modifications. Root will verify integration after backend task. No push/deploy performed.

Files: frontend/package*.json, api/contracts.ts, shared/write-refresh.ts, asset/AssetsInvestments.test.tsx, investment/InvestmentsPage.tsx, StockPicker.tsx, InvestmentSetup.tsx, StockChart.tsx, chart-data.ts, investment-market.scss, InvestmentMarket.test.tsx, StockChart.test.tsx.

## Review-fix follow-up

Fixed the review findings without changing package dependencies or starting the local application. InvestmentSetup now treats `hasTrades` as prior activity, receives account-query loading/error/refetch state without collapsing it to empty arrays, and exposes no creation/completion actions until both prerequisites resolve. StockPicker gates search on a ready catalog, distinguishes catalog loading/request/state errors from a genuine empty search, retains a selected security reference, provides retry, and shows the catalog update time.

StockChart now supports 1 month, 3 months, 1 year, and all returned history. It aggregates complete daily input before applying the selected range, labels the server-bounded approximately two-year history and potentially partial week/month boundary periods, and names the detail disclosure for the selected period. VOL and MACD explicitly use red-up `#c74b50` and green-down `#31846a`; a failed chart initialization no longer leaves an empty `role="img"`.

Active positions now expose an owner/admin-only `记录卖出` action. The resulting SELL draft carries and locks the position account plus the existing security id/code/name, so historical unverified securities can be cleared without reappearing in verified-only catalog search. Member views remain mutation-free.

RED: focused `InvestmentMarket.test.tsx` + `StockChart.test.tsx` initially reported 9 expected failures (12 passing), covering all review branches. GREEN: focused 21/21 passed. Final frontend verification: 37 files and 228/228 tests passed; `npm run typecheck` passed; `npm run build` passed. Vite still reports the existing main-chunk size warning (620.42 kB uncompressed); the chart library remains a separate 222.35 kB lazy chunk.

Changed paths: `frontend/src/features/asset/AssetsInvestments.test.tsx`; `frontend/src/features/investment/InvestmentMarket.test.tsx`; `InvestmentSetup.tsx`; `InvestmentsPage.tsx`; `StockChart.test.tsx`; `StockChart.tsx`; `StockPicker.tsx`; `chart-data.ts`; `investment-market.scss`; and this report. Commit: the final frontend review-fix commit containing this section (`fix: address investment market frontend review`).

### Cloud-preview indicator warm-up correction

The first range implementation passed only the selected window to KLineChart. Cloud preview showed that a one-month window left MA30/MA60 unavailable and also shortened MACD warm-up. The final implementation separates calculation data from viewport framing: the loader receives all period-aggregated history, while the post-aggregation selected window controls the latest-detail values and KLineChart bar spacing/right-edge viewport. This retains correct boundary OHLC and indicator context together.

RED: the new StockChart regression failed because the one-month loader contained only the visible candle and no viewport call occurred. GREEN: StockChart 7/7 passed, including full-history loader and changed viewport assertions. Fresh `npm run typecheck` and `npm run build` passed after the correction; Vite's existing main-chunk warning remains (620.63 kB uncompressed, KLineChart lazy chunk 222.35 kB). Per root coordination, the earlier final full frontend result remains 228/228 and was not rerun after this isolated chart-only correction; root owns the next combined full-suite gate.

### Published-catalog fallback and viewport wording

Catalog search availability now follows published catalog count rather than requiring state `READY`. A status response such as `ERROR` with `count > 0` keeps the last successful catalog searchable and selectable, while showing the refresh failure, backend error, last successful `updatedAt`, and retry. A catalog-status request failure with no known count still blocks search, preserving the distinction from a genuine no-results search.

The chart selector is now labelled `视窗缩放`, with the note `按所选时间跨度缩放，可拖动查看更早历史。` This accurately describes the klinecharts 50-pixel bar-spacing cap for short weekly/monthly ranges without discarding older warm-up bars.

RED: focused InvestmentMarket + StockChart reported 3 expected failures for the stale-catalog branch and viewport wording. GREEN: the same focused suites passed 23/23. Fresh `npm run typecheck` and `npm run build` passed; the existing bundle warning remains (621.10 kB main chunk, 222.35 kB lazy KLineChart chunk). Root owns the final full frontend run. Commit: the follow-up commit containing this section (`fix: keep published investment catalog usable`).

### Catalog state allowlist gate

Catalog availability is now explicitly limited to positive-count `READY` and `ERROR` states. `UNKNOWN` and `DISABLED` block new searches even if a malformed or transitional response includes a positive count; the existing locked historical-position SELL path remains independent of catalog search. RED: both table cases searched before the allowlist. GREEN: InvestmentMarket 18/18 passed and `npm run typecheck` passed. No broader suite was run by coordination request. Commit: the focused gate commit containing this section (`fix: restrict searchable catalog states`).
