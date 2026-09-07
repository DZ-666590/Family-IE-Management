# Unified Accounting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the approved unified household accounting model and reset business data safely while preserving identity/family data.

**Architecture:** Retain the modular monolith. A shared posting service owns immutable balanced journals, strict cash balances and source revisions; domain modules own their business records and call it in the same transaction. Read models distinguish cash movement from income/expense and valuations.

**Tech Stack:** Java 17, Spring Boot, MySQL 8, H2 isolated tests, React, TypeScript, existing Semi Design.

**Spec:** `docs/superpowers/specs/2026-09-07-unified-accounting-design.md`

## Global Constraints

- Stay on `codex/family-finance-stage-2` in its existing worktree. No new branch, no main changes.
- Preserve the user's staged/untracked `.idea` files. Every commit MUST use explicit owned paths with `git commit --only -- <paths>`; never commit the entire index or use add -A.
- No production financial test writes, secrets, backup contents, server addresses or database credentials in Git. Controller alone handles server/backup/reset/push/deploy.
- Workers do not push, deploy, switch branches or spawn agents. Reports remain in this plan's ignored SDD workspace, never force-add.
- CNY integer cents, nonnegative available cash, balanced atomic postings, immutable journal entries, idempotency and explicit business sources.
- Preserve existing UI style, roles, page metadata, date helper, draft protection and query freshness. No bank integration or implicit overdraft.
- Add migrations for both MySQL and H2; do not edit applied V1–V13 migrations. Never auto-delete old financial data on startup/migration.
- Run focused tests while iterating, full relevant suite before each task commit. Existing frontend baseline:131 tests passed. Java startup platform scripts have known separate issues; do not bundle unrelated repairs.

### Task 1: Unified posting kernel

**Files:** create `src/main/java/com/familyfinance/accounting/{LedgerAccountKind,LedgerEntryInput,LedgerPostingCommand,LedgerReceipt,LedgerPostingService,LedgerReadService}.java` and focused repository/internal helpers; create MySQL/H2 `V14__unified_accounting.sql`; create `src/test/java/com/familyfinance/accounting/LedgerPostingServiceTest.java`.

**Interfaces:** Implement the exact public records/methods in the spec. Receipt must expose original business source and journal ID for adapters. Add read/query methods needed to inspect source revision and idempotency without allowing clients to submit raw entries. Use JDBC current/locking reads where JPA snapshot identity would be unsafe.

- [ ] Write domain/DB regressions for balanced posting, insufficient funds, changed payload under same key, reverse/replace and replay. Example independently derived acceptance:
```java
// Given opening cash10000 cents, two concurrent outgoing8000 postings:
assertThat(successCount).isEqualTo(1);
assertThat(read.balance(householdId, "CASH:" + accountId)).isEqualTo(2000L);
// A failing posting leaves journal/entry/loan business counts unchanged.
```
- [ ] Run focused tests and record RED before implementing. Register household-owned accounts and enforce code/kind consistency, positive single-sided entry amounts, exact balanced totals, dates no later than Shanghai today, overflow bounds and request/source uniqueness.
- [ ] Implement post/replace/reverse with immutable original and reversal entries, source-current mapping, same-transaction balance projection and final-state checks. Replacement must not fail solely on a transient reverse leg when final funds are unchanged, but cannot create a negative balance at a historical day boundary.
```java
LedgerReceipt result = posting.post(new LedgerPostingCommand(h, "MANUAL", sourceId,
    key, day, actor, List.of(new LedgerEntryInput("EXPENSE:"+categoryId, EXPENSE, 1000,0,categoryId,memberId),
    new LedgerEntryInput("CASH:"+accountId,CASH,0,1000,null,memberId))));
```
- [ ] Ensure failures are actionable API-domain errors, not leaking SQL. No unsupported legacy backfill. Test projection rebuild equals effective entries and foreign-household account references are rejected.
- [ ] Run focused tests/type compilation, self-review, commit explicit owned paths. Report exact API additions and table contracts for Task2.

### Task 2: Accounts, transfers, income/expense and recurring cash integration

