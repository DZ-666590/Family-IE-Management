package com.familyfinance.accounting;

public final class LedgerCodes {
    private LedgerCodes(){}
    public static String inCurrency(String code,String currency){return "CNY".equals(currency)?code:code+":"+currency;}
}
