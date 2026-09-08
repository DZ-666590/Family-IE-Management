package com.familyfinance.investment;

public record SecurityResponse(
        long id,
        String market,
        String tsCode,
        String name,
        String securityType,
        boolean active,String currency,String symbol,String exchange,String timezone) {

    public SecurityResponse(long id,String market,String tsCode,String name,String type,boolean active){
        this(id,market,tsCode,name,type,active,"CNY",tsCode.split("\\.")[0],market,"Asia/Shanghai");
    }

    static SecurityResponse from(Security security) {
        return new SecurityResponse(
                security.getId(), security.getMarket(), security.getTsCode(), security.getName(),
                security.getSecurityType(), security.isActive(),security.getCurrency(),security.getSymbol(),security.getExchange(),security.getTimezone());
    }
}
