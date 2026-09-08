package com.familyfinance.accounting;

import static org.assertj.core.api.Assertions.*;
import static com.familyfinance.accounting.LedgerAccountKind.*;

import com.familyfinance.shared.RequestValidationException;
import com.familyfinance.shared.DecimalMoney;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("test")
@SpringBootTest
class DecimalLedgerTest {
    @Autowired LedgerPostingService posting;
    @Autowired LedgerReadService read;
    @Autowired LedgerReportingService reporting;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean LedgerStore store;
    long h, actor, cash, category;
    final LocalDate day=LocalDate.of(2026,1,1);

    @BeforeEach void fixture() {
        String name=UUID.randomUUID().toString();
        jdbc.update("insert into households(name,created_at) values (?,current_timestamp)",name);
        h=jdbc.queryForObject("select id from households where name=?",Long.class,name);
        jdbc.update("insert into app_users(household_id,username,password_hash,email,display_name,created_at) values (?,?,?,?,?,current_timestamp)",h,name,"unused",name+"@test.invalid","test");
        actor=jdbc.queryForObject("select id from app_users where username=?",Long.class,name);
        jdbc.update("insert into financial_accounts(household_id,name,type,currency,opening_confirmed) values (?,'cash','CASH','CNY',true)",h);
        cash=jdbc.queryForObject("select id from financial_accounts where household_id=?",Long.class,h);
        jdbc.update("insert into categories(household_id,kind,name,color,is_default,created_at) values (?,'EXPENSE','expense','#000',false,current_timestamp)",h);
        category=jdbc.queryForObject("select id from categories where household_id=?",Long.class,h);
    }

    @Test void decimalPostingReconstructsAndReversesExactSettledAmounts() throws Exception {
        posting.post(opening("100.01","opening"));
        var payment=posting.post(expense("0.02","payment"));
        assertThat(balance()).isEqualByComparingTo("99.99");
        assertThat(jdbc.queryForObject("select balance_amount from ledger_accounts where household_id=? and account_code=?",BigDecimal.class,h,"CASH:"+cash)).isEqualByComparingTo("99.99");
        assertThat(read.reconstructedBalances(h)).containsEntry("CASH:"+cash,9999L);
        assertThat(read.reconstructedBalanceAmounts(h)).isEqualTo(read.balancesAmount(h));
        assertThat(reporting.balanceAmountsAsOf(h,day).get("CASH:"+cash)).isEqualByComparingTo("99.99");
        assertThat(reporting.activities(h,day,day.plusDays(1)).get(0).amount()).isEqualByComparingTo("0.02");
        assertThat(reporting.sumBudgetExpenseAmount(h,day,day.plusDays(1),"TOTAL",null,null,false)).isEqualByComparingTo("0.02");
        assertThat(reporting.sumBudgetExpenseCents(h,day,day.plusDays(1),"TOTAL",null,null,false)).isEqualTo("2");
        assertThat(reporting.cashFlowAmounts(h,day,day.plusDays(1)).cashOut()).isEqualByComparingTo("0.02");
        jdbc.update("update ledger_accounts set balance_amount=12.34 where household_id=? and account_code=?",h,"CASH:"+cash);
        read.rebuildBalances(h);
        assertThat(balance()).isEqualByComparingTo("99.99");
        var reversal=posting.reverse(h,"MANUAL",1,"undo",actor);
        assertThat(reversal.reversesJournalId()).isEqualTo(payment.journalId());
        assertThat(balance()).isEqualByComparingTo("100.01");
        assertThat(posting.reverse(h,"MANUAL",1,"undo",actor)).isEqualTo(reversal);
    }

