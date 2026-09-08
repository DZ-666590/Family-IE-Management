package com.familyfinance.reporting;

public record InvestmentSummary(Long marketValueCents, int positionCount, int unpricedPositionCount,
                                boolean manualPrice, boolean stalePrice, boolean missingPrice,Long estimatedValueCents) {
    public InvestmentSummary(long marketValueCents,int positionCount,int unpricedPositionCount,boolean manualPrice,boolean stalePrice,boolean missingPrice) {
        this(marketValueCents,positionCount,unpricedPositionCount,manualPrice,stalePrice,missingPrice,marketValueCents);
    }
}
