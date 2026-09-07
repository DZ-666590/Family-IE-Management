# Round 1 Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Fix the eight approved reliability issues and deploy the verified current branch.

**Architecture:** Preserve the modular monolith and public business contracts. Centralize shared date, paging, cache-refresh and form-lifecycle behavior; leave financial domain rules and visual design unchanged.

**Tech Stack:** Java 17, Spring Boot, React, TypeScript, TanStack Query, React Router, Semi Design.

**Spec:** `docs/superpowers/specs/2026-09-07-round1-reliability.md`

## Global Constraints

- Java 17 / Spring Boot / React / TypeScript / Semi Design; preserve existing visual and business rules.
- No database migration, funding-account integration, new loan calculation strategy, or unrelated second-round feature.
- Keep `codex/clarity-workspace` in the existing worktree. Do not touch other checkouts or user data.
- Tests write only isolated data; production verification is read-only.
- Never commit server addresses, credentials, or database connection configuration.
- Implementers commit only their task changes; they never push, deploy, change branch or dispatch agents. Controller handles release.
- Use focused regressions while iterating, with final full checks before release. Existing test baseline: 48 frontend tests.

## Task 1: Money, percentage and business-date correctness

**Files:** `src/main/java/com/familyfinance/reporting/NetWorthService.java`, reporting tests, `frontend/src/shared/runtime.ts`, a focused decimal helper if needed, `frontend/src/features/loan/LoansPage.tsx`, its tests, and every frontend default date/year consumer currently using UTC slicing.

**Interfaces:** Keep the net-worth JSON shape. Add `businessDate(date: Date = new Date()): string`; make existing `localYearMonth(date)` use the same Asia/Shanghai day. Keep loan request `annualRate` numeric but serialize no more than six decimal places; reject invalid percentages before request.

- [ ] Add and run failing regressions in `NetWorthServiceTest` / `ConsolidatedReportingApiTest` proving 159135 stored cents yield `1591.35`, not `159135.00`. Include 1 cent and category/child-category agreement with the existing budget detail default.
- [ ] Add frontend regressions with these independent literals:

```ts
expect(businessDate(new Date('2026-09-08T07:30:00+08:00'))).toBe('2026-09-08');
expect(localYearMonth(new Date('2027-01-01T00:01:00+08:00'))).toBe('2027-01');
expect(loanCreatePayload({...draft, annualRate: '3.6'}).annualRate).toBe(0.036);
expect(loanCreatePayload({...draft, annualRate: '3.1'}).annualRate).toBe(0.031);
```

- [ ] Read aggregate cents as integer cents, preserving the separate yuan-to-cents conversion for portfolio money. Use the same category roll-up convention as budget detail.
- [ ] Implement decimal percentage parsing with explicit allowed input precision (up to four decimal places in percent, six in fractional rate). Avoid binary `Number(percent) / 100` artifacts; validate range 0–100 and show percent-based Chinese feedback in the loan wizard.
- [ ] Replace default dates in transaction, asset valuation, investment, recurring and loan forms; align yearly selection's default with the business year. No global OS-clock changes.
- [ ] Run focused Java tests with `./mvnw -q -Dskip.npm=true -Dskip.installnodenpm=true -Dtest=NetWorthServiceTest,ConsolidatedReportingApiTest test`, then frontend typecheck/test; self-review and commit.

## Task 2: Complete pagination for lists, selectors and loan schedules

**Files:** `frontend/src/api/client.ts`, `contracts.ts`, new `frontend/src/shared/pagination.tsx` (transport helpers may be a separate focused `.ts` file), ledger/asset/investment/budget/recurring/loan/family page consumers as needed, and loan schedule controller/service response metadata; corresponding tests. Notifications currently return their complete visible collection and need no artificial server paging.

**Interfaces:** Existing `Page<T>` is `{items,page,size,totalElements,totalPages,hasNext}`. Preserve existing API envelope bodies; expose array endpoint page metadata through an opt-in client path, e.g. `request<Page<T>>(path,{responseType:'page'})`. JSON callers remain unchanged. Common pager uses actual `hasNext`/total metadata, not an arbitrarily increased page size. Reference dropdowns can use a shared paginated read-all helper if they are not searchable.

- [ ] Add failing client tests for both array+headers and object-page envelopes, and for an exact full last page. Add loan UI/HTTP tests that make page 1 and the final installment reachable.

```ts
expect(firstPage.items).toHaveLength(50);
expect(firstPage.hasNext).toBe(true);
expect(lastPage.items.at(-1)?.installmentNo).toBe(360);
expect(lastPage.hasNext).toBe(false);
```

