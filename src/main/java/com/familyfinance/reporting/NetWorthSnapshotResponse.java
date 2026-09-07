package com.familyfinance.reporting;

import com.familyfinance.shared.Money;
import java.time.LocalDate;

public record NetWorthSnapshotResponse(LocalDate snapshotOn, String asset, String liability, String netWorth,
        String accountingBasis,boolean valuationEstimated,int unpricedPositions) {
    static NetWorthSnapshotResponse from(LocalDate day, NetWorthResult value) {
        return new NetWorthSnapshotResponse(day, Money.formatCents(value.assetCents()),
                Money.formatCents(value.liabilityCents()), Money.formatCents(value.netWorthCents()),
                "LEDGER_AS_OF",value.investment().missingPrice(),value.investment().unpricedPositionCount());
    }
}
