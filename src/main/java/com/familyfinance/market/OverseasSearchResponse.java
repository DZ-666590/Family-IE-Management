package com.familyfinance.market;

import java.time.Instant;
import java.util.List;

public record OverseasSearchResponse(
        List<OverseasInstrument> items, boolean hasNext, Instant updatedAt, boolean stale,
        String state, String error) {
    public OverseasSearchResponse {
        items = items == null ? null : List.copyOf(items);
    }
}
