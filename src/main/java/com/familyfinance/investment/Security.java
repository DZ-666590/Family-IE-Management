package com.familyfinance.investment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "securities")
public class Security {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 2, updatable = false)
    private String market;

    @Column(name = "ts_code", nullable = false, length = 9, updatable = false)
    private String tsCode;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "security_type", nullable = false, length = 16, updatable = false)
    private String securityType;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "catalog_verified", nullable = false)
    private boolean catalogVerified;

    protected Security() {
    }

    public Security(String market, String tsCode, String name) {
        this.market = market;
        this.tsCode = tsCode;
        this.name = name;
        this.securityType = "STOCK";
        this.active = true;
        this.catalogVerified = true;
    }

    public Long getId() { return id; }
    public String getMarket() { return market; }
    /** Trading universe: A shares CNY, HKD Hong Kong counters, USD US stocks. */
    public String getCurrency() { return switch(market) {case "HK"->"HKD";case "US"->"USD";default->"CNY";}; }
    public String getTsCode() { return tsCode; }
    public String getName() { return name; }
    public String getSecurityType() { return securityType; }
    public boolean isActive() { return active; }
    public boolean isCatalogVerified() { return catalogVerified; }

    public void publishCatalog(String catalogName) {
        this.name = catalogName;
        this.active = true;
        this.catalogVerified = true;
    }

    public void retireFromCatalog() {
        this.catalogVerified = false;
    }
}
