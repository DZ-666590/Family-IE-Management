package com.familyfinance.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.familyfinance.investment.SecurityService;
import com.familyfinance.shared.ResourceNotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.core.Authentication;

class OverseasMarketServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");

    private final SecurityService security = mock(SecurityService.class);
    private final MarketDataClient client = mock(MarketDataClient.class);
    private final Authentication authentication = mock(Authentication.class);
    private final OverseasMarketService service = new OverseasMarketService(
            security, client, Clock.fixed(NOW, ZoneOffset.UTC));

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "US,2026-09-09T02:47:00Z,2026-09-08,true",
        "US,2026-09-08T21:29:59Z,2026-09-08,false",
        "US,2026-09-08T21:30:00Z,2026-09-08,true",
        "US,2026-01-06T22:29:59Z,2026-01-06,false",
        "US,2026-01-06T22:30:00Z,2026-01-06,true",
        "HK,2026-09-08T09:30:00Z,2026-09-08,true",
        "HK,2026-09-08T09:29:59Z,2026-09-08,false",
        "US,2026-09-12T23:00:00Z,2026-09-12,false",
        "US,2026-09-09T02:47:00Z,2026-09-09,false"
    })
    void acceptsOnlyPublishedSessionsAcrossMarketTimezones(String market, Instant now, LocalDate day, boolean accepted) {
        String symbol = market.equals("US") ? "BABA" : "09988";
        String zone = market.equals("US") ? "America/New_York" : "Asia/Hong_Kong";
        var instrument = new OverseasInstrument(symbol, "Alibaba", market,
                market.equals("US") ? "USD" : "HKD", market.equals("US") ? "NYSE" : "HKEX", zone);
        var response = new OverseasCandleResponse(instrument, symbol, "SINA", "none", day, now, false, true,
                List.of(new CandleBar(day.atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli(),
                        new BigDecimal("112.56"), new BigDecimal("113.115"), new BigDecimal("111.2683"),
                        new BigDecimal("112.66"), 6958655, null)));
        when(client.overseasCandles(market, symbol)).thenReturn(response);
        var subject = new OverseasMarketService(security, client, Clock.fixed(now, ZoneOffset.UTC));
        if (accepted) assertThat(subject.candles(authentication, market, symbol).bars().get(0).close())
                .isEqualByComparingTo("112.66");
        else assertThatThrownBy(() -> subject.candles(authentication, market, symbol))
                .isInstanceOf(MarketProviderException.class);
    }

    @Test
    void rejectsMalformedRequestBeforeCallingProvider() {
        assertThatThrownBy(() -> service.search(authentication, "CN", "AAPL"))
                .isInstanceOf(MarketValidationException.class);
        assertThatThrownBy(() -> service.search(authentication, "US", "x".repeat(81)))
                .isInstanceOf(MarketValidationException.class);
        assertThatThrownBy(() -> service.candles(authentication, "HK", "700"))
                .isInstanceOf(MarketValidationException.class);

        verify(client, never()).overseasSearch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(client, never()).overseasCandles(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void acceptsNullTurnoverAndUsesMarketTimezoneForAsOfValidation() {
        OverseasCandleResponse response = validCandles();
        when(client.overseasCandles("US", "AAPL")).thenReturn(response);

        service.candles(authentication, "us", "aapl");

        verify(client).overseasCandles("US", "AAPL");
    }

    @Test
    void normalizesOfficialHkexRmbTradingCurrencyAliasToIsoCny() {
        OverseasInstrument raw = new OverseasInstrument(
                "80700", "腾讯控股-R", "HK", "RMB", "HKEX", "Asia/Hong_Kong");
        OverseasSearchResponse search = new OverseasSearchResponse(
                List.of(raw), false, Instant.parse("2026-09-08T01:02:03Z"), false, "READY", null);
        when(client.overseasSearch("HK", "80700")).thenReturn(search);
        long timestamp = ZonedDateTime.of(2026, 9, 7, 0, 0, 0, 0, ZoneId.of("Asia/Hong_Kong"))
                .toInstant().toEpochMilli();
        CandleBar rawBar = new CandleBar(
                timestamp, new BigDecimal("801.25"), new BigDecimal("805.50"),
                new BigDecimal("798.00"), new BigDecimal("803.00"), 1234,
                new BigDecimal("991234.56"));
        OverseasCandleResponse candles = new OverseasCandleResponse(
                raw, "80700", "SINA", "none", LocalDate.of(2026, 9, 7),
                Instant.parse("2026-09-08T01:02:03Z"), false, true,
                List.of(rawBar));
        when(client.overseasCandles("HK", "80700")).thenReturn(candles);

        assertThat(service.search(authentication, "HK", "80700").items().get(0).currency()).isEqualTo("CNY");
        OverseasCandleResponse normalized = service.candles(authentication, "HK", "80700");
        assertThat(normalized.instrument().currency()).isEqualTo("CNY");
        assertThat(normalized.bars().get(0).open()).isEqualByComparingTo("801.25");
        assertThat(normalized.bars().get(0).high()).isEqualByComparingTo("805.50");
        assertThat(normalized.bars().get(0).low()).isEqualByComparingTo("798.00");
        assertThat(normalized.bars().get(0).close()).isEqualByComparingTo("803.00");
        assertThat(normalized.bars().get(0).turnover()).isEqualByComparingTo("991234.56");
    }

    @ParameterizedTest(name = "rejects {0} candle relative to provider fetch day")
    @MethodSource("unfinishedCandleDates")
    void rejectsCurrentAndFutureMarketDayCandles(String reason, LocalDate candleDate) {
        ZoneId zone = ZoneId.of("America/New_York");
        long timestamp = candleDate.atStartOfDay(zone).toInstant().toEpochMilli();
        OverseasCandleResponse response = new OverseasCandleResponse(
                instrument("US", "AAPL", "USD", "America/New_York"), "AAPL", "SINA", "none",
                candleDate, NOW, false, true,
                List.of(new CandleBar(timestamp, BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.ONE, BigDecimal.ONE, 1, null)));
        when(client.overseasCandles("US", "AAPL")).thenReturn(response);

        assertThatThrownBy(() -> service.candles(authentication, "US", "AAPL"))
                .isInstanceOf(MarketProviderException.class)
                .hasMessage("MARKET_UPSTREAM_INVALID");
    }

    @Test
    void rejectsProviderFetchTimestampBeyondClockSkewAllowance() {
        OverseasCandleResponse valid = validCandles();
        OverseasCandleResponse futureFetched = new OverseasCandleResponse(
                valid.instrument(), valid.symbol(), valid.source(), valid.adjustment(), valid.asOf(),
                NOW.plusSeconds(301), valid.stale(), valid.supported(), valid.bars());
        when(client.overseasCandles("US", "AAPL")).thenReturn(futureFetched);

        assertThatThrownBy(() -> service.candles(authentication, "US", "AAPL"))
                .isInstanceOf(MarketProviderException.class)
                .hasMessage("MARKET_UPSTREAM_INVALID");
    }

    @ParameterizedTest(name = "rejects malformed provider candles: {0}")
    @MethodSource("invalidCandleResponses")
    void rejectsMalformedProviderCandleResponses(String reason, OverseasCandleResponse response) {
        when(client.overseasCandles("US", "AAPL")).thenReturn(response);

        assertThatThrownBy(() -> service.candles(authentication, "US", "AAPL"))
                .isInstanceOf(MarketProviderException.class)
                .hasMessage("MARKET_UPSTREAM_INVALID");
    }

    @Test
    void preservesProviderNotFoundInsteadOfConvertingItToUnavailable() {
        when(client.overseasCandles("US", "ZZZZ")).thenThrow(new ResourceNotFoundException("海外证券不存在"));

        assertThatThrownBy(() -> service.candles(authentication, "US", "ZZZZ"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void rejectsUntrustedSearchMetadataAndOversizedResults() {
        OverseasInstrument wrongMarket = instrument("HK", "00700", "HKD", "Asia/Hong_Kong");
        when(client.overseasSearch("US", "A"))
                .thenReturn(new OverseasSearchResponse(
                        List.of(wrongMarket), false, Instant.parse("2026-09-08T01:02:03Z"),
                        false, "READY", null));

        assertThatThrownBy(() -> service.search(authentication, "US", "A"))
                .isInstanceOf(MarketProviderException.class)
                .hasMessage("MARKET_UPSTREAM_INVALID");

        OverseasInstrument apple = instrument("US", "AAPL", "USD", "America/New_York");
        when(client.overseasSearch("US", ""))
                .thenReturn(new OverseasSearchResponse(
                        java.util.Collections.nCopies(21, apple), true,
                        Instant.parse("2026-09-08T01:02:03Z"), false, "READY", null));
        assertThatThrownBy(() -> service.search(authentication, "US", ""))
                .isInstanceOf(MarketProviderException.class)
                .hasMessage("MARKET_UPSTREAM_INVALID");

        OverseasInstrument unknownCurrency = new OverseasInstrument(
                "00700", "腾讯控股", "HK", "EUR", "HKEX", "Asia/Hong_Kong");
        when(client.overseasSearch("HK", "00700"))
                .thenReturn(new OverseasSearchResponse(
                        List.of(unknownCurrency), false, Instant.parse("2026-09-08T01:02:03Z"),
                        false, "READY", null));
        assertThatThrownBy(() -> service.search(authentication, "HK", "00700"))
                .isInstanceOf(MarketProviderException.class)
                .hasMessage("MARKET_UPSTREAM_INVALID");
    }

    private static Stream<Arguments> invalidCandleResponses() {
        return Stream.of(
                Arguments.of("symbol", mutate(validCandles(), response -> new OverseasCandleResponse(
                        response.instrument(), "MSFT", response.source(), response.adjustment(), response.asOf(),
                        response.fetchedAt(), response.stale(), response.supported(), response.bars()))),
                Arguments.of("source", mutate(validCandles(), response -> new OverseasCandleResponse(
                        response.instrument(), response.symbol(), "OTHER", response.adjustment(), response.asOf(),
                        response.fetchedAt(), response.stale(), response.supported(), response.bars()))),
                Arguments.of("adjustment", mutate(validCandles(), response -> new OverseasCandleResponse(
                        response.instrument(), response.symbol(), response.source(), "qfq", response.asOf(),
                        response.fetchedAt(), response.stale(), response.supported(), response.bars()))),
                Arguments.of("currency", mutate(validCandles(), response -> new OverseasCandleResponse(
                        instrument("US", "AAPL", "HKD", "America/New_York"), response.symbol(), response.source(),
                        response.adjustment(), response.asOf(), response.fetchedAt(), response.stale(),
                        response.supported(), response.bars()))),
                Arguments.of("timezone", mutate(validCandles(), response -> new OverseasCandleResponse(
                        instrument("US", "AAPL", "USD", "UTC"), response.symbol(), response.source(),
                        response.adjustment(), response.asOf(), response.fetchedAt(), response.stale(),
                        response.supported(), response.bars()))),
                Arguments.of("chronology", mutate(validCandles(), response -> new OverseasCandleResponse(
                        response.instrument(), response.symbol(), response.source(), response.adjustment(),
                        response.asOf(), response.fetchedAt(), response.stale(), response.supported(),
                        List.of(response.bars().get(1), response.bars().get(0))))),
                Arguments.of("OHLC", mutate(validCandles(), response -> new OverseasCandleResponse(
                        response.instrument(), response.symbol(), response.source(), response.adjustment(),
                        response.asOf(), response.fetchedAt(), response.stale(), response.supported(),
                        List.of(new CandleBar(response.bars().get(0).timestamp(), new BigDecimal("12"),
                                new BigDecimal("11"), new BigDecimal("9"), new BigDecimal("10"), 12, null))))),
                Arguments.of("supported", mutate(validCandles(), response -> new OverseasCandleResponse(
                        response.instrument(), response.symbol(), response.source(), response.adjustment(),
                        response.asOf(), response.fetchedAt(), response.stale(), false, response.bars()))));
    }

    private static Stream<Arguments> unfinishedCandleDates() {
        return Stream.of(
                Arguments.of("current-day", LocalDate.of(2026, 9, 8)),
                Arguments.of("future-day", LocalDate.of(2099, 1, 2)));
    }

    private static OverseasCandleResponse mutate(
            OverseasCandleResponse response, UnaryOperator<OverseasCandleResponse> mutation) {
        return mutation.apply(response);
    }

    private static OverseasCandleResponse validCandles() {
        ZoneId zone = ZoneId.of("America/New_York");
        long first = ZonedDateTime.of(2026, 3, 6, 0, 0, 0, 0, zone).toInstant().toEpochMilli();
        long second = ZonedDateTime.of(2026, 3, 9, 0, 0, 0, 0, zone).toInstant().toEpochMilli();
        return new OverseasCandleResponse(
                instrument("US", "AAPL", "USD", "America/New_York"), "AAPL", "SINA", "none",
                LocalDate.of(2026, 3, 9), Instant.parse("2026-03-10T14:00:00Z"), false, true,
                List.of(
                        new CandleBar(first, new BigDecimal("9"), new BigDecimal("12"),
                                new BigDecimal("8"), new BigDecimal("11"), 10, null),
                        new CandleBar(second, new BigDecimal("11"), new BigDecimal("13"),
                                new BigDecimal("10"), new BigDecimal("12"), 20, new BigDecimal("240"))));
    }

    private static OverseasInstrument instrument(String market, String symbol, String currency, String timezone) {
        return new OverseasInstrument(symbol, "Apple Inc.", market, currency, "NASDAQ", timezone);
    }
}
