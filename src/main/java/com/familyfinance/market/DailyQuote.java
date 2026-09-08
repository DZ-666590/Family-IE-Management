package com.familyfinance.market;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailyQuote(
        String symbol, LocalDate tradeDate, long openCents, long highCents, long lowCents,
        long closeCents, long preCloseCents, BigDecimal pctChange, QuoteSource source) {
    public DailyQuote(
            String symbol, LocalDate tradeDate, long openCents, long highCents, long lowCents,
            long closeCents, long preCloseCents, BigDecimal pctChange) {
        this(symbol, tradeDate, openCents, highCents, lowCents, closeCents, preCloseCents, pctChange,
                QuoteSource.TUSHARE);
    }
}
