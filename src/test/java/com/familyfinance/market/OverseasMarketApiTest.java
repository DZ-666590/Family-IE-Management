package com.familyfinance.market;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyfinance.shared.ResourceNotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OverseasMarketApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean MarketDataClient client;

    @Test
    void rejectsAnonymousAndAuthenticatedNonMember() throws Exception {
        mvc.perform(get("/api/overseas-market/search").param("market", "US").param("q", "AAPL"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/overseas-market/search").with(user("outsider"))
                        .param("market", "US").param("q", "AAPL"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void overseasSearchAndCandlesNeverCreateAccountingRows() throws Exception {
        MockHttpSession owner = login();
        OverseasInstrument instrument = new OverseasInstrument(
                "00700", "腾讯控股", "HK", "USD", "HKEX", "Asia/Hong_Kong");
        when(client.overseasSearch("HK", "00700")).thenReturn(new OverseasSearchResponse(
                List.of(instrument), false, Instant.parse("2026-09-08T01:02:03Z"), false,
                "READY", null));
        long timestamp = LocalDate.of(2026, 9, 7).atStartOfDay(ZoneId.of("Asia/Hong_Kong"))
                .toInstant().toEpochMilli();
        when(client.overseasCandles("HK", "00700")).thenReturn(new OverseasCandleResponse(
                instrument, "00700", "SINA", "none", LocalDate.of(2026, 9, 7),
                Instant.parse("2026-09-08T01:02:03Z"), false, true,
                List.of(new CandleBar(timestamp, BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.ONE, BigDecimal.ONE, 10, null))));
        String[] protectedTables = {
                "securities", "investment_trades", "market_price_snapshots", "ledger_accounts",
                "ledger_journals", "ledger_entries", "ledger_sources", "accounting_commands"
        };
        Map<String, Integer> countsBefore = new LinkedHashMap<>();
        for (String table : protectedTables) countsBefore.put(table, count(table));

        mvc.perform(get("/api/overseas-market/search").session(owner)
                        .param("market", "hk").param("q", " 00700 "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].symbol").value("00700"))
                .andExpect(jsonPath("$.data.items[0].currency").value("USD"))
                .andExpect(jsonPath("$.data.items[0].timezone").value("Asia/Hong_Kong"))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-09-08T01:02:03Z"))
                .andExpect(jsonPath("$.data.state").value("READY"));
        mvc.perform(get("/api/overseas-market/candles").session(owner)
                        .param("market", "HK").param("symbol", "00700"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.symbol").value("00700"))
                .andExpect(jsonPath("$.data.bars[0].turnover").doesNotExist());

        for (var entry : countsBefore.entrySet()) {
            org.assertj.core.api.Assertions.assertThat(count(entry.getKey()))
                    .as(entry.getKey()).isEqualTo(entry.getValue());
        }
    }

    @Test
    void mapsUnknownSymbolTo404AndProviderFailureTo503() throws Exception {
        MockHttpSession owner = login();
        when(client.overseasCandles("US", "ZZZZ"))
                .thenThrow(new ResourceNotFoundException("海外证券不存在"));
        when(client.overseasCandles("US", "AAPL"))
                .thenThrow(new MarketProviderException("MARKET_UPSTREAM_UNAVAILABLE", true));

        mvc.perform(get("/api/overseas-market/candles").session(owner)
                        .param("market", "US").param("symbol", "ZZZZ"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get("/api/overseas-market/candles").session(owner)
                        .param("market", "US").param("symbol", "AAPL"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("MARKET_UPSTREAM_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.message").value("行情暂时不可用，请稍后重试"));
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private MockHttpSession login() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", "demo").param("password", "demo1234"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
