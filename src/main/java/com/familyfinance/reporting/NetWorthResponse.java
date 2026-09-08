package com.familyfinance.reporting;

import com.familyfinance.shared.Money;
import java.math.BigDecimal;
import java.util.List;

public record NetWorthResponse(String asset, String liability, String netWorth, List<AllocationResponse> allocation,
                               String debtRatioPercent, BudgetSummaryResponse budget,
                               InvestmentSummaryResponse investment, List<NetWorthSnapshotResponse> history,
                               java.time.LocalDate asOf,String accountingBasis,String cumulativeAssetValuationChange,String knownAsset,List<NetWorthResult.Unconverted> unconverted) {
    static NetWorthResponse from(NetWorthResult value, List<NetWorthSnapshotResponse> history,java.time.LocalDate asOf) {
        return new NetWorthResponse(Money.formatCents(value.assetCents()), Money.formatCents(value.liabilityCents()),
                Money.formatCents(value.netWorthCents()), value.allocation().stream().map(AllocationResponse::from).toList(),
                value.debtRatioTenths()==null?null:BigDecimal.valueOf(value.debtRatioTenths(), 1).toPlainString(), BudgetSummaryResponse.from(value.budget()),
                InvestmentSummaryResponse.from(value.investment()), List.copyOf(history),asOf,"LEDGER_AS_OF",
                Money.formatCents(value.cumulativeAssetValuationChangeCents()),Money.formatCents(value.knownAssetCents()),value.unconverted());
    }
}