**Files:** `ledger/AccountService.java`, `AccountResponse.java`, `AccountCreateRequest.java`, `AccountPatchRequest.java`, `FinancialAccount.java`, `DefaultFinancialAccountFactory.java`; `transaction/TransactionService.java` and request/response types; `ledger/recurring/RecurringConfirmationService.java`; new `accounting/CashTransferController.java`, `CashTransferService.java` and request/response; `config/DemoDataInitializer.java`; corresponding migrations beyondV14 and tests.

**Interfaces:** Consume Task1 posting/read APIs. Add POST/GET `/api/transfers`, with fromAccountId,toAccountId,amount,occurredOn,idempotencyKey. Account responses add `balance`, `availableBalance`, opening confirmation/date semantics without removing existing fields. Register CASH and EQUITY opening counterpart; all real money writes must use it.

- [ ] Add failing tests for zero cash expense, exact balance, transfer, delete-income/reverse protection, scope isolation, repeated requests, future dates and generated-row immutable financial fields.
```java
// cashA=0,cashB=10000; outgoingA1000 must fail; transferBtoA1000 then outgoingA1000 succeeds.
assertThat(read.balance(h, "CASH:"+a)).isZero();
assertThat(read.balance(h, "CASH:"+b)).isEqualTo(9000L);
```
- [ ] Integrate account opening/confirmation, cash current balance and audited opening corrections. Automatic default0 accounts must not pretend a user-confirmed opening snapshot; explicit API opening0 is valid. Reject nonzero account archive; preserve identity and metadata operations.
- [ ] Integrate manual writes with post/replace/reverse, stable source IDs and meaningful idempotency; never directly edit financial fields of generated entries. Recurring insufficient funds leaves pending and creates no transaction.
- [ ] Add transfers as linked journal-backed operations, not two unrelated income/expense rows. Refuse same-account transfer and cross-family references. Plain metadata edits remain available.
- [ ] Update demo seed/test fixtures to explicitly establish valid opening funds and matching postings. Do not globally disable accounting enforcement to keep legacy tests green. No production auto-backfill or invented income.
- [ ] Run affected Java suites, self-review, commit owned paths and report API contracts/examples to later tasks.

### Task 3: Loans and debt/payment accounting

**Files:** `loan/LoanService.java`, `LoanCreateRequest.java`, `LoanPatchRequest.java`, `LoanInstallmentConfirmationService.java`, `LoanPrepaymentService.java`, `Loan.java`; new focused loan-accounting adapter; transaction response/source integration; loan/accounting API tests.

**Interfaces:** Use Task1 ledger, Task2 cash ownership/opening rules. Loan creation adds explicit `fundingMode` (OPENING or DISBURSEMENT) and optional disbursementAccountId required for DISBURSEMENT. Existing stored loans are not guessed/replayed at startup. Stable business IDs link loan and payment journals.

- [ ] RED tests: cash0 payment110000 cents fails atomically; cash110000 pays principal100000+interest10000, cash0, loan decreases100000, expense10000. Test prepay, repeat key changed amount/date rejection, read-only role, date and full-close boundaries.
- [ ] Implement opening loan liability against EQUITY; actual disbursement against selected CASH. Same borrower name/loan metadata does not create money. Contract changes with posted repayments/prepayments cannot silently reset principal/history.
- [ ] Post scheduled payment LOAN principal + EXPENSE interest / CASH total, prepayment LOAN / CASH. Preserve scheduling calculation strategy and paid/cancelled history; all checks/postings/business changes share a transaction.
- [ ] Implement safe source-level correction policy: at minimum block independent generated-money edits and unsafe loan archive/contract edits; expose meaningful errors. If reversal of a loan payment is offered, recalculate all affected allocations rather than only its cash row.
- [ ] Update loan fixtures to fund payment accounts and establish journals; run loan/accounting tests, self-review and scoped commit.

### Task 4: Asset/investment money sources and consistent reporting

**Files:** `asset/AssetService.java` and request DTOs; `investment/InvestmentAccountService.java`, `InvestmentTradeService.java`, DTOs; `reporting/{NetWorthService,PortfolioService,DashboardService,AnalysisService,NetWorthSnapshotService}.java`; `budget/BudgetUsageService.java`, `transaction/LedgerReadAdapter.java`; focused accounting adapters, migrations and integration tests.

