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

    @Column(name = "ts_code", nullable = false, length = 64, updatable = false)
    private String tsCode;
    @Column(name="symbol",length=16,updatable=false) private String symbol;
    @Column(name="exchange_name",length=40,updatable=false) private String exchange;
    @Column(name="currency",length=3,nullable=false,updatable=false) private String currency="CNY";
    @Column(name="timezone",length=40,updatable=false) private String timezone;

    @Column(nullable = false, length = 200)
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
    public String getCurrency() { return currency; }
    public String getSymbol(){return symbol==null?tsCode.substring(0,tsCode.indexOf('.')):symbol;}
    public String getExchange(){return exchange==null?market:exchange;}
    public String getTimezone(){return timezone==null?"Asia/Shanghai":timezone;}
    public static Security overseas(com.familyfinance.market.OverseasInstrument instrument){
        String code=instrument.symbol()+(instrument.market().equals("HK")?".HK":"."+instrument.exchange().replace(' ','_')+".US");
        Security result=new Security(instrument.market(),code,instrument.name());
        result.symbol=instrument.symbol();result.exchange=instrument.exchange();result.currency=instrument.currency();result.timezone=instrument.timezone();return result;
    }
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
