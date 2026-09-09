package com.familyfinance.fx;

import java.time.LocalDate;

@FunctionalInterface
public interface ExchangeRateProvider {
    ExchangeRateBatch fetch(LocalDate asOf);
    default java.util.List<ExchangeRateBatch> fetchRange(LocalDate from,LocalDate to) {
        throw new UnsupportedOperationException("Historical range is unavailable");
    }
}
