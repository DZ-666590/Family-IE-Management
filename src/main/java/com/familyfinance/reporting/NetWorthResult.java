package com.familyfinance.reporting;

import java.util.List;

public record NetWorthResult(Long assetCents, long liabilityCents, Long netWorthCents,
                             List<AllocationSlice> allocation, Integer debtRatioTenths,
                             List<DebtProgress> debtProgress, BudgetSummary budget,
                             InvestmentSummary investment,long cumulativeAssetValuationChangeCents,long knownAssetCents,List<Unconverted> unconverted) {
    public record Unconverted(String kind,String currency,String nativeAmount){}
    public NetWorthResult(long assetCents,long liabilityCents,long netWorthCents,List<AllocationSlice> allocation,int debtRatioTenths,List<DebtProgress> debtProgress,BudgetSummary budget,InvestmentSummary investment,long change){
        this(assetCents,liabilityCents,netWorthCents,allocation,debtRatioTenths,debtProgress,budget,investment,change,assetCents,List.of());
    }
    public NetWorthResult(long assetCents,long liabilityCents,long netWorthCents,List<AllocationSlice> allocation,int debtRatioTenths,List<DebtProgress> debtProgress,BudgetSummary budget,InvestmentSummary investment) {
        this(assetCents,liabilityCents,netWorthCents,allocation,debtRatioTenths,debtProgress,budget,investment,0);
    }
}
