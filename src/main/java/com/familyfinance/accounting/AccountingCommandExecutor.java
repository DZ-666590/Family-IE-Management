package com.familyfinance.accounting;

import java.util.function.Supplier;
import java.sql.SQLException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class AccountingCommandExecutor {
    /** The supplier invokes a transactional domain proxy, including its commit/rollback.
     * Ambient callers get one attempt: their outer transaction owns recovery. */
    public <T> T execute(Supplier<T> transactionalCommand) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) return transactionalCommand.get();
        for(int attempt=1;;attempt++) {
            try {return transactionalCommand.get();}
            catch(RuntimeException failure) {
                if(attempt>=3 || TransactionSynchronizationManager.isActualTransactionActive() || !confirmedDeadlock(failure)) throw failure;
            }
        }
    }
    private boolean confirmedDeadlock(RuntimeException failure) {
        // Spring transaction/commit failures, connectivity errors and lock timeouts are intentionally excluded.
        if(!(failure instanceof PessimisticLockingFailureException)) return false;
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
            if(cause instanceof SQLException sql && sql.getErrorCode()==1213 && "40001".equals(sql.getSQLState())) return true;
        }
        return false;
    }
}
