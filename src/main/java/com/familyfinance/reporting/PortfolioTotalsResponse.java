package com.familyfinance.reporting;

public record PortfolioTotalsResponse(
        String cost, String marketValue, String realizedProfit, String unrealizedProfit,
        String totalProfit, int unpricedPositions,String estimatedValue) {
    public PortfolioTotalsResponse(String cost,String marketValue,String realizedProfit,String unrealizedProfit,String totalProfit,int unpricedPositions){
        this(cost,marketValue,realizedProfit,unrealizedProfit,totalProfit,unpricedPositions,marketValue==null?cost:marketValue);
    }
}
