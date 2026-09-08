package com.familyfinance.market;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record OverseasCandleResponse(
        OverseasInstrument instrument, String symbol, String source, String adjustment,
        LocalDate asOf, Instant fetchedAt, boolean stale, boolean supported, List<CandleBar> bars) {
    public OverseasCandleResponse {
        bars = bars == null ? null : List.copyOf(bars);
    }
}
