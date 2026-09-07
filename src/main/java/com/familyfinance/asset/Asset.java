package com.familyfinance.asset;

import com.familyfinance.household.AppUser;
import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.Household;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

@Entity
@Table(name = "assets")
public class Asset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    private Household household;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false, length = 16, updatable = false)
    private AssetType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_member_id")
    private FamilyMember ownerMember;

    @Column(name = "acquired_on", updatable = false)
    private LocalDate acquiredOn;

    @Column(name = "purchase_value_cents", updatable = false)
    private Long purchaseValueCents;

    @Column(name = "current_value_cents", nullable = false)
    private Long currentValueCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AssetStatus status = AssetStatus.ACTIVE;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private AppUser createdBy;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Enumerated(EnumType.STRING) @Column(name="accounting_mode")
    private AssetAccountingMode accountingMode;
    @Column(name="accounting_on") private LocalDate accountingOn;
    @Column(name="initial_value_cents") private Long initialValueCents;
    @Column(name="funding_account_id") private Long fundingAccountId;
    @Column(name="last_accounting_on") private LocalDate lastAccountingOn;
    @Column(name="disposed_on") private LocalDate disposedOn;
    @Column(name="disposal_proceeds_cents") private Long disposalProceedsCents;
    @Column(name="disposal_cash_account_id") private Long disposalCashAccountId;
    @Column(name="disposed_by") private Long disposedBy;

    public AssetAccountingMode getAccountingMode(){return accountingMode;}
    public LocalDate getAccountingOn(){return accountingOn;}
    public Long getInitialValueCents(){return initialValueCents;}
    public Long getFundingAccountId(){return fundingAccountId;}
    public LocalDate getLastAccountingOn(){return lastAccountingOn;}
    public LocalDate getDisposedOn(){return disposedOn;}
    public Long getDisposalProceedsCents(){return disposalProceedsCents;}
    public Long getDisposalCashAccountId(){return disposalCashAccountId;}
    public Long getDisposedBy(){return disposedBy;}
    void initialize(AssetAccountingMode mode,LocalDate day,long initial,Long funding) {
        accountingMode=mode;accountingOn=day;initialValueCents=initial;fundingAccountId=funding;lastAccountingOn=day;
    }
    void valuedOn(LocalDate day){lastAccountingOn=day;}
    void dispose(LocalDate day,long proceeds,Long cash,long actor,Instant now) {
        disposedOn=day;disposalProceedsCents=proceeds;disposalCashAccountId=cash;disposedBy=actor;
        lastAccountingOn=day;currentValueCents=0L;archive(now);
    }

    @OneToOne(mappedBy = "asset", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private PropertyAsset property;

    @OneToOne(mappedBy = "asset", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private VehicleAsset vehicle;

    protected Asset() {
    }

    Asset(
            Household household,
            String name,
            AssetType type,
            FamilyMember ownerMember,
            LocalDate acquiredOn,
            Long purchaseValueCents,
            long currentValueCents,
            AppUser createdBy) {
        this.household = Objects.requireNonNull(household, "household must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.ownerMember = ownerMember;
        this.acquiredOn = acquiredOn;
        this.purchaseValueCents = purchaseValueCents;
        this.currentValueCents = currentValueCents;
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy must not be null");
    }

    public Long getId() { return id; }
    public Household getHousehold() { return household; }
    public String getName() { return name; }
    public AssetType getType() { return type; }
    public FamilyMember getOwnerMember() { return ownerMember; }
    public LocalDate getAcquiredOn() { return acquiredOn; }
    public Long getPurchaseValueCents() { return purchaseValueCents; }
    public Long getCurrentValueCents() { return currentValueCents; }
    public AssetStatus getStatus() { return status; }
    public AppUser getCreatedBy() { return createdBy; }
    public Instant getArchivedAt() { return archivedAt; }
    public PropertyAsset getProperty() { return property; }
    public VehicleAsset getVehicle() { return vehicle; }
    public boolean isArchived() { return status == AssetStatus.ARCHIVED; }

    void attachProperty(PropertyAsset property) {
        this.property = Objects.requireNonNull(property, "property must not be null");
    }

    void attachVehicle(VehicleAsset vehicle) {
        this.vehicle = Objects.requireNonNull(vehicle, "vehicle must not be null");
    }

    void updateProfile(String name, FamilyMember ownerMember) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.ownerMember = ownerMember;
    }

    void updateCurrentValue(long currentValueCents) {
        this.currentValueCents = currentValueCents;
    }

    void archive(Instant archivedAt) {
        if (!isArchived()) {
            this.status = AssetStatus.ARCHIVED;
            this.archivedAt = Objects.requireNonNull(archivedAt, "archivedAt must not be null");
        }
    }
}
