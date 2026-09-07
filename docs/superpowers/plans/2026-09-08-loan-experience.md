# Loan Experience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Execute sequentially; user explicitly waived intermediate design/implementation approvals. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Deliver the user's five ordered loan improvements on the existing unified ledger.

**Architecture:** Three sequential vertical slices: financed asset creation, loan totals/payoff/cash payment UX, then dual-strategy partial-prepayment simulation and commit. Domain services share the existing posting transaction and frontend consumes exact server contracts.

**Tech Stack:** Java17, Spring Boot, MySQL8/H2, React/TypeScript/Semi.

**Spec:** `docs/superpowers/specs/2026-09-08-loan-experience-design.md`

## Global Constraints

- Existing worktree `.worktrees/family-finance-stage-2`, branch `codex/family-finance-stage-2`; no new branch/main changes.
- Preserve staged `.idea/.gitignore` and five untracked IDE files. Every commit uses explicit owned paths and `git commit --only -- <paths>`; no add-A or whole-index commits.
- No subagents from workers, SSH, push, deploy, production writes or clearing data. Root handles release; previous deployment remains blocked on SSH.
- V1–V18 not modified; append H2/MySQL migrations. No destructive reset or guessed legacy financial backfill.
- Integer cents, household-first current mutation reads, confirmed/date-valid specific cash account, atomic domain+journal changes, immutable sources, stable full-request idempotency and whole-transaction retry must remain.
- Normal display/preview reads are readonly consistent snapshots. On submit reauthorize/reload/current-read and verify the plan token before economic changes; replay receipts checked before later-state guards.
- Preserve permissions, Clarity style, draft/error/focus/page protections and cross-module refresh. No real bank or brokerage operations, no claimed bank payoff quote.
- Focused RED/GREEN while iterating; complete relevant suites before commit; root performs final combined/MySQL/browser acceptance. Reports stay ignored in this plan's SDD directory.

### Task 1: Automatically create the financed purchase asset (user item1)

**Files:** loan/LoanCreateRequest.java, LoanFundingMode.java, Loan.java, LoanService.java, LoanAccountingService.java, LoanResponse.java; asset/Asset.java, AssetResponse.java, AssetService.java, AssetAccountingMode.java; new asset/LoanPurchasedAssetService.java; accounting/LedgerReportingService.java; frontend/api/contracts.ts, features/loan/LoansPage.tsx, features/asset/AssetsPage.tsx, accounting history source options; next unused H2/MySQL migration (V19 expected); loan/LoanPurchasedAssetApiTest.java and corresponding frontend behavior test.

**Interfaces:** createPurchasedAsset=true + fundingMode=FINANCED_PURCHASE with no linkedAssetId/disbursementAccountId. Response exposes stable purchasedAssetId and asset acquisitionSourceType/acquisitionSourceId/detailsPending. Loan source LOAN_FINANCED_PURCHASE, original asset source points to that same journal, not another asset posting.

