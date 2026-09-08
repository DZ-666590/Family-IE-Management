package com.familyfinance.ledger;

public record AccountPatchRequest(
        String name,
        AccountType type,
        String currency,
        String openingBalance,
        String openingOn,
        WalletProvider walletProvider,
        String bankName,
        String cardLastFour) {
    public AccountPatchRequest(String name, AccountType type, String currency, String openingBalance, String openingOn) {
        this(name, type, currency, openingBalance, openingOn, null, null, null);
    }
    public AccountPatchRequest(String name, AccountType type, String currency, String openingBalance) {
        this(name,type,currency,openingBalance,null);
    }
}
