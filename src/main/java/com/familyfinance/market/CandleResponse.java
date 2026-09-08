package com.familyfinance.market;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record CandleResponse(
        String symbol, String source, String adjustment, LocalDate asOf, Instant fetchedAt,
        boolean stale, boolean supported, List<CandleBar> bars) {
    public static CandleResponse unsupported(String symbol, String adjustment) {
        return new CandleResponse(
                symbol, QuoteSource.BAOSTOCK.name(), adjustment, null, null, false, false, List.of());
    }
}
