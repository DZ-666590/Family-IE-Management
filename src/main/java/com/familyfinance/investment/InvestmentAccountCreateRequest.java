package com.familyfinance.investment;

public record InvestmentAccountCreateRequest(String name, String brokerName, String currency, Long fundingAccountId) {
    public InvestmentAccountCreateRequest(String name,String brokerName,String currency){this(name,brokerName,currency,null);}
}
