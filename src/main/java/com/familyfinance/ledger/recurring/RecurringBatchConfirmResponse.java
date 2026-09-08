package com.familyfinance.ledger.recurring;

import java.util.List;

public record RecurringBatchConfirmResponse(
        int requested,
        int confirmed,
        List<RecurringOccurrenceResponse> occurrences) {}
