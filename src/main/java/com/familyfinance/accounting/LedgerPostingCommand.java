package com.familyfinance.accounting;

import java.time.LocalDate;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

public record LedgerPostingCommand(long householdId, String sourceType, long sourceId,
        String idempotencyKey, LocalDate effectiveOn, long actorId, List<LedgerEntryInput> entries) {
    public LedgerPostingCommand {
        // Capture input once; nulls remain available to the service's actionable validation.
        if(entries!=null) entries=Collections.unmodifiableList(new ArrayList<>(entries));
    }
}
