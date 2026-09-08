package com.familyfinance.market;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecurityCatalogServiceTest {
    @Test
    void staleDirectoryKeepsPublishedReferencesAndMarksStatusError() {
        MarketDataClient client = mock(MarketDataClient.class);
        SecurityCatalogImporter importer = mock(SecurityCatalogImporter.class);
        SecurityCatalogStateStore states = mock(SecurityCatalogStateStore.class);
        MarketDirectoryResponse stale = new MarketDirectoryResponse(
                List.of(new MarketDirectoryItem("600000.SH", "浦发银行", "SH")),
                Instant.parse("2026-09-07T08:00:00Z"), true);
        when(client.enabled()).thenReturn(true);
        when(client.directory()).thenReturn(stale);

        new SecurityCatalogService(client, importer, states).scheduledRefresh();

        verify(importer, never()).publish(stale);
        verify(states).failed("MARKET_DIRECTORY_STALE");
    }
}
