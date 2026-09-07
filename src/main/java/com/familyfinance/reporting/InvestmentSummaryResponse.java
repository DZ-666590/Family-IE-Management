package com.familyfinance.reporting;

import com.familyfinance.shared.Money;

public record InvestmentSummaryResponse(String marketValue, int positionCount, int unpricedPositionCount,
                                        boolean manualPrice, boolean stalePrice, boolean missingPrice,String estimatedValue) {
    static InvestmentSummaryResponse from(InvestmentSummary value) {
        return new InvestmentSummaryResponse(value.marketValueCents()==null?null:Money.formatCents(value.marketValueCents()), value.positionCount(),
                value.unpricedPositionCount(), value.manualPrice(), value.stalePrice(), value.missingPrice(),Money.formatCents(value.estimatedValueCents()));
    }
}
