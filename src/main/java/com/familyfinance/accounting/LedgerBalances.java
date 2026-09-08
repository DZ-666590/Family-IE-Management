package com.familyfinance.accounting;

import java.math.BigDecimal;
import com.familyfinance.shared.DecimalMoney;
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

    Map<String,BigDecimal> calculate(long h,List<Change> changes,boolean verifyProjection) {
        return calculate(h,changes,verifyProjection,true);
    }
    Map<String,BigDecimal> calculate(long h,List<Change> changes,boolean verifyProjection,boolean currentRead) {
        Map<String,BigDecimal> result=new LinkedHashMap<>();
        for(var account:store.accounts(h,currentRead)) {
            TreeMap<LocalDate,BigDecimal> days=new TreeMap<>();
            BigDecimal current=DecimalMoney.fromCents(0);
            for(var m:store.movements(h,account.code(),currentRead)) {
                BigDecimal delta=delta(account.kind(),m.debit(),m.credit());
                current=current.add(delta);
                days.merge(m.day(),delta,BigDecimal::add);
            }
            if(verifyProjection && current.compareTo(account.balance())!=0)
                throw LedgerStore.conflict("ACCOUNTING_BALANCE_MISMATCH","科目 "+account.code()+" 的余额投影与分录不一致，请核对后重建");
            for(var change:changes) for(var e:change.entries()) if(e.accountCode().equals(account.code()))
                days.merge(change.day(),delta(account.kind(),e.debitAmount(),e.creditAmount()),BigDecimal::add);
            BigDecimal running=DecimalMoney.fromCents(0);
            for(var date:days.entrySet()) {
                running=running.add(date.getValue());
                if((account.kind()==LedgerAccountKind.CASH || account.kind()==LedgerAccountKind.LOAN) && running.signum()<0)
                    throw LedgerStore.conflict(account.kind()==LedgerAccountKind.CASH ? "INSUFFICIENT_FUNDS":"INSUFFICIENT_LOAN_PRINCIPAL",
                            (account.kind()==LedgerAccountKind.CASH ? "资金账户「"+store.cashAccountName(h,account.code(),currentRead)+"」" : "贷款本金")
                            +"在 "+date.getKey()+" 入账后余额不足，该日资金缺口 ¥"+running.negate().toPlainString()
                            +"；当前账内余额 ¥"+DecimalMoney.format(account.balance())+"。请核对该日期及之前的资金记录。");
                if(running.compareTo(DecimalMoney.MAX_AMOUNT)>0 || running.compareTo(DecimalMoney.MIN_AMOUNT)<0)
                    throw LedgerStore.conflict("ACCOUNTING_AMOUNT_OVERFLOW","科目 "+account.code()+" 的余额超出整数分范围");
            }
            result.put(account.code(),running);
        }
        return result;
    }

    void save(long h,Map<String,BigDecimal> balances) {
        balances.forEach((code,amount)->store.jdbc.update("update ledger_accounts set balance_amount=? where household_id=? and account_code=?",amount,h,code));
    }

    private static BigDecimal delta(LedgerAccountKind kind,BigDecimal debit,BigDecimal credit) {
        BigDecimal value=debit.subtract(credit);
        return switch(kind) { case CASH,ASSET,EXPENSE -> value; case LOAN,INCOME,EQUITY -> value.negate(); };
    }
}
