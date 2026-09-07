# Task 2 report: complete pagination for lists, selectors and loan schedules

## Outcome

- The API client now has an opt-in `responseType: 'page'` path. It preserves existing JSON callers, accepts both structured `Page<T>` envelopes and array envelopes with the five `X-Page*` headers, and honors `X-Has-Next` for an exact full final page.
- Shared pagination provides metadata-driven controls, empty-page recovery/clamping, and `readAllPages` for non-searchable reference selectors. The read-all helper rejects a mismatched/non-advancing page, contradictory `hasNext`/`totalPages`, oversized content, and final totals that do not equal the rows read.
- Visible lists now page with server metadata: transactions, accounts, category roots, assets, asset valuation history, investment trades, investment accounts, budget usage, budget revisions, recurring occurrences, recurring rules, active loans, loan schedules, family memberships, and family invites.
- Non-searchable selectors read all pages with distinct query keys: ledger/loan/recurring financial accounts, flat categories, active assets, active loans, memberships, investment accounts, and recurring-rule references. `/api/members` remains unchanged because it is an unbounded complete endpoint. Security search remains a searchable page-0 query; editing sets the exact stored `tsCode` as its query so a selection outside the initial result remains present.
- Loan schedules retain the 50-row server cap and array response body. Additive headers expose page, effective capped size, actual installment-row total, total pages and `hasNext`. Totals come from the persisted installment collection, including paid/cancelled history retained after prepayment, rather than `termMonths`.
- Family invites now expose the missing `totalElements` and `totalPages` fields in their already-structured page object.
- Mobile pagination buttons receive a 44px minimum height and spread across the available width.

## TDD evidence

### RED

`cd frontend && npm test -- --run src/api/client.test.ts src/shared/pagination.test.tsx src/features/loan/LoanFamilyPermissions.test.tsx`

- Array+header page tests received a bare array.
- The pagination helper module did not exist.
- The loan schedule UI received a page object but still attempted `schedule.data.map`, and no next-page control existed.
- Result: 3 expected test failures plus the missing-module suite error.

The first Spring run also confirmed the new 360-row fixture needed a sufficiently large principal: `1000.00` at 12% over 360 months generated zero-principal rows and correctly violated `CK_LOAN_INSTALLMENTS_PRINCIPAL`. The fixture was corrected to `1000000.00`; no production amortization or loan strategy was changed.

### GREEN and regression verification

- `cd frontend && npm test -- --run`: exit 0; 15 files, 56 tests passed.
- `cd frontend && npm run typecheck`: exit 0.
- `./mvnw -q -Dskip.npm=true -Dskip.installnodenpm=true -Dtest=LoanApiTest,LoanPrepaymentTest test`: exit 0; 3 tests passed across the two classes.
- The Java API test reaches page 1 and installment 360, asserts the 50-row cap, `totalElements=360`, `totalPages=8`, and final `hasNext=false`.
- The prepayment test compares `X-Total-Elements` to the actual persisted installment-row count after a partial prepayment.
- Frontend boundary coverage includes exact 50 (full last page), 51 (second transaction/category page), and 360 (final loan installment).
- `git diff --check`: exit 0.

## Self-review

- Existing envelope bodies remain backward compatible; page metadata parsing is opt-in.
- Query keys distinguish page/filter/projection/all-option shapes.
- Filter changes reset transaction, asset and budget page state; mutation invalidation plus `usePageRecovery` clamps a page that disappears after removal or filtering.
- Read-only member rendering continues to show the paged asset, investment and loan views; mutation visibility rules were not changed.
- Notifications were not paged because the endpoint already returns the complete visible collection.
- The dashboard's deliberate five-item recent snapshot remains `page=0&size=5`, and security search remains a query-scoped first page; neither is an accidentally truncated management list.
- No migration, funding integration, loan strategy, central cache policy, permissions, dirty/error lifecycle, credential, server-address, push, deployment, or branch-switch change was made.

## Remaining verification boundary

Whole-suite backend/integration, browser viewport QA, and release/deployment verification remain the controller's responsibility. No known implementation blocker remains.
