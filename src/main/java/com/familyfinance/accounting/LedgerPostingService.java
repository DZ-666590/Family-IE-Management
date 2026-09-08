package com.familyfinance.accounting;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.List;

@Service
public class LedgerPostingService {
    private final LedgerStore store;
    private final LedgerValidation validation;
    private final LedgerBalances balances;
    private final Clock clock;
    public LedgerPostingService(LedgerStore store,LedgerValidation validation,LedgerBalances balances,Clock clock) {
        this.store=store; this.validation=validation; this.balances=balances; this.clock=clock;
    }

    /** Preflight one aggregate debit before a multi-journal command writes its first child. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void requireAvailableCash(long household,long account,java.time.LocalDate day,java.math.BigDecimal amount) {
        amount=com.familyfinance.shared.DecimalMoney.settled(amount);
        if(amount.signum()<=0)throw new IllegalArgumentException("cash debit must be positive");
        store.lock(household);String code="CASH:"+account;
        if(store.accounts(household,true).stream().noneMatch(a->a.code().equals(code)))
            throw LedgerStore.conflict("INSUFFICIENT_FUNDS","付款账户余额不足，请先核对资金记录");
        balances.calculate(household,List.of(new LedgerBalances.Change(day,List.of(
                new LedgerEntryInput(code,LedgerAccountKind.CASH,java.math.BigDecimal.ZERO,amount,null,null)))),true);
    }

    @Transactional
    public LedgerReceipt post(LedgerPostingCommand command) {
        validation.command(command);
        store.lock(command.householdId());
        String digest=LedgerRequestDigest.of("POST",command);
        var replay=replay(command.householdId(),command.idempotencyKey(),digest);
        if(replay!=null) return replay;
        if(store.source(command.householdId(),command.sourceType(),command.sourceId()).isPresent())
            throw LedgerStore.conflict("ACCOUNTING_SOURCE_EXISTS","业务来源已有账务记录，请使用更正入口");
        validation.register(command);
        var finalBalances=balances.calculate(command.householdId(),List.of(new LedgerBalances.Change(command.effectiveOn(),command.entries())),true);
        var receipt=store.append(command,1,"POST",digest,null,clock.instant());
        balances.save(command.householdId(),finalBalances);
        store.pointSource(command,1,receipt.journalId(),false);
        return receipt;
    }

    @Transactional
    public LedgerReceipt replace(LedgerPostingCommand command) {
        validation.command(command);
        store.lock(command.householdId());
        String digest=LedgerRequestDigest.of("REPLACE",command);
        var replay=replay(command.householdId(),command.idempotencyKey(),digest);
        if(replay!=null) return replay;
        var source=activeSource(command.householdId(),command.sourceType(),command.sourceId());
        var original=store.journal(command.householdId(),source.currentJournalId()).receipt();
        long revision=nextRevision(source.revision());
        var reversal=reversal(command.householdId(),command.sourceType(),command.sourceId(),null,command.actorId(),original);
        validation.register(command);
        var finalBalances=balances.calculate(command.householdId(),List.of(
                new LedgerBalances.Change(reversal.effectiveOn(),reversal.entries()),
                new LedgerBalances.Change(command.effectiveOn(),command.entries())),true);
        store.append(reversal,revision,"REVERSE",digest,original.journalId(),clock.instant());
        var receipt=store.append(command,revision,"REPLACE",digest,null,clock.instant());
        balances.save(command.householdId(),finalBalances);
        store.pointSource(command,revision,receipt.journalId(),true);
        return receipt;
    }

    @Transactional
    public LedgerReceipt reverse(long householdId,String sourceType,long sourceId,String idempotencyKey,long actorId) {
        validation.identity(householdId,sourceType,sourceId,idempotencyKey,actorId);
        store.lock(householdId);
        // Reversal identity is stable across retries and never depends on today's date/current version.
        var identity=new LedgerPostingCommand(householdId,sourceType,sourceId,idempotencyKey,null,actorId,List.of());
        String digest=LedgerRequestDigest.of("REVERSE",identity);
        var replay=replay(householdId,idempotencyKey,digest);
        if(replay!=null) return replay;
        var source=activeSource(householdId,sourceType,sourceId);
        var original=store.journal(householdId,source.currentJournalId()).receipt();
        var reversal=reversal(householdId,sourceType,sourceId,idempotencyKey,actorId,original);
        validation.register(reversal);
        var finalBalances=balances.calculate(householdId,List.of(new LedgerBalances.Change(reversal.effectiveOn(),reversal.entries())),true);
        long revision=nextRevision(source.revision());
        var receipt=store.append(reversal,revision,"REVERSE",digest,original.journalId(),clock.instant());
        balances.save(householdId,finalBalances);
        store.pointSource(reversal,revision,null,true);
        return receipt;
    }

    private LedgerReceipt replay(long h,String key,String digest) {
        var replay=store.request(h,key);
        if(replay.isEmpty()) return null;
        if(!replay.get().digest().equals(digest)) throw LedgerStore.conflict("IDEMPOTENCY_KEY_REUSED","请求键已用于不同内容，请为新操作生成新请求键");
        return replay.get().receipt();
    }

    private LedgerStore.Source activeSource(long h,String type,long id) {
        return store.source(h,type,id).filter(s->s.currentJournalId()!=null)
                .orElseThrow(()->LedgerStore.conflict("ACCOUNTING_SOURCE_NOT_ACTIVE","该业务没有可更正或冲销的有效凭证"));
    }

    private LedgerPostingCommand reversal(long h,String type,long id,String key,long actor,LedgerReceipt original) {
        var entries=store.entries(h,original.journalId()).stream().map(e->new LedgerEntryInput(
                e.accountCode(),e.kind(),e.creditAmount(),e.debitAmount(),e.categoryId(),e.memberId())).toList();
        return new LedgerPostingCommand(h,type,id,key,original.effectiveOn(),actor,entries);
    }

    private static long nextRevision(long revision) {
        if(revision==Long.MAX_VALUE) throw LedgerStore.conflict("ACCOUNTING_REVISION_OVERFLOW","账务版本号超出范围");
        return revision+1;
    }
}
