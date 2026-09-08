package com.familyfinance.accounting;

import static org.assertj.core.api.Assertions.*;
import static com.familyfinance.accounting.LedgerAccountKind.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class LedgerCurrencyTest {
    final LedgerValidation validation = new LedgerValidation(null,
            Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC), null);

    LedgerPostingCommand command(List<LedgerEntryInput> entries) {
        return new LedgerPostingCommand(1, "TEST", 1, "key", LocalDate.of(2026, 9, 7), 1, entries);
    }
    LedgerEntryInput entry(String currency, boolean debit) {
        return new LedgerEntryInput("EQUITY:OPENING", EQUITY,
                debit ? BigDecimal.ONE : BigDecimal.ZERO,
                debit ? BigDecimal.ZERO : BigDecimal.ONE, null, null, currency);
    }

    @Test void differentCurrenciesCannotCancelEachOther() {
        assertThatThrownBy(() -> validation.command(command(List.of(entry("USD", true), entry("CNY", false)))))
                .isInstanceOf(com.familyfinance.shared.RequestValidationException.class);
    }
    @Test void eachCurrencyCanBalanceIndependentlyWithinOneCommand() {
        assertThatCode(() -> validation.command(command(List.of(entry("USD", true), entry("USD", false),
                entry("HKD", true), entry("HKD", false))))).doesNotThrowAnyException();
    }
    @Test void foreignCurrencyIsPartOfIdempotencyIdentity() {
        assertThat(LedgerRequestDigest.of("POST", command(List.of(entry("USD", true), entry("USD", false)))))
                .isNotEqualTo(LedgerRequestDigest.of("POST", command(List.of(entry("HKD", true), entry("HKD", false)))));
    }
    @Test void legacyConstructorsKeepCnyRequestIdentity() {
        var legacy = new LedgerEntryInput("EQUITY:OPENING", EQUITY, 100, 0, null, null);
        assertThat(legacy.currency()).isEqualTo("CNY");
        assertThat(LedgerRequestDigest.of("POST", command(List.of(legacy))))
                .isEqualTo(LedgerRequestDigest.of("POST", command(List.of(entry("CNY", true)))));
    }
    @Test void unknownOrMissingCurrencyCannotReachPosting() {
        for (String currency : new String[]{"EUR", "usd", "", null}) {
            assertThatThrownBy(() -> entry(currency, true)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
