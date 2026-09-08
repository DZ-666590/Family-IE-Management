package com.familyfinance.market;

import java.time.Instant;
import java.util.List;

public record MarketDirectoryResponse(List<MarketDirectoryItem> items, Instant fetchedAt, boolean stale) {
}
