package com.familyfinance.investment;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PositionTrade(
        long id,
        LocalDate tradedOn,
        InvestmentTradeType type,
        BigDecimal quantity,
        BigDecimal unitPrice,
        long feeCents) {
    public PositionTrade(long id,LocalDate day,InvestmentTradeType type,BigDecimal quantity,long cents,long fee) {
        this(id,day,type,quantity,BigDecimal.valueOf(cents,2),fee);
    }
    public long priceCents(){return unitPrice.movePointRight(2).longValueExact();}
}
