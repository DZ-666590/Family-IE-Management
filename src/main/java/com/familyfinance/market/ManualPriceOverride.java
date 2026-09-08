package com.familyfinance.market;

import com.familyfinance.household.AppUser;
import com.familyfinance.household.Household;
import com.familyfinance.investment.Security;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Objects;

@Entity
@Table(name = "manual_price_overrides")
public class ManualPriceOverride {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "household_id") private Household household;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "security_id") private Security security;
    @Column(name = "price_cents", nullable = false) private long priceCents;
    @Column(name="unit_price",precision=25,scale=6) private java.math.BigDecimal unitPrice;
    @Column(name = "effective_on", nullable = false) private LocalDate effectiveOn;
    @Column(length = 500) private String note;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "created_by") private AppUser createdBy;
    protected ManualPriceOverride() { }
    ManualPriceOverride(Household household, Security security, long priceCents, LocalDate effectiveOn, String note, AppUser createdBy) {
        this(household,security,java.math.BigDecimal.valueOf(priceCents,2),effectiveOn,note,createdBy);
    }
    ManualPriceOverride(Household household,Security security,java.math.BigDecimal unitPrice,LocalDate effectiveOn,String note,AppUser createdBy){
        this.household = Objects.requireNonNull(household); this.security = Objects.requireNonNull(security);
        this.unitPrice=unitPrice;this.priceCents=unitPrice.movePointRight(2).setScale(0,java.math.RoundingMode.HALF_UP).longValueExact(); this.effectiveOn = Objects.requireNonNull(effectiveOn); this.note = note;
        this.createdBy = Objects.requireNonNull(createdBy);
    }
    void replace(long priceCents, String note) { replace(java.math.BigDecimal.valueOf(priceCents,2),note); }
    void replace(java.math.BigDecimal price,String note){unitPrice=price;priceCents=price.movePointRight(2).setScale(0,java.math.RoundingMode.HALF_UP).longValueExact();this.note=note;}
    public LocalDate getEffectiveOn() { return effectiveOn; }
    public long getPriceCents() { return priceCents; }
    public java.math.BigDecimal getUnitPrice(){return unitPrice==null?java.math.BigDecimal.valueOf(priceCents,2):unitPrice;}
    public String getNote() { return note; }
}
