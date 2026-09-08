package com.familyfinance.market;

import java.time.Instant;

public record SecurityCatalogStatus(String state, int count, Instant updatedAt, String error) {
}
