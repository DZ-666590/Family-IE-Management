package com.familyfinance.accounting;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashMap;

@Service
public class LedgerReadService {
    private final LedgerStore store;
    private final LedgerBalances ledgerBalances;
    public LedgerReadService(LedgerStore store,LedgerBalances ledgerBalances) { this.store=store; this.ledgerBalances=ledgerBalances; }
    /** Display snapshot only. Funding decisions belong exclusively to LedgerPostingService. */
    @Transactional(readOnly=true)
    public long balance(long householdId, String accountCode) {
        return store.accounts(householdId,false).stream().filter(a->a.code().equals(accountCode)).mapToLong(LedgerStore.Account::balance).findFirst().orElse(0);
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
    @Transactional(readOnly=true)
    public List<LedgerEntryInput> entries(long h,long journalId) {
        store.journal(h,journalId,false);
        return List.copyOf(store.entries(h,journalId,false));
    }
    @Transactional(readOnly=true)
    public Map<String,Long> balances(long h) {
        Map<String,Long> values=new LinkedHashMap<>();
        store.accounts(h,false).forEach(a->values.put(a.code(),a.balance()));
        return Map.copyOf(values);
    }
    /** Compare with balances() to detect drift; this method never repairs it. */
    @Transactional(readOnly=true)
    public Map<String,Long> reconstructedBalances(long h) {
        return Map.copyOf(ledgerBalances.calculate(h,List.of(),false,false));
    }
    /** Explicit administrative repair. Never invoked automatically by a posting or a read. */
    @Transactional
    public void rebuildBalances(long h) {
        store.lock(h);
        ledgerBalances.save(h,ledgerBalances.calculate(h,List.of(),false));
    }
}
