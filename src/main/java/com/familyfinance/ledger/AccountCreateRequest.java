package com.familyfinance.ledger;

public record AccountCreateRequest(
        String name,
        AccountType type,
        String currency,
        String openingBalance,
        String openingOn,
        WalletProvider walletProvider,
        String bankName,
        String cardLastFour) {
    public AccountCreateRequest(String name, AccountType type, String currency, String openingBalance, String openingOn) {
        this(name, type, currency, openingBalance, openingOn, null, null, null);
    }
    public AccountCreateRequest(String name, AccountType type, String currency, String openingBalance) {
        this(name,type,currency,openingBalance,null);
    }
}
