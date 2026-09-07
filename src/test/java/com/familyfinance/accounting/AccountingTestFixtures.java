package com.familyfinance.accounting;

import com.familyfinance.transaction.FinancialTransactionRepository;
import com.familyfinance.ledger.FinancialAccountRepository;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;
import java.util.UUID;

/** Explicit accounting setup for reporting fixtures, never production backfill. */
public final class AccountingTestFixtures {
    private AccountingTestFixtures(){}
    public static void postFixtureTransactions(ApplicationContext context,long household) {
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(ignored->{
            var transactions=context.getBean(FinancialTransactionRepository.class);
            var accounts=context.getBean(FinancialAccountRepository.class);
            var cash=context.getBean(CashAccountingService.class);
            var read=context.getBean(LedgerReadService.class);
            var jdbc=context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
            long actor=jdbc.queryForObject("select min(id) from app_users where household_id=?",Long.class,household);
            for(var account:accounts.findAll().stream().filter(a->a.getHousehold().getId()==household).toList()) {
                if(!account.isOpeningConfirmed()) {
                    cash.opening(account,100000000L,LocalDate.of(2020,1,1),actor,"fixture-open:"+UUID.randomUUID());
                    accounts.flush();
                }
            }
            for(var tx:transactions.findByHouseholdIdAndOccurredOnBetween(household,LocalDate.of(2020,1,1),LocalDate.of(2100,1,1),org.springframework.data.domain.Sort.by("id"))) {
                if(read.currentSource(household,"TRANSACTION",tx.getId()).isEmpty())cash.postTransaction(tx,"fixture-tx:"+UUID.randomUUID());
            }
        });
    }
}
