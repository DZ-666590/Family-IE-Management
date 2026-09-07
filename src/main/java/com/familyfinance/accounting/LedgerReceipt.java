package com.familyfinance.accounting;

import java.time.LocalDate;

public record LedgerReceipt(long journalId, long householdId, String sourceType, long sourceId,
        long revision, LocalDate effectiveOn, Long reversesJournalId) {}
