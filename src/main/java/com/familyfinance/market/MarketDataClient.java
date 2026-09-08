package com.familyfinance.market;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class MarketDataClient {
    private final RestClient client;
    private final boolean enabled;

    public MarketDataClient(@Value("${MARKET_DATA_URL:}") String rawUrl) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        enabled = !url.isBlank();
        if (enabled && !(url.equals("http://127.0.0.1:8091") || url.equals("http://localhost:8091"))) {
            throw new IllegalArgumentException("MARKET_DATA_URL must target the loopback market adapter");
        }
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(2));
        requests.setReadTimeout(Duration.ofSeconds(25));
        client = enabled ? RestClient.builder().baseUrl(url).requestFactory(requests).build() : null;
    }

    public boolean enabled() {
        return enabled;
    }

    public MarketDirectoryResponse directory() {
        requireEnabled();
        return get("/directory", MarketDirectoryResponse.class);
    }

    public CandleResponse candles(String symbol, String adjustment) {
        requireEnabled();
        try {
            CandleResponse response = client.get().uri(builder -> builder.path("/candles")
                            .queryParam("symbol", symbol).queryParam("adjust", adjustment).build())
                    .retrieve().body(CandleResponse.class);
            if (response == null) throw invalid();
            return response;
        } catch (MarketProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MarketProviderException("MARKET_UPSTREAM_UNAVAILABLE", false);
        }
    }

    private <T> T get(String path, Class<T> type) {
        try {
            T response = client.get().uri(path).retrieve().body(type);
            if (response == null) throw invalid();
            return response;
        } catch (MarketProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MarketProviderException("MARKET_UPSTREAM_UNAVAILABLE", false);
        }
    }

    private void requireEnabled() {
        if (!enabled) throw new MarketProviderException("MARKET_DISABLED", false);
    }

    private static MarketProviderException invalid() {
        return new MarketProviderException("MARKET_UPSTREAM_INVALID", false);
    }
}
