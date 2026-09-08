package com.familyfinance.accounting;

import java.math.BigDecimal;
import com.familyfinance.shared.DecimalMoney;

public record LedgerEntryInput(String accountCode, LedgerAccountKind kind,
        BigDecimal debitAmount, BigDecimal creditAmount, Long categoryId, Long memberId) {
    public LedgerEntryInput {
        debitAmount=DecimalMoney.settled(debitAmount);
        creditAmount=DecimalMoney.settled(creditAmount);
    }
    /** Exact adapter for legacy business modules still supplying integer cents. */
    public LedgerEntryInput(String accountCode,LedgerAccountKind kind,long debitCents,long creditCents,Long categoryId,Long memberId) {
        this(accountCode,kind,DecimalMoney.fromCents(debitCents),DecimalMoney.fromCents(creditCents),categoryId,memberId);
    }
    public long debitCents() { return DecimalMoney.toCents(debitAmount); }
    public long creditCents() { return DecimalMoney.toCents(creditAmount); }
}
