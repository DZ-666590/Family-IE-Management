package com.familyfinance.reporting;

import com.familyfinance.shared.Money;
import java.time.LocalDate;

public record NetWorthSnapshotResponse(LocalDate snapshotOn, String asset, String liability, String netWorth,
        String accountingBasis,boolean valuationEstimated,int unpricedPositions) {
    static NetWorthSnapshotResponse from(NetWorthSnapshot value) {
        return new NetWorthSnapshotResponse(value.getSnapshotOn(), Money.formatCents(value.getAssetCents()),
                Money.formatCents(value.getLiabilityCents()), Money.formatCents(value.getNetWorthCents()),value.getAccountingBasis(),value.isValuationEstimated(),value.getUnpricedPositions());
    }
}