    @Test void rejectsSubcentWithoutPersistingCallerOrLedgerWrites() throws Exception {
        posting.post(opening("100.01","opening"));
        assertThatThrownBy(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
            jdbc.update("update financial_accounts set name='changed' where id=?",cash);
            posting.post(expense("0.001","invalid"));
        })).isInstanceOf(RequestValidationException.class);
        assertThat(balance()).isEqualByComparingTo("100.01");
        assertThat(jdbc.queryForObject("select name from financial_accounts where id=?",String.class,cash)).isEqualTo("cash");
        assertThat(jdbc.queryForObject("select count(*) from ledger_journals where household_id=?",Long.class,h)).isEqualTo(1);
    }

    @Test void equivalentDecimalsReplayLegacyCentDigest() throws Exception {
        var legacy=new LedgerPostingCommand(h,"OPENING",cash,"same",day,actor,List.of(
            new LedgerEntryInput("CASH:"+cash,CASH,123,0,null,null),
            new LedgerEntryInput("EQUITY:OPENING",EQUITY,0,123,null,null)));
        var receipt=posting.post(legacy);
        assertThat(posting.post(opening("1.2300","same"))).isEqualTo(receipt);
        assertThat(posting.post(opening("1.23","same"))).isEqualTo(receipt);
        assertThat(read.entries(h,receipt.journalId())).isEqualTo(legacy.entries());
    }

    @Test void digestEncodingMatchesPersistedV21PostReplaceAndReverseReceipts() {
        var command=new LedgerPostingCommand(1,"OPENING",1,"ignored",day,1,List.of(
            leg("CASH:1",CASH,"1.2300","0",null),leg("EQUITY:OPENING",EQUITY,"0","1.23",null)));
        assertThat(LedgerRequestDigest.of("POST",command)).isEqualTo("ff20194cba95a0875de541b8718b21637dbc80f781ce8e4d7321ae16d107863b");
        assertThat(LedgerRequestDigest.of("REPLACE",command)).isEqualTo("075f8983c38b762ff4b7527be2b65b07e531070fe453806eb03accc3736b9852");
        assertThat(LedgerRequestDigest.of("REVERSE",new LedgerPostingCommand(1,"OPENING",1,"ignored",null,1,List.of())))
            .isEqualTo("2e0c3b340f5daa5355aa5fa4681ee55b473227dfa9204217aeb3abdf638009af");
    }

    @Test void settledMoneyNormalizesAndPreservesExactSignedLongBounds() {
        assertThat(DecimalMoney.settled(new BigDecimal("1.2300"))).isEqualTo(new BigDecimal("1.23"));
        assertThat(DecimalMoney.format(new BigDecimal("1"))).isEqualTo("1.00");
        assertThat(DecimalMoney.toCents(new BigDecimal("92233720368547758.07"))).isEqualTo(Long.MAX_VALUE);
        assertThat(DecimalMoney.toCents(new BigDecimal("-92233720368547758.08"))).isEqualTo(Long.MIN_VALUE);
        assertThat(DecimalMoney.fromCents(Long.MIN_VALUE)).isEqualTo(new BigDecimal("-92233720368547758.08"));
        for(String value:List.of("0.001","1.231","92233720368547758.08","-92233720368547758.09"))
            assertThatThrownBy(()->DecimalMoney.settled(new BigDecimal(value))).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(()->DecimalMoney.settled(null)).isInstanceOf(RequestValidationException.class);
    }

    @Test void reconstructionKeepsOneSnapshotAcrossConcurrentReplacement() throws Exception {
        posting.post(opening("100.01","opening"));
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        var once=new java.util.concurrent.atomic.AtomicBoolean();
        // Schedule a real committed replacement between the two real account movement queries.
        org.mockito.Mockito.doAnswer(invocation->{
            Object result=invocation.callRealMethod();
            if(once.compareAndSet(false,true)) executor.submit(()->posting.replace(opening("100.02","replacement")))
                .get(10,java.util.concurrent.TimeUnit.SECONDS);
            return result;
        }).when(store).movements(h,"CASH:"+cash,false);
        try {
            var snapshot=read.reconstructedBalanceAmounts(h);
            assertThat(snapshot.get("CASH:"+cash)).isEqualByComparingTo("100.01");
            assertThat(snapshot.get("EQUITY:OPENING")).isEqualByComparingTo("100.01");
            assertThat(read.balanceAmount(h,"CASH:"+cash)).isEqualByComparingTo("100.02");
        } finally { executor.shutdownNow(); }
    }

    private BigDecimal balance() { return read.balanceAmount(h,"CASH:"+cash); }
    private LedgerEntryInput leg(String code,LedgerAccountKind kind,String debit,String credit,Long categoryId) {
        return new LedgerEntryInput(code,kind,new BigDecimal(debit),new BigDecimal(credit),categoryId,null);
    }
    private LedgerPostingCommand opening(String amount,String key) {
        return new LedgerPostingCommand(h,"OPENING",cash,key,day,actor,List.of(
            leg("CASH:"+cash,CASH,amount,"0",null),leg("EQUITY:OPENING",EQUITY,"0",amount,null)));
    }
    private LedgerPostingCommand expense(String amount,String key) {
        return new LedgerPostingCommand(h,"MANUAL",1,key,day,actor,List.of(
            leg("EXPENSE:"+category,EXPENSE,amount,"0",category),leg("CASH:"+cash,CASH,"0",amount,null)));
    }
}
