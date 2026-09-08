package com.familyfinance.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.familyfinance.accounting.LedgerAccountKind.*;

import com.familyfinance.shared.ResourceConflictException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("test")
@SpringBootTest
class LedgerPostingServiceTest {
    @Autowired LedgerPostingService posting;
    @Autowired LedgerReadService read;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    long h, actor, cash, category;
    LocalDate day = LocalDate.of(2026, 1, 1);

    @BeforeEach
    void fixture() {
        String name = UUID.randomUUID().toString();
        jdbc.update("insert into households(name,created_at) values (?, current_timestamp)", name);
        h = jdbc.queryForObject("select id from households where name=?", Long.class, name);
        jdbc.update("insert into app_users(household_id,username,password_hash,email,display_name,created_at) values (?,?,?,?,?,current_timestamp)", h, name, "unused", name+"@test.invalid", "test");
        actor = jdbc.queryForObject("select id from app_users where username=?", Long.class, name);
        jdbc.update("insert into financial_accounts(household_id,name,type,currency) values (?,'cash','CASH','CNY')", h);
        cash = jdbc.queryForObject("select id from financial_accounts where household_id=?", Long.class, h);
        jdbc.update("insert into categories(household_id,kind,name,color,is_default,created_at) values (?,'EXPENSE','expense','#000',false,current_timestamp)", h);
        category = jdbc.queryForObject("select id from categories where household_id=?", Long.class, h);
    }

    @Test
    void balancedPostingChangesCashAndEquityExactlyOnce() {
        var command = opening(10000, "opening");
        var receipt = posting.post(command);
        assertThat(read.balance(h, "CASH:"+cash)).isEqualTo(10000);
        assertThat(read.balance(h, "EQUITY:OPENING")).isEqualTo(10000);
        assertThat(posting.post(command)).isEqualTo(receipt);
    }

    LedgerPostingCommand opening(long amount, String key) {
        return command("OPENING", cash, key, day, List.of(
                new LedgerEntryInput("CASH:"+cash, CASH, amount, 0, null, null),
                new LedgerEntryInput("EQUITY:OPENING", EQUITY, 0, amount, null, null)));
    }

    @Test
    void rejectsInsufficientFundsWithoutAnyJournalOrBusinessWrite() {
        posting.post(opening(1000, "opening"));
        assertThatThrownBy(() -> posting.post(expense(1, 1100, "payment", day)))
                .isInstanceOf(ResourceConflictException.class);
        assertThat(read.balance(h, "CASH:"+cash)).isEqualTo(1000);
        assertThat(count("ledger_journals")).isEqualTo(1);
        assertThat(count("ledger_entries")).isEqualTo(2);
        assertThat(count("loan_prepayments")).isZero();
    }

    @Test
    void rejectsChangedPayloadAndDuplicateSource() {
        posting.post(opening(10000, "opening"));
        assertThatThrownBy(() -> posting.post(opening(9000, "opening"))).isInstanceOf(ResourceConflictException.class);
        assertThatThrownBy(() -> posting.post(opening(10000, "other"))).isInstanceOf(ResourceConflictException.class);
        assertThat(count("ledger_journals")).isEqualTo(1);
    }

