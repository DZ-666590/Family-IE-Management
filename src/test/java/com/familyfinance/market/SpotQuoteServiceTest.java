package com.familyfinance.market;

import com.familyfinance.investment.Security;
import com.familyfinance.investment.SecurityRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpotQuoteServiceTest {
    final Instant now=Instant.parse("2026-09-09T02:00:00Z");
    @Test void validatesIdentityAndKeepsSourceTimeSeparateFromFetchTime() {
        var repo=mock(SecurityRepository.class);var client=mock(MarketDataClient.class);
        var stock=new Security("SH","600519.SH","样例");ReflectionTestUtils.setField(stock,"id",1L);
        when(repo.findAllById(List.of(1L))).thenReturn(List.of(stock));
        when(client.spot("CN",List.of("600519.SH"))).thenReturn(new SpotBatchResponse(List.of(quote("600519.SH","CNY")),"TRADING_HOURS",60));
        var service=new SpotQuoteService(repo,client,Clock.fixed(now,ZoneOffset.UTC));
        var result=service.fetch(List.of(1L));
        assertThat(result.quotes()).hasSize(1);
        assertThat(result.quotes().get(0).price()).isEqualTo("100.123456");
        assertThat(result.quotes().get(0).quotedAt()).isEqualTo(now.minusSeconds(30));
        assertThat(result.prices().get(1L).source()).isEqualTo(QuoteSource.TENCENT);
    }
    @Test void invalidProviderCurrencyCannotBecomeAValuationPrice() {
        var repo=mock(SecurityRepository.class);var client=mock(MarketDataClient.class);
        var stock=new Security("SH","600519.SH","样例");ReflectionTestUtils.setField(stock,"id",1L);
        when(repo.findAllById(List.of(1L))).thenReturn(List.of(stock));
        when(client.spot("CN",List.of("600519.SH"))).thenReturn(new SpotBatchResponse(List.of(quote("600519.SH","USD")),"TRADING_HOURS",60));
        assertThat(new SpotQuoteService(repo,client,Clock.fixed(now,ZoneOffset.UTC)).fetch(List.of(1L)).prices()).isEmpty();
    }
    SpotQuote quote(String symbol,String currency){return new SpotQuote(symbol,"CN",currency,new BigDecimal("100.123456"),now.minusSeconds(30),now,"TENCENT_PUBLIC",null,"OK",30L);}
}
