package com.familyfinance.accounting;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/** Derives daily closing balances, including candidate replacement legs as one atomic change. */
@Component
class LedgerBalances {
    private final LedgerStore store;
    LedgerBalances(LedgerStore store) { this.store=store; }
    record Change(LocalDate day,List<LedgerEntryInput> entries) {}

    Map<String,Long> calculate(long h,List<Change> changes,boolean verifyProjection) {
        return calculate(h,changes,verifyProjection,true);
    }
    Map<String,Long> calculate(long h,List<Change> changes,boolean verifyProjection,boolean currentRead) {
        Map<String,Long> result=new LinkedHashMap<>();
        for(var account:store.accounts(h,currentRead)) {
            TreeMap<LocalDate,BigInteger> days=new TreeMap<>();
            BigInteger current=BigInteger.ZERO;
            for(var m:store.movements(h,account.code(),currentRead)) {
                BigInteger delta=delta(account.kind(),m.debit(),m.credit());
                current=current.add(delta);
                days.merge(m.day(),delta,BigInteger::add);
            }
            if(verifyProjection && !current.equals(BigInteger.valueOf(account.balance())))
                throw LedgerStore.conflict("ACCOUNTING_BALANCE_MISMATCH","科目 "+account.code()+" 的余额投影与分录不一致，请核对后重建");
            for(var change:changes) for(var e:change.entries()) if(e.accountCode().equals(account.code()))
                days.merge(change.day(),delta(account.kind(),e.debitCents(),e.creditCents()),BigInteger::add);
            BigInteger running=BigInteger.ZERO;
            for(var date:days.entrySet()) {
                running=running.add(date.getValue());
                if((account.kind()==LedgerAccountKind.CASH || account.kind()==LedgerAccountKind.LOAN) && running.signum()<0)
                    throw LedgerStore.conflict(account.kind()==LedgerAccountKind.CASH ? "INSUFFICIENT_FUNDS":"INSUFFICIENT_LOAN_PRINCIPAL",
                            "科目 "+account.code()+" 在 "+date.getKey()+" 余额不足，缺少 "+running.negate()+" 分；当前余额 "+account.balance()+" 分");
                if(running.compareTo(BigInteger.valueOf(Long.MAX_VALUE))>0 || running.compareTo(BigInteger.valueOf(Long.MIN_VALUE))<0)
                    throw LedgerStore.conflict("ACCOUNTING_AMOUNT_OVERFLOW","科目 "+account.code()+" 的余额超出整数分范围");
            }
            result.put(account.code(),running.longValueExact());
        }
        return result;
    }

    void save(long h,Map<String,Long> balances) {
        balances.forEach((code,amount)->store.jdbc.update("update ledger_accounts set balance_cents=? where household_id=? and account_code=?",amount,h,code));
    }

    private static BigInteger delta(LedgerAccountKind kind,long debit,long credit) {
        BigInteger value=BigInteger.valueOf(debit).subtract(BigInteger.valueOf(credit));
        return switch(kind) { case CASH,ASSET,EXPENSE -> value; case LOAN,INCOME,EQUITY -> value.negate(); };
    }
}
