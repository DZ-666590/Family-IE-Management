package com.familyfinance.market;

import java.math.BigDecimal;
import java.time.Instant;

/** Reference-only, never a financial transaction or a finalized daily close. */
public record SpotQuote(String symbol,String market,String currency,BigDecimal price,
                        Instant quotedAt,Instant fetchedAt,String source,Integer delayMinutes,String status,Long ageSeconds) {}
