package com.familyfinance.ledger;

import com.familyfinance.shared.Money;
import java.time.Instant;

public record AccountResponse(
        Long id,
        String name,
        AccountType type,
        String currency,
        String openingBalance,
        Instant archivedAt,
        boolean openingConfirmed,
        java.time.LocalDate openingOn,
        String balance,
        String availableBalance) {

    static AccountResponse from(FinancialAccount account, long balance) {
        return new AccountResponse(
                account.getId(),
                account.getName(),
                account.getType(),
                account.getCurrency(),
                Money.formatCents(account.getOpeningBalanceCents()),
                account.getArchivedAt(),
                account.isOpeningConfirmed(),
                account.getOpeningOn(),
                account.isOpeningConfirmed()?Money.formatCents(balance):null,
                account.isOpeningConfirmed()?Money.formatCents(balance):null);
    }
}
