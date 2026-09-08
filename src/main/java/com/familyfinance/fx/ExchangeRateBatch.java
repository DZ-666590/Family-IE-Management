package com.familyfinance.fx;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Complete same-source/day reference snapshot; rates mean CNY per unit. */
public record ExchangeRateBatch(String source, LocalDate effectiveOn, Map<String,BigDecimal> cnyPerUnit) {
    public ExchangeRateBatch {
        if(!"ECB".equals(source) || effectiveOn==null || effectiveOn.getYear()<1999 || cnyPerUnit==null
                || !cnyPerUnit.keySet().equals(Set.of("USD","HKD"))) throw new IllegalArgumentException("Incomplete exchange rate batch");
        var normalized=new TreeMap<String,BigDecimal>();
        for(var row:cnyPerUnit.entrySet()) {
            BigDecimal rate=row.getValue();
            if(rate==null || rate.signum()<=0 || rate.compareTo(new BigDecimal("1000000"))>0)
                throw new IllegalArgumentException("Invalid exchange rate");
            rate=rate.setScale(12,RoundingMode.HALF_UP);
            if(rate.signum()==0)throw new IllegalArgumentException("Exchange rate below precision");
            normalized.put(row.getKey(),rate);
        }
        cnyPerUnit=Map.copyOf(normalized);
    }
}
