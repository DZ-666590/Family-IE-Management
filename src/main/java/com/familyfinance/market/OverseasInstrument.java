package com.familyfinance.market;

public record OverseasInstrument(
        String symbol, String name, String market, String currency, String exchange, String timezone) {
}