- [ ] Add backward-compatible schedule pagination metadata while retaining the 50-row server cap. Expose complete totals, not guessed values derived from original term after prepayment adds history.
- [ ] Page every existing visible list/history that was fixed to page 0 (including valuation history); reset/clamp pages after filters and removals and show an empty-page recovery path.
- [ ] Include budget usage and revision history. Update test doubles to reflect real paged transport contracts rather than adding production-only fallbacks for incomplete mocks. A read-all helper must reject inconsistent metadata or a non-advancing page, not loop indefinitely.
- [ ] Keep reference selections complete across pages, including editing an existing selection not on page 0; keep distinct query keys for different page/filter/projection shapes.
- [ ] Verify selectors, 50/51/360 boundaries, filter reset, non-manager read views and mobile pager usability. Do not add business filtering or archive recovery from later rounds.
- [ ] Run focused paging/frontend tests, affected Java schedule tests, typecheck, self-review and commit.

## Task 3: Consistent refresh and permission-aware actions

**Files:** `frontend/src/auth/AuthProvider.tsx`, focused shared query-refresh helper, `TransactionsPage.tsx`, `NotificationsPage.tsx`, `WorkspaceLayout.tsx`, tests; additive transaction capability/source response fields only if required by existing authorization rules.

**Interfaces:** Preserve `AuthContextValue.request<T>`. Successful business writes must await appropriate cache refresh and suppress stale reads; authentication transitions clear previous-user cache. Preserve all backend permission decisions. Determine transaction edit/delete permission from the actual backend rules, not only `MEMBER` versus manager labels.

- [ ] Add a regression with fresh cached net worth, a transaction write, and immediate navigation showing all new totals. Add a late-response case and a failed-write case (must not act as success).

```ts
expect(cache.getQueryData(['net-worth'])).toEqual(updatedWorth);
oldRead.resolve(previousWorth);
await Promise.resolve();
expect(cache.getQueryData(['net-worth'])).toEqual(updatedWorth);
```

- [ ] Centralize write-to-read invalidation instead of duplicating incomplete local lists. Cover transaction edits/deletes, budgets, accounts, assets, investments, loans, recurring and notifications; inactive previously viewed summaries must not remain falsely fresh.
- [ ] Gate member edits/deletes of others' entries and generated protected records in both desktop and mobile views, while allowing permitted own records. Hide/gate notification generation for members; keep ordinary read/resolve actions.
- [ ] Verify OWNER/ADMIN/MEMBER, creator/noncreator, manual/generated cases with focused behavior tests; do not loosen backend authorization.
- [ ] Run auth/client/permission tests and typecheck, self-review and commit.

## Task 4: Scoped errors and unsaved-input protection

**Files:** `frontend/src/features/common.tsx`, focused `frontend/src/shared/draft-guard.tsx` and form-error helper if useful, application router integration, all existing editing forms/drawers, auth/settings forms, and focused tests.

**Interfaces:** Shared guard registration must support nested drawers and in-memory draft lifecycles. A root React Router data-router adapter is permitted if required for reliable `useBlocker`; preserve all existing URLs and auth behavior. Do not implement fragile manual history rewinding. Passwords and financial drafts must not be persisted in localStorage/sessionStorage.

- [ ] Add failing tests for the reported close/reopen error leak and edited-draft closing by close button, backdrop, Escape and route transition; untouched drawers close without confirmation.

```ts
await user.type(screen.getByLabelText('金额'), '28.50');
await user.click(screen.getByRole('button', {name:'关闭'}));
expect(screen.getByRole('dialog', {name:'放弃未保存的修改？'})).toBeVisible();
await user.click(screen.getByRole('button', {name:'继续编辑'}));
expect(screen.getByLabelText('金额')).toHaveValue('28.50');
```

- [ ] Reset mutation errors when a new form session or record starts. Keep server field errors beside or linked to fields, focus/scroll the current error, and route wizard errors to their step. Do not erase user input on failed save.
- [ ] Track meaningful draft edits (text/select/date/radio/checkbox and nested selections). Protect UI close/route transitions, and use beforeunload for browser refresh/close where allowed. Keep mandatory logout/session-expiry cleanup safe; discard stale-user drafts and pending results.
- [ ] Ensure save success can close without a discard prompt; prevent pending or late saves from closing a different new form. Nested confirmation closes only the top dialog and restores focus correctly.
- [ ] Run form, auth, drawer and router regressions; typecheck/build; self-review and commit.

## Task 5: Integrated verification and release (controller)

**Files:** `docs/acceptance/round-1-reliability-checklist.md`; existing CI workflow remains unchanged unless a defect is evidenced.

- [ ] Run `python3 -m unittest discover -s scripts/tests -v`, frontend typecheck/test/build, and `./mvnw -B -Dskip.npm=true -Dskip.installnodenpm=true verify`.
- [ ] Start a same-version isolated backend with test dependencies and H2, not production credentials. Verify money/percentage/schedule paging, immediate cross-page refresh, both user roles, old errors, draft close and route protection on desktop/mobile.
- [ ] Review the complete round's diff. Fix release-blocking findings within this scope and record limitations honestly.
- [ ] Commit the verified acceptance record, push `codex/clarity-workspace`, follow GitHub Actions to completion, verify public deployment commit/asset and read-only UI. No production data mutations.
- [ ] Deliver eight-item outcome checklist with commit and Actions run link; never stop at local-only commit.
