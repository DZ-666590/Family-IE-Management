package com.familyfinance.ledger;

public record AccountPatchRequest(
        String name,
        AccountType type,
        String currency,
        String openingBalance,
        String openingOn) {
    public AccountPatchRequest(String name, AccountType type, String currency, String openingBalance) {
        this(name,type,currency,openingBalance,null);
    }
}
