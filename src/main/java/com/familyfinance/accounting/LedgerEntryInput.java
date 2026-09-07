package com.familyfinance.accounting;

public record LedgerEntryInput(String accountCode, LedgerAccountKind kind,
        long debitCents, long creditCents, Long categoryId, Long memberId) {}
