package com.familyfinance.asset;

import com.familyfinance.shared.Money;
import java.time.Instant;
import java.time.LocalDate;

public record AssetResponse(
        long id,
        String name,
        AssetType type,
        Long ownerMemberId,
        LocalDate acquiredOn,
        String purchaseValue,
        String currentValue,
        AssetStatus status,
        long createdBy,
        Instant archivedAt,
        PropertyAssetResponse property,
        VehicleAssetResponse vehicle, AssetAccountingMode accountingMode, LocalDate accountingOn,
        String initialValue, Long fundingAccountId, LocalDate lastAccountingOn,
        LocalDate disposedOn, String disposalProceeds, Long disposalCashAccountId, Long disposedBy,String disposalBookGain) {

    static AssetResponse from(Asset asset,Long disposalBookGainCents) {
        return new AssetResponse(
                asset.getId(),
                asset.getName(),
                asset.getType(),
                asset.getOwnerMember() == null ? null : asset.getOwnerMember().getId(),
                asset.getAcquiredOn(),
                asset.getPurchaseValueCents() == null ? null : Money.formatCents(asset.getPurchaseValueCents()),
                Money.formatCents(asset.getCurrentValueCents()),
                asset.getStatus(),
                asset.getCreatedBy().getId(),
                asset.getArchivedAt(),
                asset.getProperty() == null ? null : PropertyAssetResponse.from(asset.getProperty()),
                asset.getVehicle() == null ? null : VehicleAssetResponse.from(asset.getVehicle()),
                asset.getAccountingMode(),asset.getAccountingOn(),asset.getInitialValueCents()==null?null:Money.formatCents(asset.getInitialValueCents()),
                asset.getFundingAccountId(),asset.getLastAccountingOn(),asset.getDisposedOn(),
                asset.getDisposalProceedsCents()==null?null:Money.formatCents(asset.getDisposalProceedsCents()),asset.getDisposalCashAccountId(),asset.getDisposedBy(),
                disposalBookGainCents==null?null:Money.formatCents(disposalBookGainCents));
    }
}
