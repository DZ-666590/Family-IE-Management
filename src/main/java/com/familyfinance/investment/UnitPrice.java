package com.familyfinance.investment;

import java.math.BigDecimal;

public final class UnitPrice {
    private UnitPrice(){}
    public static String format(BigDecimal value){
        if(value==null)return null;
        value=value.stripTrailingZeros();return value.setScale(Math.max(2,value.scale())).toPlainString();
    }
    public static BigDecimal parse(String raw){
        if(raw==null||!raw.trim().matches("\\d{1,9}(\\.\\d{1,6})?"))throw new IllegalArgumentException("单价须为最多六位小数的正数");
        BigDecimal value=new BigDecimal(raw.trim());
        if(value.signum()<=0||value.compareTo(new BigDecimal("999999999.99"))>0)throw new IllegalArgumentException("单价须大于零且不超过999,999,999.99");
        return value;
    }
}
