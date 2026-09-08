package com.familyfinance.market;

import java.math.BigDecimal;

public record CandleBar(
        long timestamp, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
        long volume, BigDecimal turnover) {
}