- [x] RED real HTTP tests for mortgage/car/other automatic names, principal-equal values, no fake metadata, same-household link, unchanged cash, borrowed principal/asset reporting and one journal; failed validation/post rollback leaves no orphan asset/loan; replay returns same pair. Frontend test exact POST payload and selected-option explanation.
```java
// Given confirmed cash0 and principal100000 cents financed purchase:
assertThat(asset.getPurchaseValueCents()).isEqualTo(100000L);
assertThat(asset.getCurrentValueCents()).isEqualTo(100000L);
assertThat(ledger.balance(h,"CASH:"+cashId)).isZero();
assertThat(ledger.balance(h,"ASSET:"+asset.getId())).isEqualTo(100000L);
assertThat(ledger.balance(h,"LOAN:"+loanId)).isEqualTo(100000L);
```
- [x] Implement internal paired origination. Save validated loan draft with existing all-null initialization tuple, create generated asset referencing its ID, attach stable automatic link and initialize loan, then one post before commit. No second asset-value posting.
```java
var entries=List.of(new LedgerEntryInput("ASSET:"+assetId,ASSET,p,0,null,null),
                   new LedgerEntryInput("LOAN:"+loanId,LOAN,0,p,null,null));
posting.post(new LedgerPostingCommand(h,"LOAN_FINANCED_PURCHASE",loanId,key,day,actor,entries));
```
- [x] Generate type+numeric names under household lock with collision protection. Omit unknown subtype rows; expose pending metadata and allow valid later completion without rebooking. Normal manual-create validators remain strict. Reject reassigning auto purchase or changing coupled principal/origination fields separately; rate/term correction remains safe before payments.
- [x] Add FINANCED_PURCHASE to safe schema shapes, source lookup/loan initialization, relevant reporting borrowed-versus-cash distinction, audit links and frontend selector. Plain cash/OPENING paths stay compatible.
- [x] Full backend/frontend checks and build; scoped commit. Report exact new fields/source behavior and touched read contracts for Task2.

### Task 2: Loan totals, one-step payoff and explicit cash account (user items2–4)

**Files:** loan/LoanResponse.java, LoanService.java, LoanController.java, LoanInstallmentConfirmationService.java, LoanPaymentRequest.java, LoanPrepaymentService.java, LoanPrepayment.java, LoanPrepaymentResponse.java; new loan/LoanTotalsService.java, LoanPayoffService.java, LoanPayoffRequest.java, LoanPayoffQuote.java and shared plan-token helper; transaction/FinancialTransaction.java; frontend contracts/LoansPage/new payoff panel as appropriate; next additive migration if needed; loan/LoanPayoffApiTest.java and frontend LoanPayoff tests.

**Interfaces:** LoanResponse adds scheduledRepaymentTotal/remainingRepaymentTotal/paidRepaymentTotal. GET `/api/loans/{id}/payoff-quote` accepts paidOn/paymentAccountId/interestAmount; POST `/api/loans/{id}/payoff` body has paidOn,paymentAccountId,interestAmount,planToken,idempotencyKey. All quote fields as spec; source stays LOAN_PREPAYMENT with operation kind PAYOFF and stored principal/interest metadata. LoanPaymentRequest gains optional paymentAccountId, preserving old callers.

- [x] RED totals against more than one UI page and cancelled/rebuilt history, payoff due-interest split, insufficient/exact funds and wrong selected account, same-key replay/mismatch, stale quote, unauthorized/foreign account, actual-date guards. Frontend exercises actual DTO fields and totals side by side.
```java
// Pending principal2000, due interest100, future interest50:
assertThat(quote.cashAmount()).isEqualTo("2100.00");
assertThat(quote.futureScheduledInterest()).isEqualTo("50.00");
// 2099.99 cash -> no loan/principal/schedule/transaction/journal change.
// 2100 cash -> cash0, loan0, expense100, prior PAID rows retained.
```
- [x] Implement readonly whole-loan totals (all paid installment cash + all prepayment cash + pending), excluding CANCELLED. Mutable responses must read current history explicitly, not lazy cached collection snapshots. No extra original-total field with ambiguous semantics.
- [x] Build a canonical planToken from current loan/principal and ordered active schedule identity/amounts/dates; quote readonly, submit verifies locked current state. Settlement interest defaults to due unpaid interest and may be explicitly increased to actual interest. Never charge future planned interest or unconfirmed extra amounts.
```java
long principal=loan.getCurrentPrincipalCents();
long interest=validatedActualInterestOrDueInterest;
long cashAmount=Math.addExact(principal,interest);
// Existing LoanAccountingService.pay posts LOAN+EXPENSE against selected CASH.
```
- [x] Persist one payoff/prepayment event and transaction with accurate principal+interest split; apply full principal, cancel pending, retain past paid history and link payoff result. Legacy prepayment cash metadata stays compatible. Full old prepay must not waive due interest or silently charge more than request.
- [x] Add optional actual payment-account selection to regular/prepay/payoff, without changing future default. Keep current roles and exact cash guarding. Add one-click payoff entry with quote/confirmation, plan-change/funding-error recovery and stable key. Wire totals/history/invalidation and API tests.
- [x] Full backend/frontend/typecheck/build; scoped commit; report exact summary/quote/token/prepayment interfaces for Task3.

