package com.familyfinance.market;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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

        new SecurityCatalogService(client, importer, states, Clock.systemUTC()).scheduledRefresh();

        verify(importer, never()).publish(stale);
        verify(states).failed("MARKET_DIRECTORY_STALE");
    }

    @Test
    void failedFirstLoadRetriesQuicklyThenHealthyCatalogWaitsTwentyFourHours() {
        MarketDataClient client = mock(MarketDataClient.class);
        SecurityCatalogImporter importer = mock(SecurityCatalogImporter.class);
        SecurityCatalogStateStore states = mock(SecurityCatalogStateStore.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-09-08T08:00:00Z"));
        MarketDirectoryResponse fresh = new MarketDirectoryResponse(
                List.of(new MarketDirectoryItem("600000.SH", "浦发银行", "SH")),
                Instant.parse("2026-09-08T07:59:00Z"), false);
        when(client.enabled()).thenReturn(true);
        when(client.directory())
                .thenThrow(new MarketProviderException("MARKET_UPSTREAM_UNAVAILABLE", false))
                .thenReturn(fresh)
                .thenReturn(fresh);
        SecurityCatalogService service = new SecurityCatalogService(client, importer, states, clock);

        service.scheduledRefresh();
        clock.advance(Duration.ofSeconds(59));
        service.scheduledRefresh();
        verify(client).directory();
        verify(states).failed("MARKET_UPSTREAM_UNAVAILABLE");

        clock.advance(Duration.ofSeconds(1));
        service.scheduledRefresh();
        verify(client, org.mockito.Mockito.times(2)).directory();
        verify(importer).publish(fresh);

        clock.advance(Duration.ofHours(23).plusMinutes(59));
        service.scheduledRefresh();
        verify(client, org.mockito.Mockito.times(2)).directory();
        clock.advance(Duration.ofMinutes(1));
        service.scheduledRefresh();
        verify(client, org.mockito.Mockito.times(3)).directory();
        verify(importer, org.mockito.Mockito.times(2)).publish(fresh);
    }

    @Test
    void repeatedFailuresBackOffOneTwoFourThenAtMostFiveMinutes() {
        MarketDataClient client = mock(MarketDataClient.class);
        SecurityCatalogImporter importer = mock(SecurityCatalogImporter.class);
        SecurityCatalogStateStore states = mock(SecurityCatalogStateStore.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-09-08T08:00:00Z"));
        when(client.enabled()).thenReturn(true);
        when(client.directory()).thenThrow(
                new MarketProviderException("MARKET_UPSTREAM_UNAVAILABLE", false));
        SecurityCatalogService service = new SecurityCatalogService(client, importer, states, clock);

        service.scheduledRefresh();
        clock.advance(Duration.ofMinutes(1));
        service.scheduledRefresh();
        clock.advance(Duration.ofMinutes(2));
        service.scheduledRefresh();
        clock.advance(Duration.ofMinutes(4));
        service.scheduledRefresh();
        clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        service.scheduledRefresh();
        verify(client, org.mockito.Mockito.times(4)).directory();
        clock.advance(Duration.ofSeconds(1));
        service.scheduledRefresh();
        verify(client, org.mockito.Mockito.times(5)).directory();
        verify(importer, never()).publish(org.mockito.ArgumentMatchers.any());
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;
        private MutableClock(Instant initial) { current = new AtomicReference<>(initial); }
        private void advance(Duration duration) { current.updateAndGet(value -> value.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current.get(); }
    }
}
