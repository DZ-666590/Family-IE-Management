package com.familyfinance.investment;

import java.time.Instant;

public record InvestmentAccountPatchRequest(
        String name,
        String brokerName,
        String currency,
        Long createdBy,
        Instant archivedAt, Long fundingAccountId) {
    public InvestmentAccountPatchRequest(String name,String brokerName,String currency,Long createdBy,Instant archivedAt){this(name,brokerName,currency,createdBy,archivedAt,null);}
}
