package com.familyfinance.fx;

import java.time.LocalDate;

@FunctionalInterface
public interface ExchangeRateProvider {
    ExchangeRateBatch fetch(LocalDate asOf);
}
