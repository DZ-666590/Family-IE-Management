package com.familyfinance.market;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SecurityCatalogService {
    private final MarketDataClient client;
    private final SecurityCatalogImporter importer;
    private final SecurityCatalogStateStore states;
    private final Clock clock;
    private final AtomicReference<Instant> nextAttempt = new AtomicReference<>(Instant.MIN);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicBoolean refreshing = new AtomicBoolean();

    public SecurityCatalogService(
            MarketDataClient client, SecurityCatalogImporter importer, SecurityCatalogStateStore states,
            Clock clock) {
        this.client = client;
        this.importer = importer;
        this.states = states;
        this.clock = clock;
    }

    public SecurityCatalogStatus status() {
        return states.status(client.enabled());
    }

    @Scheduled(initialDelayString = "${app.market.catalog-initial-delay:PT30S}",
            fixedDelayString = "${app.market.catalog-poll-delay:PT1M}")
    public void scheduledRefresh() {
        if (!client.enabled()) return;
        Instant now = clock.instant();
        if (now.isBefore(nextAttempt.get()) || !refreshing.compareAndSet(false, true)) return;
        try {
            MarketDirectoryResponse response = client.directory();
            if (response.stale()) {
                failed("MARKET_DIRECTORY_STALE", now);
                return;
            }
            importer.publish(response);
            consecutiveFailures.set(0);
            nextAttempt.set(now.plus(Duration.ofHours(24)));
        } catch (MarketProviderException exception) {
            failed(exception.code(), now);
        } catch (RuntimeException exception) {
            failed("MARKET_UPSTREAM_UNAVAILABLE", now);
        } finally {
            refreshing.set(false);
        }
    }

    private void failed(String error, Instant now) {
        int failure = consecutiveFailures.incrementAndGet();
        long[] delays = {1, 2, 4, 5};
        nextAttempt.set(now.plus(Duration.ofMinutes(delays[Math.min(failure - 1, delays.length - 1)])));
        states.failed(error);
    }
}
