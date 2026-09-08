package com.familyfinance.market;

import com.familyfinance.investment.Security;
import com.familyfinance.shared.Money;
import java.time.Instant;
import java.time.LocalDate;

public record MarketPriceResponse(
        long securityId, String tsCode, String name, String price, QuoteSource source,
        LocalDate tradeDate, Instant fetchedAt, boolean stale, String error,String currency) {
    public MarketPriceResponse(long id,String code,String name,String price,QuoteSource source,LocalDate day,Instant fetchedAt,boolean stale,String error){this(id,code,name,price,source,day,fetchedAt,stale,error,"CNY");}
    static MarketPriceResponse noQuote(Security security, String error) {
        return new MarketPriceResponse(security.getId(), security.getTsCode(), security.getName(), null,
                null, null, null, true, error,security.getCurrency());
    }
    static MarketPriceResponse manual(Security security, ManualPriceOverride override, boolean stale) {
        return new MarketPriceResponse(security.getId(), security.getTsCode(), security.getName(),
                com.familyfinance.investment.UnitPrice.format(override.getUnitPrice()), QuoteSource.MANUAL, override.getEffectiveOn(),
                null, stale, null,security.getCurrency());
    }
    static MarketPriceResponse provider(Security security, MarketPriceSnapshot snapshot, boolean stale) {
        return new MarketPriceResponse(security.getId(), security.getTsCode(), security.getName(),
                Money.formatCents(snapshot.getCloseCents()), snapshot.getSource(), snapshot.getTradeDate(),
                snapshot.getFetchedAt(), stale, null);
    }
}
