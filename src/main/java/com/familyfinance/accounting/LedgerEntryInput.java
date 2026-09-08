package com.familyfinance.accounting;

import java.math.BigDecimal;
import com.familyfinance.shared.DecimalMoney;

public record LedgerEntryInput(String accountCode, LedgerAccountKind kind,
        BigDecimal debitAmount, BigDecimal creditAmount, Long categoryId, Long memberId, String currency) {
    public LedgerEntryInput {
        if(currency==null || !java.util.Set.of("CNY","HKD","USD").contains(currency))
            throw new IllegalArgumentException("Unsupported ledger currency");
        debitAmount=DecimalMoney.settled(debitAmount);
        creditAmount=DecimalMoney.settled(creditAmount);
    }
    public LedgerEntryInput(String code,LedgerAccountKind kind,BigDecimal debit,BigDecimal credit,Long category,Long member) {
        this(code,kind,debit,credit,category,member,"CNY");
    }
    /** Exact adapter for legacy business modules still supplying integer cents. */
    public LedgerEntryInput(String accountCode,LedgerAccountKind kind,long debitCents,long creditCents,Long categoryId,Long memberId) {
        this(accountCode,kind,DecimalMoney.fromCents(debitCents),DecimalMoney.fromCents(creditCents),categoryId,memberId);
    }
    public LedgerEntryInput(String code,LedgerAccountKind kind,long debit,long credit,Long category,Long member,String currency) {
        this(code,kind,DecimalMoney.fromCents(debit),DecimalMoney.fromCents(credit),category,member,currency);
    }
    public long debitCents() { return DecimalMoney.toCents(debitAmount); }
    public long creditCents() { return DecimalMoney.toCents(creditAmount); }
}
