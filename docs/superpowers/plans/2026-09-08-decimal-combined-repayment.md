# Decimal Ledger and Combined Repayment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development sequentially. User approved the architecture and requested execution; no intermediate approval menus. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Decimal-native ledger and loan calculations, feasible repayment periods, and one-confirmation settlement of due installments plus additional principal.

**Architecture:** Four sequential independently reviewed slices: decimal accounting core with exact compatibility, precision-aware loan planning, atomic combined repayment backend, then complete UI integration. Keep original source journals and statement semantics.

**Tech Stack:** Java17/SpringBoot4.1.1, MySQL8/H2/Flyway, React/TypeScript/Semi.

**Spec:** `docs/superpowers/specs/2026-09-08-decimal-combined-repayment-design.md`

## Global Constraints

- Existing linked Stage2 worktree and `codex/family-finance-stage-2`; no new branch or main checkout changes.
- Preserve staged `.idea/.gitignore` and five untracked IDE files. Every commit uses `git commit --only` with explicit owned paths; no whole-index commits.
- No worker subagents, SSH, push, deployment, production writes or resetting data. Root owns disposable database/browser/final gates.
- V1–V21 immutable; append both H2/MySQL migrations fromV22. Preserve all historical financial meaning and request digests.
- Formal CNY entries and balances are BigDecimal scale2, with exact equality, no implicit sub-cent rounding, no negative cash/loan principal, household-first current locking and full-transaction retry. Read previews use repeatable snapshots.
- High precision is calculation/rounding metadata, not hidden spendable cash. No invented bank rules, no silent method/term changes or removal of existing permissions.
- Keep old source IDs, zero-opening/ownership/date checks and complete request replay. New monetary wire fields are decimal strings; never use floating point for money.
- Focused RED/GREEN per change, relevant complete checks per stage, independent review after each. Reports in this plan's ignored SDD directory. Root conducts final combined/MySQL/browser acceptance.

### Task 1: BigDecimal accounting core and exact migration

**Files:** new shared/DecimalMoney.java; accounting/LedgerEntryInput, LedgerBalances, LedgerStore, LedgerValidation, LedgerPostingService, LedgerReadService, LedgerReportingService, LedgerActivity, LedgerRequestDigest; new V22__decimal_ledger.sql in both dialects; new accounting/DecimalLedgerTest.java and migration/DecimalLedgerMigrationTest.java; existing raw-SQL accounting tests and latest-version expectations when necessary.

**Interfaces:** DecimalMoney.settled(BigDecimal)→scale2 exact-or-validation; fromCents(long)→BigDecimal; toCents(BigDecimal)→exactlong; format(BigDecimal)→two-decimal string. LedgerEntryInput(accountCode,kind,BigDecimal debitAmount,BigDecimal creditAmount,categoryId,memberId), retain old long-cent constructor/debitCents/creditCents compatibility. LedgerReadService.balanceAmount(h,code), balancesAmount(h), reconstructedBalanceAmounts(h) primary decimal methods; old long methods are explicit boundary adapters. Store and balance reconstruction use BigDecimal internally, not long arithmetic behind a decimal facade.

- [ ] RED real posting and migration fixtures: 100.01 opening minus0.02→99.99; sub-cent0.001 rejects atomically; equivalent1.2300/1.23 behaves consistently; old digest replay and source reversal retained.
```java
assertThat(read.balanceAmount(h,"CASH:"+accountId)).isEqualByComparingTo("99.99");
// Seed V21 journals including reversals and receipts; after migrate, amounts*c100 exactly equal old cents.
```
- [ ] Implement decimal values/SQL storage. Add balance_amount/debit_amount/credit_amount DECIMAL(21,2), convert old cents exactly. Old cents names may become generated read-only aliases; preserve original signed-long-cent bounds, checks and immutable source IDs. No second writable copy.
```java
BigDecimal amount = BigDecimal.valueOf(oldCents, 2);
BigDecimal normalized = supplied.setScale(2, RoundingMode.UNNECESSARY);
// Debit/credit/rollup comparisons use compareTo; legacy digest still emits exact original writeLong cents.
```
- [ ] Update core current/snapshot/rebuild/reverse/report calculations and original-digest encoding. Keep consumer cents methods/DTOs documented as adapters; update test-only direct writes to primary decimal columns afterV22, not earlier migration fixtures.
- [ ] H2 and real MySQL migration/posting/current-read checks, full backend, frontend regression/typecheck/build if wire touched, explicit owned commit and task-1-report exact API/schema/compatibility instructions for Task2.

### Task 2: Precision-aware loan schedules and feasible term options