**Interfaces:** Consume posting kernel/cash rules; extend investment accounts with a household-scoped funding cash account and expose it. Asset additions distinguish OPENING from PURCHASE with funding account for purchase. Opened positions and actual buys must not be conflated. Extend unified read facade for income, expense, cash flows, liabilities and valuation bridge.

- [ ] RED acceptance: purchase at value500000 decreases CASH500000 and increases asset/position500000, no new wealth; sell/fees/dividends produce linked cash and realized results, insufficient cash and overselling roll back. Opening positions/assets create opening equity, not current income.
- [ ] Wire buy/sell/dividend/fee to the same ledger. Match quantities/cost replay with journal amounts and cash; edited historical trades require auditable replacement/reversal and validated affected positions. Account transfers fund investment cash explicitly.
- [ ] Wire asset opening/purchase and valuations/real disposal. Never make archive alone erase value. Valuation changes do not change cash and cannot be counted twice in net worth.
- [ ] Migrate reporting reads to unified cash/expense/loan source data. Expense budget excludes principal; cash-flow/debt views show principal separately. Internal transfer does not affect income/expense/networth; snapshots use consistent as-of semantics.
```java
// For payment1100: cashOut=1100,expense=100,principalPaid=1000; no duplicate networth decrement.
assertThat(summary.expense()).isEqualTo("100.00");
```
- [ ] Update financial fixtures and affected reporting/plugin tests; explicitly document any truly unsupported old business operation and gate its write (never leave independent old money writes enabled). Self-review and scoped commit.

### Task 5: User-facing flows, balance visibility and final contracts

**Files:** frontend API contracts and account/ledger/loan/asset/investment/budget/dashboard pages, shared request refresh and new transfer/account-opening components; frontend tests and necessary styles only.

**Interfaces:** Use exact Task2–4 implemented endpoints/response shapes from their reports. Keep current routes and visual system; expose cash accounts, balances, funding source, opening-vs-new transaction semantics, transfer history and audit links.

- [ ] Add failing behavioral tests for insufficient balance feedback, required funding account, zero opening confirmation, transfer balances, accurate principal/interest totals and blocked generated financial edits.
- [ ] Show current/available balance and expected remaining amount in outgoing operations; backend remains authoritative. Generate stable per-form idempotency key and preserve it on failed retry, reset on newform. Retain existing dirty/error/permissions/paging behavior.
- [ ] Distinguish cash outflow, expense, borrowing and principal on overview, budget and annual views. Present metadata corrections versus financial reversal clearly. New/initial asset, loan and investment forms explicitly identify their meaning.
- [ ] If a not-yet-supported money command remains, show clear read-only availability and ensure backend rejects it; do not present a working write button that bypasses the ledger.
- [ ] Run full frontend tests/typecheck/build, self-review and scoped commit.

### Task 6: Integration, database reset evidence and release (controller)

**Files:** `docs/acceptance/unified-accounting-checklist.md`; no public credentials or backup material.

- [ ] Verify backup/reset evidence: database backup restored in isolated schema, all table counts matched, protected identity/reference hashes identical after transactional cleanup, application healthy. User business reset is already completed; do not repeat after user data entry without fresh scope validation.
- [ ] Run full Java/frontend checks. Verify zero/exact/insufficient funding, two concurrent80 against100, replay/mismatchedkey, reversals, transfers, loan principal/interest, investment/asset sources and summaries using isolated databases, including actual MySQL current-read semantics.
- [ ] Independent whole-round review, address blockers. Preserve existing stagedIDE files; commit acceptance evidence with explicit paths only.
- [ ] Stage2 push only after all money writers and readers are consistently connected/gated. Watch CI/CD, verify publiccommit/resources/health. No hidden runtime destructive migrations or testfinance writes into preserveduserfamilies.
- [ ] Report completed scope, any gated operation, exact backup location/recovery boundary, tests, deployedcommit and any residual platformstartup issues honestly.
