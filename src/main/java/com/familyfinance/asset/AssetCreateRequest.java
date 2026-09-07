package com.familyfinance.asset;

import java.time.LocalDate;

public record AssetCreateRequest(
        String name,
        AssetType type,
        Long ownerMemberId,
        LocalDate acquiredOn,
        String purchaseValue,
        String currentValue,
        PropertyAssetRequest property,
        VehicleAssetRequest vehicle, AssetAccountingMode accountingMode, LocalDate accountingOn, Long fundingAccountId) {
    public AssetCreateRequest(String name,AssetType type,Long ownerMemberId,LocalDate acquiredOn,String purchaseValue,String currentValue,PropertyAssetRequest property,VehicleAssetRequest vehicle) {
        this(name,type,ownerMemberId,acquiredOn,purchaseValue,currentValue,property,vehicle,null,null,null);
    }
}