### Task 3: Dual-strategy partial prepayment and final loan experience (user item5)

**Files:** new loan/PrepaymentStrategy.java, LoanPrepaymentPlanner.java, LoanPrepaymentPreview.java; loan/LoanPrepaymentRequest.java, LoanPrepaymentService.java, LoanController.java, LoanPrepaymentResponse.java, LoanResponse.java; frontend contracts/LoansPage and focused strategy comparison component; tests loan/LoanPrepaymentPlannerTest.java, LoanPrepaymentStrategyApiTest.java and frontend strategy tests; append migration only if persistence needed.

**Interfaces:** strategies REDUCE_TERM and REDUCE_PAYMENT; GET `/api/loans/{id}/prepayment-preview?amount=&paidOn=&strategy=&paymentAccountId=` produces before/after schedule summaries and planToken. Existing POST/prepay gains strategy, optional paymentAccountId and planToken, preserving compatibility default REDUCE_PAYMENT for old clients. New UI always sends selected strategy+token. Quote contains after schedule (bounded360), exact principal/cash, next payment, period counts, maturity and total planned interest.

- [x] RED pure planner cases for both standard modes, zero rate, tail cents, custom irregular dates, tiny residual infeasibility; real API cases for cash shortfall rollback, stale preview/replay, no other-account subsidy, current-read concurrency, paid-history preservation and totals after successive strategies. Test overdue guard and full amount routing fromTask2.
```java
// Original zero-rate120000 cents over12 future months, partial30000:
assertThat(reduceTerm.size()).isEqualTo(9); // each10000, unchanged originaldates
assertThat(reducePayment.size()).isEqualTo(12); // each7500, samefinaldate
assertThat(reduceTerm.stream().mapToLong(InstallmentDraft::principalCents).sum()).isEqualTo(90000);
```
- [x] Implement pure plan calculation over ordered future drafts, rejecting partial prepayment while dueOn<=paidOn remains PENDING. REDUCE_TERM keeps each original installment total cap, recalculates interest on new balance and clips last payment; REDUCE_PAYMENT standard modes call existing calculator then preserve exact original dates. Tiny residual belowperiodcount returns explicit fixed-term infeasibility.
```java
// Per preserved original period (normal loans use annualRate/12):
long interest=roundedInterest(newRemaining,periodRate);
long principalPaid=Math.min(newRemaining,Math.subtractExact(oldPeriodTotal,interest));
// CUSTOM periodRate = originalInterest/originalBeginningPrincipal;
// fixed-term CUSTOM principal uses original principal weights and final cent remainder.
```
- [x] Preview and execution share planner; submit checks token/current cash and loan balance in one transaction before cancelling original pending rows and appending increasing installment numbers. Keep original contract fields, expose lateststrategy/remainingterm/maturity/nextcash amount. Never create zero/negative principal rows or extend shortened plan beyond originalmaturity.
- [x] UI offers both requested choices with before/after amounts, periods, dates and planned-interest changes; clearly explain varying original payments/custom proportional model. Refresh preview when amount/date/account/strategy changes; ignore late stale preview responses; disable submit until matching preview. Preserve failed input/key.
- [x] Full gates, scoped commit and exact report; root performs independent final review, actual MySQL and real browser acceptance, updates `docs/acceptance/loan-experience-checklist.md`. No server changes unless previous SSH/deployment prerequisite is genuinely restored and explicitly in scope.
