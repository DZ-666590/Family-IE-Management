package com.familyfinance.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.familyfinance.shared.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MarketDataClientOverseasTest {
    private MockRestServiceServer server;
    private MarketDataClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MarketDataClient("http://127.0.0.1:8091", builder);
    }

    @Test
    void buildsBoundedSearchRequestAndDeserializesTypedResponse() {
        server.expect(requestTo("http://127.0.0.1:8091/overseas/search?market=US&q=A%20B"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"items":[],"hasNext":false,"updatedAt":null,"stale":false,
                         "state":"SYNCING","error":null}
                        """, MediaType.APPLICATION_JSON));

        OverseasSearchResponse response = client.overseasSearch("US", "A B");

        assertThat(response.state()).isEqualTo("SYNCING");
        server.verify();
    }

    @Test
    void distinguishesMissingInstrumentFromProviderFailure() {
        server.expect(requestTo("http://127.0.0.1:8091/overseas/candles?market=US&symbol=ZZZZ"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> client.overseasCandles("US", "ZZZZ"))
                .isInstanceOf(ResourceNotFoundException.class);
        server.verify();

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MarketDataClient("http://127.0.0.1:8091", builder);
        server.expect(requestTo("http://127.0.0.1:8091/overseas/candles?market=US&symbol=AAPL"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> client.overseasCandles("US", "AAPL"))
                .isInstanceOf(MarketProviderException.class)
                .hasMessage("MARKET_UPSTREAM_UNAVAILABLE");
        server.verify();
    }

    @Test
    void classifiesMalformedJsonAsInvalidProviderPayload() {
        server.expect(requestTo("http://127.0.0.1:8091/overseas/search?market=US&q=AAPL"))
                .andRespond(withSuccess("{not-json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.overseasSearch("US", "AAPL"))
                .isInstanceOf(MarketProviderException.class)
                .hasMessage("MARKET_UPSTREAM_INVALID");
        server.verify();
    }
}
