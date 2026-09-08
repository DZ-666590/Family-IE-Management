package com.familyfinance.market;

import com.familyfinance.shared.ResourceNotFoundException;
import java.time.Duration;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.util.UriBuilder;

@Component
public class MarketDataClient {
    private final RestClient client;
    private final boolean enabled;

    @Autowired
    public MarketDataClient(@Value("${MARKET_DATA_URL:}") String rawUrl) {
        this(rawUrl, configuredBuilder());
    }

    MarketDataClient(String rawUrl, RestClient.Builder builder) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        enabled = !url.isBlank();
        if (enabled && !(url.equals("http://127.0.0.1:8091") || url.equals("http://localhost:8091"))) {
            throw new IllegalArgumentException("MARKET_DATA_URL must target the loopback market adapter");
        }
        client = enabled ? builder.baseUrl(url).build() : null;
    }

    private static RestClient.Builder configuredBuilder() {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(2));
        requests.setReadTimeout(Duration.ofSeconds(25));
        return RestClient.builder().requestFactory(requests);
    }

    public boolean enabled() {
        return enabled;
    }

    public MarketDirectoryResponse directory() {
        requireEnabled();
        return get("/directory", MarketDirectoryResponse.class);
    }

    public OverseasSearchResponse overseasSearch(String market, String query) {
        requireEnabled();
        return overseasGet("/overseas/search", builder -> builder
                .queryParam("market", market).queryParam("q", query), OverseasSearchResponse.class);
    }

    public OverseasCandleResponse overseasCandles(String market, String symbol) {
        requireEnabled();
        return overseasGet("/overseas/candles", builder -> builder
                .queryParam("market", market).queryParam("symbol", symbol), OverseasCandleResponse.class);
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

    private <T> T overseasGet(
            String path,
            UnaryOperator<UriBuilder> query,
            Class<T> type) {
        try {
            T response = client.get().uri(builder -> query.apply(builder.path(path)).build())
                    .retrieve().body(type);
            if (response == null) throw invalid();
            return response;
        } catch (ResourceNotFoundException | MarketProviderException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new ResourceNotFoundException("海外证券不存在");
            }
            throw new MarketProviderException("MARKET_UPSTREAM_UNAVAILABLE", true);
        } catch (ResourceAccessException exception) {
            throw new MarketProviderException("MARKET_UPSTREAM_UNAVAILABLE", true);
        } catch (RestClientException exception) {
            throw invalid();
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private void requireEnabled() {
        if (!enabled) throw new MarketProviderException("MARKET_DISABLED", false);
    }

    private static MarketProviderException invalid() {
        return new MarketProviderException("MARKET_UPSTREAM_INVALID", false);
    }
}
