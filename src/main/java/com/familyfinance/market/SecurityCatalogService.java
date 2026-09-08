package com.familyfinance.market;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SecurityCatalogService {
    private final MarketDataClient client;
    private final SecurityCatalogImporter importer;
    private final SecurityCatalogStateStore states;

    public SecurityCatalogService(
            MarketDataClient client, SecurityCatalogImporter importer, SecurityCatalogStateStore states) {
        this.client = client;
        this.importer = importer;
        this.states = states;
    }

    public SecurityCatalogStatus status() {
        return states.status(client.enabled());
    }

    @Scheduled(initialDelayString = "${app.market.catalog-initial-delay:PT30S}",
            fixedDelayString = "${app.market.catalog-refresh-delay:PT24H}")
    public void scheduledRefresh() {
        if (!client.enabled()) return;
        try {
            MarketDirectoryResponse response = client.directory();
            if (response.stale()) {
                states.failed("MARKET_DIRECTORY_STALE");
                return;
            }
            importer.publish(response);
        } catch (MarketProviderException exception) {
            states.failed(exception.code());
        } catch (RuntimeException exception) {
            states.failed("MARKET_UPSTREAM_UNAVAILABLE");
        }
    }
}
