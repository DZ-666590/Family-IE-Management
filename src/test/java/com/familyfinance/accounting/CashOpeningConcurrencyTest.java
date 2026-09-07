package com.familyfinance.accounting;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import com.familyfinance.auth.FamilyUserPrincipal;
import com.familyfinance.ledger.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
@ActiveProfiles("test")
class CashOpeningConcurrencyTest {
    @Autowired AccountService accounts;
    @Autowired AccountingCommandExecutor commands;
    @Autowired LedgerReadService ledger;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @MockitoSpyBean JdbcTemplate jdbc;

    @org.junit.jupiter.api.Test
    void replayFindsBusinessResultCommittedAfterCallersEarlierSnapshot() throws Exception {
        Family f=family();
        var request=new AccountCreateRequest("replay",AccountType.CASH,"CNY","10.00","2026-01-01");
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var pool=Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(ignored->{
                jdbc.queryForObject("select count(*) from financial_accounts where household_id=?",Long.class,f.household());
                AccountResponse committed;
                try {committed=pool.submit(()->accounts.create(f.authentication(),request,"same-request")).get(10,TimeUnit.SECONDS);}
                catch(Exception e){throw new RuntimeException(e);}
                assertThat(accounts.create(f.authentication(),request,"same-request").id()).isEqualTo(committed.id());
            });
            assertThat(jdbc.queryForObject("select count(*) from financial_accounts where household_id=?",Long.class,f.household())).isEqualTo(1);
        } finally {pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }

    @org.junit.jupiter.api.Test
    void metadataCommandReloadsOpeningFromCurrentStateAfterAnEarlierSnapshot() throws Exception {
        Family f=family();
        long id=accounts.create(f.authentication(),new AccountCreateRequest("opening",AccountType.CASH,"CNY","10.00","2026-01-01"),"initial").id();
        boolean mysql=Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) c->"MySQL".equals(c.getMetaData().getDatabaseProductName())));
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var pool=Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(ignored->{
                if(mysql) jdbc.queryForObject("select opening_balance_cents from financial_accounts where id=?",Long.class,id);
                try {pool.submit(()->accounts.update(f.authentication(),id,new AccountPatchRequest(null,null,null,"20.00",null),"correction")).get(10,TimeUnit.SECONDS);}
                catch(Exception e){throw new RuntimeException(e);}
                var updated=accounts.update(f.authentication(),id,new AccountPatchRequest("renamed",null,null,null,null),"rename");
                assertThat(updated.openingBalance()).isEqualTo("20.00");
                assertThat(updated.balance()).isEqualTo("20.00");
            });
            assertThat(jdbc.queryForObject("select opening_balance_cents from financial_accounts where id=?",Long.class,id)).isEqualTo(2000);
            assertThat(ledger.balance(f.household(),"CASH:"+id)).isEqualTo(2000);
        } finally {pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void brandNewHouseholdOpeningsExposeRawGapDeadlockAndRetryWholeCommand(boolean retry) throws Exception {
        Family a=family(),b=family();
        boolean mysql=Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) c->"MySQL".equals(c.getMetaData().getDatabaseProductName())));
        var barrier=new CyclicBarrier(2,()->{
            if(mysql) {
                try {System.out.println("Actual first-registration locks: "+jdbc.queryForList("select OBJECT_NAME,INDEX_NAME,LOCK_TYPE,LOCK_MODE,LOCK_DATA from performance_schema.data_locks where OBJECT_SCHEMA=database() and OBJECT_NAME='ledger_accounts'"));}
                catch(RuntimeException e) {System.out.println("Lock trace unavailable: "+e.getClass().getSimpleName());}
            }
        });
        Set<Long> firstAttempts=ConcurrentHashMap.newKeySet();
        doAnswer(call->{
            Object result=call.callRealMethod();
            String sql=call.getArgument(0);
            if(mysql&&sql.equals("select kind from ledger_accounts where household_id=? and account_code=? for update")&&firstAttempts.add(Thread.currentThread().getId()))
                barrier.await(10,TimeUnit.SECONDS);
            return result;
        }).when(jdbc).queryForList(anyString(),eq(String.class),any(Object[].class));
        var pool=Executors.newFixedThreadPool(2);
        try {
            var fa=pool.submit(()->open(a,retry));
            var fb=pool.submit(()->open(b,retry));
            Object ra=fa.get(30,TimeUnit.SECONDS),rb=fb.get(30,TimeUnit.SECONDS);
            if(mysql&&!retry) {
                var failed=List.of(ra,rb).stream().filter(v->v instanceof Throwable).toList();
                assertThat(failed).hasSize(1);
                var sql=rootSql((Throwable)failed.get(0));
                assertThat(sql.getErrorCode()).isEqualTo(1213);
                assertThat(sql.getSQLState()).isEqualTo("40001");
                System.out.println("Raw complete command rollback: "+rootSql((Throwable)failed.get(0)));
            } else {assertThat(ra).isInstanceOf(AccountResponse.class);assertThat(rb).isInstanceOf(AccountResponse.class);}
            verify(a,ra instanceof AccountResponse);
            verify(b,rb instanceof AccountResponse);
        } finally {pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
    Object open(Family family,boolean retry) {
        var request=new AccountCreateRequest("opening",AccountType.CASH,"CNY","100.00","2026-01-01");
        Supplier<AccountResponse> command=()->accounts.create(family.authentication(),request,"fresh-opening");
        try {return retry?commands.execute(command):command.get();} catch(RuntimeException e) {return e;}
    }
    void verify(Family family,boolean success) {
        long h=family.household();
        for(String table:new String[]{"financial_accounts","cash_opening_events","accounting_commands","ledger_journals"})
            assertThat(jdbc.queryForObject("select count(*) from "+table+" where household_id=?",Long.class,h)).as(table).isEqualTo(success?1:0);
        assertThat(ledger.balance(h,"EQUITY:OPENING")).isEqualTo(success?10000:0);
        assertThat(ledger.reconstructedBalances(h)).isEqualTo(ledger.balances(h));
    }
    java.sql.SQLException rootSql(Throwable e) {for(Throwable t=e;t!=null;t=t.getCause()) if(t instanceof java.sql.SQLException sql) return sql;throw new AssertionError(e);}
    Family family() {
        String name=UUID.randomUUID().toString();
        jdbc.update("insert into households(name,created_at) values (?,current_timestamp)",name);
        long h=jdbc.queryForObject("select id from households where name=?",Long.class,name);
        jdbc.update("insert into app_users(household_id,username,password_hash,email,display_name,created_at) values (?,?,?,?,?,current_timestamp)",h,name,"unused",name+"@test.invalid","test");
        long actor=jdbc.queryForObject("select id from app_users where username=?",Long.class,name);
        jdbc.update("insert into household_memberships(household_id,user_id,role,status,joined_at) values (?,?,'OWNER','ACTIVE',current_timestamp)",h,actor);
        var principal=new FamilyUserPrincipal(actor,name,"test","unused");
        return new Family(h,UsernamePasswordAuthenticationToken.authenticated(principal,"unused",principal.getAuthorities()));
    }
    record Family(long household,Authentication authentication) {}
}