**Files:** loan/AmortizationCalculator, InstallmentDraft, LoanPrepaymentPlanner, Loan/LoanInstallment/LoanPrepayment, LoanAccountingService, LoanService, LoanPlanToken, LoanTotalsService, LoanPrepaymentService/Preview/Response; transaction/FinancialTransaction loan split zero-principal boundary; new loan/PreciseLoanScheduleCalculator.java, PreciseInstallmentDraft.java, LoanRoundingContext.java, LoanTermOptions.java, LoanRepaymentPolicy.java, LoanRepaymentPolicyService.java, LoanRepaymentPolicyRequest.java, LoanRepaymentPolicyController.java; nextV23 precision/policy migration and tests loan/LoanPrecisionTest.java, LoanTermOptionsApiTest.java.

**Interfaces:** LoanRoundingContext(BigDecimal preciseInterestPaid,BigDecimal actualInterestPaid); precise calculator returns actual scale2 drafts plus precise principal/interest/carry metadata and policy identifier. LoanTermOptions enumerates feasible counts from supplied remaining dates and policy, with rejection reasons; no assumed continuous interval. Per-loan GET/PATCH `/api/loans/{id}/repayment-policy` supplies optional minimumInstallmentAmount plus sourceNote/revision, no old create DTO extension. Legacy draft/cents getters remain boundary adapters. Expose exact final contracts in report forTask3; newstrategy ADJUST_TERM needs explicit targetPeriods.

PreciseInstallmentDraft carries installmentNo,dueOn,principalAmount,interestAmount,remainingPrincipal,precisePrincipalAmount,preciseInterestAmount,interestCarryAmount,roundingPolicy; cashAmount() derives actual principal+interest. PreciseLoanScheduleCalculator.calculate(principal,annualRate,dates,method,roundingContext) returns List<PreciseInstallmentDraft>. LoanTermOptions.evaluate(principal,annualRate,dates,method,roundingContext,minimumInstallmentAmount) returns per-count allowed/reason/firstPaymentAmount. If integrating these requires a materially different signature, agree it with the controller and record exact final contract before Task3 starts. InstallmentDraft may carry nullable precision metadata while keeping its original five-argument constructor; no metadata invented for old rows.

- [ ] RED literal fractional examples, including zero-rate0.01 over2 periods rejected as zero-cash schedule, and remaining3.60/.12/360 successfully amortized with positive cash and nonuniform principal. Normal fixed payments retained where feasible; original dates and terminal principal exact; high precision metadata persists/replays.
```java
// Literal below/at-cent cases; no production helper computes expected values.
assertThat(schedule.stream().map(PreciseInstallmentDraft::principalAmount).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("3.60");
assertThat(schedule).hasSize(360).allSatisfy(x->assertThat(x.cashAmount()).isPositive());
```
- [ ] Implement DECIMAL128 theoretical calculation and explicit cumulative cent allocation. Prefer fixed regular cash with valid tail; otherwise floor cumulative theoretical principal to cents and allocate final residue at maturity. Interest allocation rounds total precise paid+planned interest then subtracts actual previously allocated interest. Actual cash=sum actual principal+interest. Persist theoretical amounts/rounding differences (DECIMAL30,12); no guessed backfill on old records. Allow zero principal only with positive interest/cash, omit zero principal ledger leg, preserve total loan principal and closure order.
```java
BigDecimal allocatedPrincipal = terminal ? principal.subtract(previousPrincipal) : preciseCumulativePrincipal.setScale(2,FLOOR).subtract(previousPrincipal);
BigDecimal allocatedInterest = preciseInterestPaid.add(preciseCumulativeInterest).setScale(2,HALF_UP).subtract(actualInterestPaid).subtract(previousInterest);
```
- [ ] Convert loan monetary entity fields to primary BigDecimal amount columns with exact legacy cents aliases, adapt scheduled confirmation/ledger split constraints to interest-only positive cash, and keep old source records unchanged. Add nullable precision metadata, rule revision and policy history; new quote token binds these.
- [ ] Implement optional contract minimum and candidate term evaluation1..remaining-count. Keep fixed original count when feasible, REDUCE_TERM retains old caps, ADJUST_TERM only explicit smaller count/dateprefix. Rule changes invalidate previews, never alter paid/old pending plans or block recording actual old repayments. No fake10yuan bank threshold.
- [ ] Complete focused H2/MySQL and backend/frontend compatibility checks, commit owned paths, report exact precision/money/carry/term APIs and wire changes.

### Task 3: Atomic due-plus-additional-principal backend

**Files:** new loan/LoanRepaymentService.java, LoanRepaymentPreview.java, LoanRepaymentRequest.java, LoanRepaymentResponse.java, LoanRepaymentBatch.java/repository, LoanInstallmentSettlement.java; LoanController, LoanInstallmentConfirmationService, LoanPrepaymentService internal helpers, notification/reference and history DTOs as needed; next migration for immutable repayment batches/links; tests loan/LoanCombinedRepaymentApiTest.java.