    @Test
    void validatesBalanceAmountKindDateAndHouseholdOwnership() {
        assertThatThrownBy(() -> posting.post(command("MANUAL", 1, "unbalanced", day, List.of(
                new LedgerEntryInput("CASH:"+cash,CASH,1,0,null,null)))))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> posting.post(opening(0,"zero"))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> posting.post(opening(-1,"negative"))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> posting.post(command("OPENING", cash,"future",LocalDate.of(9999,1,1),opening(1,"x").entries())))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> posting.post(command("OPENING", cash,"kind",day,List.of(
                new LedgerEntryInput("CASH:"+cash,LOAN,1,0,null,null), new LedgerEntryInput("EQUITY:OPENING",EQUITY,0,1,null,null)))))
                .isInstanceOf(RuntimeException.class);
        long ownHousehold=h;
        long ownCash=cash;
        fixture();
        assertThatThrownBy(() -> posting.post(command("OPENING", ownCash,"foreign",day,List.of(
                new LedgerEntryInput("CASH:"+ownCash,CASH,1,0,null,null), new LedgerEntryInput("EQUITY:OPENING",EQUITY,0,1,null,null)))))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("select count(*) from ledger_journals where household_id in (?,?)",Long.class,h,ownHousehold)).isZero();
    }

    @Test
    void concurrentPaymentsCannotSpendTheSameFunds() throws Exception {
        posting.post(opening(10000,"opening"));
        ExecutorService executor=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            var a=executor.submit(() -> payAfter(start,1));
            var b=executor.submit(() -> payAfter(start,2));
            start.countDown();
            assertThat(a.get(10,TimeUnit.SECONDS)+b.get(10,TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(read.balance(h,"CASH:"+cash)).isEqualTo(2000);
        } finally { executor.shutdownNow(); }
    }

    int payAfter(CountDownLatch start,long id) throws InterruptedException {
        start.await();
        try { posting.post(expense(id,8000,"payment-"+id,day)); return 1; }
        catch (ResourceConflictException expected) { return 0; }
    }

    long count(String table) {
        return jdbc.queryForObject("select count(*) from "+table+" where household_id=?",Long.class,h);
    }

    @Test
    void replacementChecksCombinedResultAndPreservesImmutableHistory() {
        var original=posting.post(opening(10000,"opening"));
        posting.post(expense(1,10000,"spend",day));
        var replacement=opening(10000,"correct-opening");
        var replaced=posting.replace(replacement);
        assertThat(replaced).isNotNull();
        assertThat(replaced.revision()).isEqualTo(2);
        assertThat(read.balance(h,"CASH:"+cash)).isZero();
        assertThat(read.entries(h,original.journalId())).isEqualTo(opening(10000,"x").entries());
        assertThat(read.currentSource(h,"OPENING",cash)).contains(replaced);
        assertThat(read.requestReceipt(h,"opening")).contains(original);
        assertThat(posting.post(opening(10000,"opening"))).isEqualTo(original);
        assertThat(posting.replace(replacement)).isEqualTo(replaced);
        assertThat(count("ledger_journals")).isEqualTo(4);
    }

    @Test
    void reverseIsAppendOnlyIdempotentAndRemovesCurrentSource() {
        posting.post(opening(10000,"opening"));
        var original=posting.post(expense(1,3000,"spend",day));
        var reverse=posting.reverse(h,"MANUAL",1,"undo",actor);
        assertThat(read.balance(h,"CASH:"+cash)).isEqualTo(10000);
        assertThat(reverse.reversesJournalId()).isEqualTo(original.journalId());
        assertThat(reverse.sourceType()).isEqualTo("MANUAL");
        assertThat(reverse.sourceId()).isEqualTo(1);
        assertThat(posting.reverse(h,"MANUAL",1,"undo",actor)).isEqualTo(reverse);
        assertThat(read.currentSource(h,"MANUAL",1)).isEmpty();
        assertThatThrownBy(()->posting.reverse(h,"MANUAL",1,"another-undo",actor)).isInstanceOf(ResourceConflictException.class);
        assertThat(count("ledger_journals")).isEqualTo(3);
    }

    @Test
    void cannotUseLaterMoneyToHideHistoricalDeficitOnReverseOrReplace() {
        posting.post(opening(10000,"opening"));
        posting.post(expense(1,8000,"spend",day.plusDays(1)));
        posting.post(command("OTHER_OPENING",cash,"later",day.plusDays(2),opening(10000,"x").entries()));
        assertThatThrownBy(()->posting.reverse(h,"OPENING",cash,"undo",actor)).isInstanceOf(ResourceConflictException.class);
        assertThatThrownBy(()->posting.replace(opening(1000,"reduce"))).isInstanceOf(ResourceConflictException.class);
        assertThatThrownBy(()->posting.replace(command("OPENING",cash,"move-date",day.plusDays(2),opening(10000,"x").entries())))
                .isInstanceOf(ResourceConflictException.class);
        assertThat(read.balance(h,"CASH:"+cash)).isEqualTo(12000);
        assertThat(count("ledger_journals")).isEqualTo(3);
    }

    @Test
    void projectionDiscrepanciesAreExplicitAndRepairRebuildsFromImmutableEntries() {
        posting.post(opening(10000,"opening"));
        posting.post(expense(1,2500,"spend",day));
        assertThat(read.balances(h)).containsEntry("CASH:"+cash,7500L);
        assertThat(read.reconstructedBalances(h)).isEqualTo(read.balances(h));
        jdbc.update("update ledger_accounts set balance_amount=90.00 where household_id=? and account_code=?",h,"CASH:"+cash);
        assertThat(read.reconstructedBalances(h)).containsEntry("CASH:"+cash,7500L);
        assertThatThrownBy(()->posting.post(expense(2,1,"unsafe",day))).isInstanceOf(ResourceConflictException.class);
        read.rebuildBalances(h);
        assertThat(read.balance(h,"CASH:"+cash)).isEqualTo(7500);
        assertThat(count("ledger_journals")).isEqualTo(2);
    }

    @Test
    void sharesCallerTransactionAndRollsBackBusinessWrites() {
        posting.post(opening(10000,"opening"));
        var transaction=new TransactionTemplate(transactionManager);
        assertThatThrownBy(()->transaction.executeWithoutResult(tx->{
            jdbc.update("update financial_accounts set name='changed' where id=?",cash);
            posting.post(expense(1,3000,"spend",day));
            assertThat(read.balance(h,"CASH:"+cash)).isEqualTo(7000);
            throw new IllegalStateException("adapter business failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(read.balance(h,"CASH:"+cash)).isEqualTo(10000);
        assertThat(jdbc.queryForObject("select name from financial_accounts where id=?",String.class,cash)).isEqualTo("cash");
        assertThat(count("ledger_journals")).isEqualTo(1);
    }

    @Test
    void earlyRepeatableReadSnapshotCannotAuthorizeStaleCash() throws Exception {
        posting.post(opening(10000,"opening"));
        CountDownLatch snapshotReady=new CountDownLatch(1), paid=new CountDownLatch(1);
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            var result=executor.submit(()->{
                var transaction=new TransactionTemplate(transactionManager);
                transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
                return transaction.execute(tx->{
                    // A pre-authorization plain read creates MySQL's transaction-wide RR snapshot.
                    jdbc.queryForObject("select count(*) from financial_transactions where household_id=?",Long.class,h);
                    snapshotReady.countDown();
                    try { if(!paid.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("payment timeout"); }
                    catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                    assertThatThrownBy(()->posting.post(expense(2,8000,"stale-payment",day))).isInstanceOf(ResourceConflictException.class);
                    tx.setRollbackOnly();
                    return true;
                });
            });
            assertThat(snapshotReady.await(10,TimeUnit.SECONDS)).isTrue();
            posting.post(expense(1,8000,"winner",day));
            paid.countDown();
            assertThat(result.get(10,TimeUnit.SECONDS)).isTrue();
            assertThat(read.balance(h,"CASH:"+cash)).isEqualTo(2000);
        } finally { paid.countDown(); executor.shutdownNow(); }
    }

    @Test
    void loanRepaymentUsesPrincipalPlusInterestAndCannotOverpayPrincipal() {
        jdbc.update("insert into loans(household_id,name,loan_type,payment_account_id,payment_category_id,principal_cents,annual_rate,term_months,repayment_method,start_on,current_principal_cents,status,created_by) values (?,'loan','OTHER',?,?,10000,0,12,'EQUAL_PRINCIPAL',?,10000,'ACTIVE',?)",h,cash,category,day,actor);
        long loan=jdbc.queryForObject("select id from loans where household_id=?",Long.class,h);
        posting.post(command("LOAN_OPENING",loan,"loan-open",day,List.of(
                new LedgerEntryInput("LOAN:"+loan,LOAN,0,10000,null,null),
                new LedgerEntryInput("EQUITY:OPENING",EQUITY,10000,0,null,null))));
        var pay=command("LOAN_PAYMENT",1,"pay",day,List.of(
                new LedgerEntryInput("LOAN:"+loan,LOAN,1000,0,null,null),
                new LedgerEntryInput("EXPENSE:"+category,EXPENSE,100,0,category,null),
                new LedgerEntryInput("CASH:"+cash,CASH,0,1100,null,null)));
        assertThatThrownBy(()->posting.post(pay)).isInstanceOf(ResourceConflictException.class);
        assertThat(count("ledger_journals")).isEqualTo(1);
        posting.post(opening(1100,"opening"));
        posting.post(pay);
        assertThat(read.balance(h,"CASH:"+cash)).isZero();
        assertThat(read.balance(h,"LOAN:"+loan)).isEqualTo(9000);
        assertThat(read.balance(h,"EXPENSE:"+category)).isEqualTo(100);
        assertThatThrownBy(()->posting.post(command("LOAN_PAYMENT",2,"overpay",day,List.of(
                new LedgerEntryInput("LOAN:"+loan,LOAN,9001,0,null,null),
                new LedgerEntryInput("EQUITY:OPENING",EQUITY,0,9001,null,null)))))
                .isInstanceOf(ResourceConflictException.class);
        assertThat(read.balance(h,"LOAN:"+loan)).isEqualTo(9000);
    }

    @Test
    void rejectsAmountOverflowAndDoubleSidedEntries() {
        assertThatThrownBy(()->posting.post(command("OPENING",cash,"both",day,List.of(
                new LedgerEntryInput("CASH:"+cash,CASH,2,1,null,null),
                new LedgerEntryInput("EQUITY:OPENING",EQUITY,0,1,null,null)))))
                .isInstanceOf(com.familyfinance.shared.RequestValidationException.class);
        assertThatThrownBy(()->posting.post(command("OPENING",cash,"total-overflow",day,List.of(
                new LedgerEntryInput("CASH:"+cash,CASH,Long.MAX_VALUE,0,null,null),
                new LedgerEntryInput("CASH:"+cash,CASH,1,0,null,null),
                new LedgerEntryInput("EQUITY:OPENING",EQUITY,0,Long.MAX_VALUE,null,null),
                new LedgerEntryInput("EQUITY:OPENING",EQUITY,0,1,null,null)))))
                .isInstanceOf(com.familyfinance.shared.RequestValidationException.class);
        posting.post(opening(Long.MAX_VALUE,"max"));
        assertThatThrownBy(()->posting.post(command("OTHER_OPENING",cash,"balance-overflow",day,opening(1,"x").entries())))
                .isInstanceOf(ResourceConflictException.class);
        assertThat(read.balance(h,"CASH:"+cash)).isEqualTo(Long.MAX_VALUE);
        assertThat(count("ledger_journals")).isEqualTo(1);
    }

    @Test
    void malformedLargeAccountCodeReturnsDomainValidationError() {
        assertThatThrownBy(()->posting.post(command("OPENING",cash,"bad-code",day,List.of(
                new LedgerEntryInput("CASH:"+"1".repeat(70000),CASH,1,0,null,null),
                new LedgerEntryInput("EQUITY:OPENING",EQUITY,0,1,null,null)))))
                .isInstanceOf(com.familyfinance.shared.RequestValidationException.class);
    }

    @Test
    void displayReadsWorkInReadOnlyTransactionsAndPreserveArchivedBalances() {
        var receipt=posting.post(opening(10000,"opening"));
        var transaction=new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        transaction.executeWithoutResult(tx->{
            assertThat(read.balance(h,"CASH:"+cash)).isEqualTo(10000);
            assertThat(read.balances(h)).containsEntry("CASH:"+cash,10000L);
            assertThat(read.entries(h,receipt.journalId())).hasSize(2);
            assertThat(read.reconstructedBalances(h)).isEqualTo(read.balances(h));
        });
        jdbc.update("update households set status='ARCHIVED',archived_at=current_timestamp where id=?",h);
        assertThat(read.balance(h,"CASH:"+cash)).isEqualTo(10000);
        assertThatThrownBy(()->posting.post(expense(1,1,"archived-payment",day))).isInstanceOf(ResourceConflictException.class);
    }

    @Test
    void nonCanonicalAccountAliasesCannotCreateAnotherBalanceForTheSameCash() {
        assertThatThrownBy(()->posting.post(command("OPENING",cash,"alias",day,List.of(
                new LedgerEntryInput("CASH:"+cash+":",CASH,10000,0,null,null),
                new LedgerEntryInput("EQUITY:OPENING",EQUITY,0,10000,null,null)))))
                .isInstanceOf(com.familyfinance.shared.RequestValidationException.class);
    }

    @Test
    void commandCapturesEntriesBeforeCallerMutatesTheList() {
        var entries=new java.util.ArrayList<>(opening(10000,"original").entries());
        var command=command("OPENING",cash,"captured",day,entries);
        entries.clear();
        entries.addAll(opening(9000,"changed").entries());
        posting.post(command);
        assertThat(read.balance(h,"CASH:"+cash)).isEqualTo(10000);
    }

    LedgerPostingCommand expense(long source, long amount, String key, LocalDate date) {
        return command("MANUAL", source, key, date, List.of(
                new LedgerEntryInput("EXPENSE:"+category, EXPENSE, amount, 0, category, null),
                new LedgerEntryInput("CASH:"+cash, CASH, 0, amount, null, null)));
    }

    LedgerPostingCommand command(String source, long id, String key, LocalDate date, List<LedgerEntryInput> entries) {
        return new LedgerPostingCommand(h, source, id, key, date, actor, entries);
    }
}
