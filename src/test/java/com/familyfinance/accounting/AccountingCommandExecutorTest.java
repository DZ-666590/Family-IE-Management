package com.familyfinance.accounting;

import static org.assertj.core.api.Assertions.*;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountingCommandExecutorTest {
    AccountingCommandExecutor executor=new AccountingCommandExecutor();
    RuntimeException deadlock() { return new CannotAcquireLockException("deadlock",new SQLException("deadlock","40001",1213)); }
    @Test void retriesWholeCommandAtMostThreeTimes() {
        AtomicInteger calls=new AtomicInteger();
        assertThat(executor.execute(()->{ if(calls.incrementAndGet()<3) throw deadlock(); return 42; })).isEqualTo(42);
        assertThat(calls.get()).isEqualTo(3);
    }
    @Test void exhaustsThreeAttempts() {
        AtomicInteger calls=new AtomicInteger();
        assertThatThrownBy(()->executor.execute(()->{calls.incrementAndGet(); throw deadlock();})).isInstanceOf(CannotAcquireLockException.class);
        assertThat(calls.get()).isEqualTo(3);
    }
    @Test void neverRetriesValidationTimeoutOrUncertainCommit() {
        for(RuntimeException failure:new RuntimeException[]{new IllegalArgumentException(),new CannotAcquireLockException("timeout",new SQLException("timeout","HY000",1205)),new TransactionSystemException("commit uncertain",deadlock())}) {
            AtomicInteger calls=new AtomicInteger();
            assertThatThrownBy(()->executor.execute(()->{calls.incrementAndGet();throw failure;})).isSameAs(failure);
            assertThat(calls.get()).isEqualTo(1);
        }
    }
    @Test void ambientTransactionRunsOnceAndNeverRetriesItsFailedState() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        AtomicInteger calls=new AtomicInteger();
        try {
            assertThatThrownBy(()->executor.execute(()->{calls.incrementAndGet();throw deadlock();})).isInstanceOf(CannotAcquireLockException.class);
            assertThat(calls.get()).isEqualTo(1);
        } finally {TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
}
