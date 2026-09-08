package com.familyfinance.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/** Settled yuan amounts. Integer cents exist only at explicit compatibility boundaries. */
public final class DecimalMoney {
    public static final BigDecimal MAX_AMOUNT=BigDecimal.valueOf(Long.MAX_VALUE,2);
    public static final BigDecimal MIN_AMOUNT=BigDecimal.valueOf(Long.MIN_VALUE,2);
    private DecimalMoney() {}

    public static BigDecimal settled(BigDecimal amount) {
        if(amount==null) throw invalid("请提供金额");
        final BigDecimal normalized;
        try { normalized=amount.setScale(2,RoundingMode.UNNECESSARY); }
        catch(ArithmeticException ex) { throw invalid("正式入账金额必须精确到分，不允许非零的分以下小数"); }
        if(normalized.compareTo(MIN_AMOUNT)<0 || normalized.compareTo(MAX_AMOUNT)>0)
            throw invalid("金额超出整数分兼容范围");
        return normalized;
    }
    public static BigDecimal fromCents(long cents) { return BigDecimal.valueOf(cents,2); }
    public static long toCents(BigDecimal amount) { return settled(amount).movePointRight(2).longValueExact(); }
    public static String format(BigDecimal amount) { return settled(amount).toPlainString(); }
    private static RequestValidationException invalid(String message) {
        return new RequestValidationException(Map.of("amount",message));
    }
}