**Interfaces:** GET `/api/loans/{id}/repayment-preview?additionalPrincipal=&paidOn=&paymentAccountId=&strategy=&targetPeriods=`; POST `/api/loans/{id}/repayment` body same fields+planToken/idempotencyKey. Preview/response fields dueInstallments/duePrincipalAmount/dueInterestAmount/additionalPrincipal/totalPrincipalAmount/totalInterestAmount/totalCashAmount/paymentAccountId/availableBalance/balanceAfter/before/after/termOptions/token; exact report locks names forTask4. Immutable batch relates the distinct due installment transactions and extra prepayment transaction; individual ledger sources remain unchanged.

- [ ] RED sampleP10000/dueP1000I100/extra3000/cash5000→cash900/P6000/expense100. 4099.99cash rejects all. Inject failure after first real due journal and verify no paid rows/events/journals/receipts/notifications persist. Same-key replay returns identical batch and no extra debit; stale amounts/date/account/precision/rules reject.
```java
assertThat(result.totalCashAmount()).isEqualTo("4100.00");
assertThat(cashBalance).isEqualByComparingTo("900.00");
assertThat(loanPrincipal).isEqualByComparingTo("6000.00");
```
- [ ] Quote splits current PENDING rows bydueOn<=paidOn, sums due components, validates extra<=principalAfterDue, calculates future plan/carry/options with Task2. Do not cancel due rows. Extra equalpostdueprincipal closes the remainingloan in the same confirmed request; no newzero rows.
- [ ] Execute under one current household/root/account transaction. Check admin and existing assignee permissions before money, verify total cash and token, call internal MANDATORY LoanInstallmentSettlement for each due row, then internal extra-principal posting. Never nest public authorization helpers that clear dirtyEntityManager. Preserve normal single-confirmation service via the same helper.
```java
// One outer transaction and one full-request receipt:
for (LoanInstallment due : orderedDue) settlement.settleAuthorized(lockedAccess, loan, due, account, paidOn, childKey);
// Extra payment, future replan, batch links and receipt commit together; any exception rolls back all.
```
- [ ] Bind all request/snapshot fields and derive <=100-character child keys deterministically; replay before current closed/plan checks. Preserve legacy `/prepay` digest/API and never charge old callers due amounts they did not quote. Group history displays aggregate once without changing paid-cash totals (childtransactions countonce). Add current-read races, foreign/archived account, insufficient/date and role gates.
- [ ] Full relevant suites, real MySQL verification, explicit owned commit/report with exact UI contract and restrictions that remain genuinely required by authorization.

### Task 4: One-confirmation UI, policy and term selection

**Files:** frontend/src/api/contracts.ts; features/loan/LoanPrepaymentPanel.tsx, LoanStrategyComparison.tsx, LoansPage.tsx, new LoanRepaymentPolicyPanel.tsx as needed; shared/write-refresh.ts; theme/clarity.scss scopedonly; tests LoanCombinedRepayment.test.tsx and LoanPrecisionUi.test.tsx. Root owns docs/acceptance/decimal-combined-repayment-checklist.md and final integration.

**Interfaces:** Consume Task3 final report; new UI exclusively submits combined quote contract, legacy endpoint retained for old clients. Decimal strings and server amounts remain authoritative, noNumber arithmetic onmoney.

- [ ] RED rendered flow: extra3000 plusdue1100 visiblytotal4100; button确认还款¥4100.00; selectedaccountbalance5000→900; onePOST only, success currentplan refresh. Insufficient retainsdraft; amount/date/account/strategy/rule changes requirematchingnewpreview.
- [ ] Integrate due-list disclosure, explicit extra principal label, totalprincipal/interest/cash and balanceAfter, original/currentplan separation. Explain selected-date due handling before submit. Contract-policy panel clearlydistinguishes user-enteredcontractrules from simulatorfeasibility; displayvalidtermchoices and ADJUST_TERM label, not silentchanges toREDUCE_PAYMENT.
- [ ] Show highprecision/rounding policy explanation when relevant; all actualdisplaycomponents sumexactlyat2decimals; no hidden fraction permits cashpayment or blocksclosure. Emptyoptions actionablepayoff route; extraequalpostdueprincipal showsclosureconfirmationwithincombinedflow.
- [ ] Freeze attempted full body/key/token and quote for uncertain-response recovery; re-quote errors cannotdisableoriginalreceipt recovery. Keep permissions, draft/focus protections, current/history pagination and360px stack. Add exactbody/regression/late-response/receipt-replay tests, typecheck/build/fullfrontend.
- [ ] Commit ownedUIpaths/report; root performs fullverify, fresh MySQL migration and crossmodule integration, actualbrowserdue+extra/exactcash/failure/tinyterms/policy/mobile, finalwholefeature review, acceptance docs. Do not push/deploy or clean productiondata.
