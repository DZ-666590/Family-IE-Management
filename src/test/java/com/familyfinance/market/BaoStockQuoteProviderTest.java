package com.familyfinance.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BaoStockQuoteProviderTest {
    @Test
    void projectsLatestUnadjustedBarWithActualBaoStockSource() {
        MarketDataClient client = mock(MarketDataClient.class);
        when(client.enabled()).thenReturn(true);
        long first = LocalDate.of(2026, 9, 7).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        long second = LocalDate.of(2026, 9, 8).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        when(client.candles("000001.SZ", "none")).thenReturn(new CandleResponse(
                "000001.SZ", "BAOSTOCK", "none", LocalDate.of(2026, 9, 8), Instant.now(), false, true,
                List.of(
                        bar(first, "10.00", "10.20", "9.90", "10.10"),
                        bar(second, "10.10", "10.50", "10.00", "10.40"))));

        DailyQuote quote = new BaoStockQuoteProvider(client).fetchDaily(Set.of("000001.SZ")).get(0);

        assertThat(quote.source()).isEqualTo(QuoteSource.BAOSTOCK);
        assertThat(quote.tradeDate()).isEqualTo(LocalDate.of(2026, 9, 8));
        assertThat(quote.closeCents()).isEqualTo(1040);
        assertThat(quote.preCloseCents()).isEqualTo(1010);
        assertThat(quote.pctChange()).isEqualByComparingTo("2.9703");
    }

    private static CandleBar bar(long timestamp, String open, String high, String low, String close) {
        return new CandleBar(timestamp, new BigDecimal(open), new BigDecimal(high), new BigDecimal(low),
                new BigDecimal(close), 100, new BigDecimal("1000.00"));
    }
}
