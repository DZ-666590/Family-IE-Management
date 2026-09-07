package com.familyfinance.accounting;

import static com.familyfinance.accounting.LedgerAccountKind.ASSET;
import static com.familyfinance.accounting.LedgerAccountKind.EQUITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("test")
@SpringBootTest
class LedgerSecurityConcurrencyTest {
    @Autowired LedgerPostingService posting;
    @Autowired LedgerReadService read;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void independentHouseholdsCanPostOppositeSecurityOrders(boolean separateCommands) throws Exception {
        Family first=family(), second=family();
        long x=security(), y=security();
        initialize(first,x,y);
        initialize(second,x,y);
        boolean mysql=Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection ->
                "MySQL".equals(connection.getMetaData().getDatabaseProductName())));
        var bothFirstReads=new CyclicBarrier(2);
        Set<Long> synchronizedThreads=ConcurrentHashMap.newKeySet();
        // Instrument only scheduling AFTER real SQL acquires the first catalogue row.
        // Assertions below inspect committed financial state, not calls to a mock.
        doAnswer(invocation -> {
            Object result=invocation.callRealMethod();
            String sql=invocation.getArgument(0);
            if(mysql && sql.startsWith("select id from securities where id=?")
                    && synchronizedThreads.add(Thread.currentThread().getId())) {
                bothFirstReads.await(10,TimeUnit.SECONDS);
            }
            return result;
        }).when(jdbc).queryForList(anyString(),eq(Long.class),any(Object[].class));

        var executor=Executors.newFixedThreadPool(2);
        var start=new CyclicBarrier(2);
        try {
            var a=executor.submit(()-> { start.await(10,TimeUnit.SECONDS); postPositions(first,x,y,separateCommands); return true; });
            var b=executor.submit(()-> { start.await(10,TimeUnit.SECONDS); postPositions(second,y,x,separateCommands); return true; });
            assertThat(a.get(20,TimeUnit.SECONDS)).isTrue();
            assertThat(b.get(20,TimeUnit.SECONDS)).isTrue();
            assertThat(read.balance(first.household(),"EQUITY:OPENING")).isEqualTo(300);
            assertThat(read.balance(second.household(),"EQUITY:OPENING")).isEqualTo(300);
            assertThat(read.balance(first.household(),"POSITION:"+first.account()+":"+x)).isEqualTo(100);
            assertThat(read.balance(first.household(),"POSITION:"+first.account()+":"+y)).isEqualTo(200);
            assertThat(read.balance(second.household(),"POSITION:"+second.account()+":"+y)).isEqualTo(100);
            assertThat(read.balance(second.household(),"POSITION:"+second.account()+":"+x)).isEqualTo(200);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10,TimeUnit.SECONDS)).isTrue();
        }
    }

    private void postPositions(Family family,long first,long second,boolean separateCommands) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            if(separateCommands) {
                posting.post(command(family,2,List.of(position(family,first,100),equity(100))));
                posting.post(command(family,3,List.of(position(family,second,200),equity(200))));
            } else {
                posting.post(command(family,2,List.of(position(family,first,100),position(family,second,200),equity(300))));
            }
        });
    }

    private void initialize(Family family,long x,long y) {
        // Establish account and request/source index ranges so this regression isolates
        // catalogue locks from first-ever-key gap locking in an otherwise empty ledger.
        for(long id:new long[]{1,4}) {
            posting.post(command(family,id,List.of(position(family,x,1),position(family,y,1),equity(2))));
            posting.reverse(family.household(),"POSITION_OPENING",id,"catalogue-"+id+"-undo",family.actor());
        }
    }

    private LedgerPostingCommand command(Family family,long source,List<LedgerEntryInput> entries) {
        return new LedgerPostingCommand(family.household(),"POSITION_OPENING",source,"catalogue-"+source,
                LocalDate.of(2026,1,1),family.actor(),entries);
    }

    private LedgerEntryInput position(Family family,long security,long amount) {
        return new LedgerEntryInput("POSITION:"+family.account()+":"+security,ASSET,amount,0,null,null);
    }

    private LedgerEntryInput equity(long amount) {
        return new LedgerEntryInput("EQUITY:OPENING",EQUITY,0,amount,null,null);
    }

    private record Family(long household,long actor,long account) {}

    private Family family() {
        String name=UUID.randomUUID().toString();
        jdbc.update("insert into households(name,created_at) values (?,current_timestamp)",name);
        long household=jdbc.queryForObject("select id from households where name=?",Long.class,name);
        jdbc.update("insert into app_users(household_id,username,password_hash,email,display_name,created_at) values (?,?,?,?,?,current_timestamp)",household,name,"unused",name+"@test.invalid","test");
        long actor=jdbc.queryForObject("select id from app_users where username=?",Long.class,name);
        jdbc.update("insert into investment_accounts(household_id,name,broker_name,currency,created_by) values (?,'investment','test','CNY',?)",household,actor);
        return new Family(household,actor,jdbc.queryForObject("select id from investment_accounts where household_id=?",Long.class,household));
    }

    private long security() {
        String code;
        do { code=ThreadLocalRandom.current().nextInt(100000,1000000)+".SH"; }
        while(jdbc.queryForObject("select count(*) from securities where ts_code=?",Long.class,code)>0);
        jdbc.update("insert into securities(market,ts_code,name,security_type) values ('SH',?,'test','STOCK')",code);
        return jdbc.queryForObject("select id from securities where ts_code=?",Long.class,code);
    }
}
