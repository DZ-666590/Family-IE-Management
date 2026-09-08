package com.familyfinance.reporting;

public record PortfolioTotalsResponse(
        String cost, String marketValue, String realizedProfit, String unrealizedProfit,
        String totalProfit, int unpricedPositions,String estimatedValue,String currency,int missingFxRates,String knownEstimatedValue) {
    public PortfolioTotalsResponse(String cost,String marketValue,String realizedProfit,String unrealizedProfit,String totalProfit,int unpricedPositions,String estimatedValue){
        this(cost,marketValue,realizedProfit,unrealizedProfit,totalProfit,unpricedPositions,estimatedValue,"CNY",0,estimatedValue);
    }
    public PortfolioTotalsResponse(String cost,String marketValue,String realizedProfit,String unrealizedProfit,String totalProfit,int unpricedPositions){
        this(cost,marketValue,realizedProfit,unrealizedProfit,totalProfit,unpricedPositions,marketValue==null?cost:marketValue);
    }
}
