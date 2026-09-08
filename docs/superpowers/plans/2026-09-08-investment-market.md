# Investment Market Integration Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development for independent backend work and scoped review. Execute continuously; user has requested implementation.

**Goal:** Replace manual stock registration with a real catalog, add KLineChart and durable investment onboarding.

**Architecture:** Loopback-only Python market adapter -> Spring Boot authenticated APIs -> existing React investment workspace. Money stays in the existing ledger.

**Tech Stack:** Java 17, Spring Boot, MySQL, Python 3.10, AKShare 1.18.88, BaoStock 0.9.3, React, KLineChart.

**Spec:** docs/superpowers/specs/2026-09-08-investment-market-design.md

## Global Constraints

- Preserve existing data, ledger money rules, IDs and user .idea changes. Work only in existing stage-2 worktree.
- Never store credentials or real server connection details in repository files.
- No local app startup or retired startup smoke workflows. Keep ordinary targeted tests/build checks.
- No manual stock registration; no fake market data. OPENING never deducts cash.
- HTTP adapter binds 127.0.0.1:8091. Inputs only public symbols and adjustment modes.
- No push/deploy claim without evidence; no CI waiting after a push.

### Task 1: Market adapter, Spring APIs and durable setup

**Files:** Create scripts/market-data/{server.py,requirements.txt,test_server.py}; systemd/runbook under docs/operations; new Java market catalog/client/candles and investment setup service/controller; migrations in both migration directories. Modify SecurityService/Repository/Entity, quote source projection/provider wiring and affected tests.

**Interfaces:** Implement exact endpoints and payloads from spec. Adapter `/directory` returns `{items:[{tsCode,name,market}],fetchedAt,stale}`. Adapter `/candles?symbol=600000.SH&adjust=none` returns the candles response from spec. Java env MARKET_DATA_URL defaults empty (disabled until configured). Catalog scheduler fetches outside DB transactions and atomically imports verified directory. Shared references preserve IDs.

- [ ] RED: Python unittest fixtures check invalid OHLC, symbol/adjust rejection, volume normalization, stale fallback and bounded upstream work; Java tests check unknown resolve rejected, valid catalog search/selection and household setup authorization.
```python
with self.assertRaises(ValueError):
    validate_symbol('600000.SH/../../env')
```
- [ ] GREEN: implement adapter validation/cache/isolated subprocess timeouts and Java catalog/candles/setup APIs. Security selection resolves existing verified ID, not arbitrary input. Existing legacy holdings remain manageable.
```java
if (existing == null) throw new ResourceConflictException("SECURITY_NOT_LISTED", "请从股票搜索结果中选择证券");
```
- [ ] Run `python3 -m unittest discover -s scripts/market-data -v` and targeted Maven market/investment tests using skip.npm and skip.installnodenpm flags. Update fixture setup rather than allowing unverified registration in production.
- [ ] Include repeatable adapter install/update instructions and systemd sandboxing, no global pip install. Keep service disabled until operator deployment.
- [ ] Self-review and report exact test evidence and changed paths. Commit only owned paths.

### Task 2: Investment UI integration

**Files:** Modify frontend/src/features/investment/InvestmentsPage.tsx and api/contracts.ts; create StockPicker.tsx, InvestmentSetup.tsx, StockChart.tsx and focused tests/style. Update frontend/package.json/package-lock.json for fixed KLineChart.

**Interfaces:** Consume Task 1 API shapes without exposing adapter to browser. Reuse RequestFn, Drawer, QueryState, query invalidation conventions and existing cash-account/OPENING mutation flow.

- [ ] RED: user can search/select true security and submit its ID without registration; existing holdings choose OPENING; empty setup completion persists; failure retains input; member has no mutation CTA.
```typescript
expect(screen.queryByRole('button', {name: '登记证券'})).not.toBeInTheDocument();
```
- [ ] GREEN: remove registration state/drawer/helper; preserve selected item outside filtered results; debounce search; show loading/error/no results distinctly. Add setup panel and continuation into existing account/trade forms.
- [ ] Add chart drawer, date/adjustment/period controls and source/freshness states. Aggregate daily bars for week/month using Shanghai dates; volume and amount sum, open first/close last/high max/low min. Dispose chart on unmount, resize safely, reject stale async responses via query keys.
```typescript
const queryKey = ['security-candles', security.id, adjustment];
```
- [ ] Run focused tests, frontend typecheck/full tests/build, then backend regression suite once integrated. Do not run local app startup.
- [ ] Independent diff review, fix consequential issues, record acceptance evidence. Provide handoff separating implemented, verified, pushed, deployed.
