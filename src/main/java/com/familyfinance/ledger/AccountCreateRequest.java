package com.familyfinance.ledger;

public record AccountCreateRequest(
        String name,
        AccountType type,
        String currency,
        String openingBalance,
        String openingOn) {
    public AccountCreateRequest(String name, AccountType type, String currency, String openingBalance) {
        this(name,type,currency,openingBalance,null);
    }
}
