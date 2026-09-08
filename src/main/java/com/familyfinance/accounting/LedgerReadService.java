package com.familyfinance.accounting;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.math.BigDecimal;
import com.familyfinance.shared.DecimalMoney;

@Service
public class LedgerReadService {
    private final LedgerStore store;
    private final LedgerBalances ledgerBalances;
    public LedgerReadService(LedgerStore store,LedgerBalances ledgerBalances) { this.store=store; this.ledgerBalances=ledgerBalances; }
    /** Display snapshot only. Funding decisions belong exclusively to LedgerPostingService. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public BigDecimal balanceAmount(long householdId, String accountCode) {
        return store.accounts(householdId,false).stream().filter(a->a.code().equals(accountCode)).map(LedgerStore.Account::balance).findFirst().orElse(DecimalMoney.fromCents(0));
    }
    /** Exact integer-cent adapter for existing callers; display snapshot only. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public long balance(long householdId,String accountCode) {
        return DecimalMoney.toCents(balanceAmount(householdId,accountCode));
    }
    /** Mutation lookup: locks current state. Call within a writable adapter transaction. */
    @Transactional
    public Optional<LedgerReceipt> currentSource(long h,String type,long id) {
        store.lock(h);
        return store.source(h,type,id).filter(s->s.currentJournalId()!=null).map(s->store.journal(h,s.currentJournalId()).receipt());
    }
    /** Mutation lookup before creating a business row; returns the original receipt on replay. */
    @Transactional
    public Optional<LedgerReceipt> requestReceipt(long h,String key) {
        store.lock(h);
        return store.request(h,key).map(LedgerStore.Journal::receipt);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<LedgerEntryInput> entries(long h,long journalId) {
        store.journal(h,journalId,false);
        return List.copyOf(store.entries(h,journalId,false));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,BigDecimal> balancesAmount(long h) {
        Map<String,BigDecimal> values=new LinkedHashMap<>();
        store.accounts(h,false).forEach(a->values.put(a.code(),a.balance()));
        return Map.copyOf(values);
    }
    /** Compare with balancesAmount() to detect drift; this method never repairs it. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,BigDecimal> reconstructedBalanceAmounts(long h) {
        return Map.copyOf(ledgerBalances.calculate(h,List.of(),false,false));
    }
    /** Exact integer-cent boundary adapters for existing callers. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Long> balances(long h) { return cents(balancesAmount(h)); }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Long> reconstructedBalances(long h) { return cents(reconstructedBalanceAmounts(h)); }
    private static Map<String,Long> cents(Map<String,BigDecimal> amounts) {
        Map<String,Long> result=new LinkedHashMap<>();
        amounts.forEach((code,amount)->result.put(code,DecimalMoney.toCents(amount)));
        return Map.copyOf(result);
    }
    /** Explicit administrative repair. Never invoked automatically by a posting or a read. */
    @Transactional
    public void rebuildBalances(long h) {
        store.lock(h);
        ledgerBalances.save(h,ledgerBalances.calculate(h,List.of(),false));
    }
}
