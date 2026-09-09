package com.familyfinance.market;

import com.familyfinance.investment.SecurityService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class OverseasMarketService {
    private static final int MAX_QUERY_LENGTH = 80;
    private static final int MAX_SEARCH_ITEMS = 20;
    private static final int MAX_CANDLE_BARS = 600;
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Pattern HK_SYMBOL = Pattern.compile("^[0-9]{5}$");
    private static final Pattern US_SYMBOL = Pattern.compile("^[A-Z][A-Z0-9.-]{0,9}$");
    private static final Pattern ISO_CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final Set<String> US_EXCHANGES = Set.of(
            "NASDAQ", "NYSE", "NYSE AMERICAN", "NYSE ARCA", "CBOE BZX", "IEX");
    private static final Set<String> HK_CURRENCIES = Set.of("HKD", "USD", "CNY");
    private static final Set<String> SEARCH_STATES = Set.of("SYNCING", "READY", "ERROR");

    private final SecurityService security;
    private final MarketDataClient client;
    private final Clock clock;

    @Autowired
    public OverseasMarketService(SecurityService security, MarketDataClient client) {
        this(security, client, Clock.systemUTC());
    }

    OverseasMarketService(SecurityService security, MarketDataClient client, Clock clock) {
        this.security = security;
        this.client = client;
        this.clock = clock;
    }

    public OverseasSearchResponse search(Authentication authentication, String rawMarket, String rawQuery) {
        security.requireMembership(authentication);
        String market = normalizeMarket(rawMarket);
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.length() > MAX_QUERY_LENGTH) {
            throw validation("q", "搜索内容长度不能超过 80 个字符");
        }
        OverseasSearchResponse response = normalize(client.overseasSearch(market, query));
        validateSearch(response, market);
        return response;
    }

    public OverseasCandleResponse candles(
            Authentication authentication, String rawMarket, String rawSymbol) {
        security.requireMembership(authentication);
        return verifiedCandles(rawMarket,rawSymbol);
    }
    public OverseasCandleResponse verifiedCandles(String rawMarket,String rawSymbol) {
        String market = normalizeMarket(rawMarket);
        String symbol = normalizeSymbol(market, rawSymbol);
        OverseasCandleResponse response = normalize(client.overseasCandles(market, symbol));
        validateCandles(response, market, symbol, clock.instant());
        return response;
    }

    private static String normalizeMarket(String rawMarket) {
        String market = rawMarket == null ? "" : rawMarket.trim().toUpperCase(Locale.ROOT);
        if (!market.equals("HK") && !market.equals("US")) {
            throw validation("market", "市场只能是 HK 或 US");
        }
        return market;
    }

    private static String normalizeSymbol(String market, String rawSymbol) {
        String symbol = rawSymbol == null ? "" : rawSymbol.trim().toUpperCase(Locale.ROOT);
        Pattern pattern = market.equals("HK") ? HK_SYMBOL : US_SYMBOL;
        if (!pattern.matcher(symbol).matches()) {
            throw validation("symbol", "股票代码格式不正确");
        }
        return symbol;
    }

    private static void validateSearch(OverseasSearchResponse response, String market) {
        if (response == null || response.items() == null || response.items().size() > MAX_SEARCH_ITEMS
                || !SEARCH_STATES.contains(response.state())) {
            throw invalid();
        }
        if (response.stale() && response.updatedAt() == null) throw invalid();
        if (response.state().equals("ERROR")) {
            if (response.error() == null || response.error().isBlank()) throw invalid();
        } else if (response.error() != null) {
            throw invalid();
        }
        Set<String> symbols = new HashSet<>();
        for (OverseasInstrument item : response.items()) {
            validateInstrument(item, market, null);
            if (!symbols.add(item.symbol())) throw invalid();
        }
    }

    private static void validateCandles(
            OverseasCandleResponse response, String market, String symbol, Instant now) {
        if (response == null || response.bars() == null || response.bars().size() > MAX_CANDLE_BARS
                || !symbol.equals(response.symbol()) || !"SINA".equals(response.source())
                || !"none".equals(response.adjustment()) || !response.supported()
                || response.fetchedAt() == null
                || response.fetchedAt().isAfter(now.plus(MAX_CLOCK_SKEW))) {
            throw invalid();
        }
        validateInstrument(response.instrument(), market, symbol);
        ZoneId zone = ZoneId.of(expectedTimezone(market));
        LocalDate fetchedDay = completedMarketDay(response.fetchedAt(), zone);
        LocalDate actualDay = completedMarketDay(now, zone);
        Instant previous = null;
        LocalDate finalDate = null;
        for (CandleBar bar : response.bars()) {
            if (bar == null || bar.timestamp() <= 0 || invalidPrice(bar.open()) || invalidPrice(bar.high())
                    || invalidPrice(bar.low()) || invalidPrice(bar.close()) || bar.volume() < 0
                    || (bar.turnover() != null && bar.turnover().signum() < 0)
                    || bar.low().compareTo(bar.high()) > 0
                    || bar.open().compareTo(bar.low()) < 0 || bar.open().compareTo(bar.high()) > 0
                    || !validClose(bar, market)) {
                throw invalid();
            }
            Instant timestamp = Instant.ofEpochMilli(bar.timestamp());
            var local = timestamp.atZone(zone);
            if (!local.toLocalTime().equals(LocalTime.MIDNIGHT)
                    || local.toLocalDate().isAfter(fetchedDay)
                    || local.toLocalDate().isAfter(actualDay)
                    || (previous != null && !timestamp.isAfter(previous))) {
                throw invalid();
            }
            previous = timestamp;
            finalDate = local.toLocalDate();
        }
        if (response.bars().isEmpty()) {
            if (response.asOf() != null) throw invalid();
        } else if (!finalDate.equals(response.asOf())) {
            throw invalid();
        }
    }

    private static LocalDate completedMarketDay(Instant instant, ZoneId zone) {
        // Match the adapter's 17:30 local publication buffer, including US DST.
        var local = instant.atZone(zone);
        LocalDate day = local.toLocalTime().isBefore(LocalTime.of(17, 30))
                ? local.toLocalDate().minusDays(1) : local.toLocalDate();
        while (day.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                || day.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) day = day.minusDays(1);
        return day;
    }

    private static void validateInstrument(OverseasInstrument instrument, String market, String symbol) {
        if (instrument == null || !market.equals(instrument.market())
                || (symbol != null && !symbol.equals(instrument.symbol()))
                || !normalizeSymbolForPayload(market, instrument.symbol())
                || instrument.name() == null || instrument.name().isBlank()
                || instrument.name().length() > 200 || !instrument.name().equals(instrument.name().trim())
                || instrument.currency() == null || !ISO_CURRENCY.matcher(instrument.currency()).matches()
                || (market.equals("US") && !"USD".equals(instrument.currency()))
                || (market.equals("HK") && !HK_CURRENCIES.contains(instrument.currency()))
                || !expectedTimezone(market).equals(instrument.timezone())
                || !validExchange(market, instrument.exchange())) {
            throw invalid();
        }
    }

    private static boolean normalizeSymbolForPayload(String market, String symbol) {
        if (symbol == null) return false;
        Pattern pattern = market.equals("HK") ? HK_SYMBOL : US_SYMBOL;
        return pattern.matcher(symbol).matches();
    }

    private static boolean validExchange(String market, String exchange) {
        return market.equals("HK") ? "HKEX".equals(exchange) : US_EXCHANGES.contains(exchange);
    }

    private static String expectedTimezone(String market) {
        return market.equals("HK") ? "Asia/Hong_Kong" : "America/New_York";
    }

    private static boolean invalidPrice(BigDecimal value) {
        return value == null || value.signum() <= 0;
    }

    private static boolean validClose(CandleBar bar, String market) {
        // Match the adapter's bounded SINA HK close-tail tolerance; retain
        // original OHLC values and keep other markets strictly in-range.
        BigDecimal tolerance = market.equals("HK")
                ? bar.high().abs().max(bar.low().abs()).max(bar.close().abs())
                    .multiply(new BigDecimal("0.0000001")).min(new BigDecimal("0.00002"))
                : BigDecimal.ZERO;
        return bar.close().compareTo(bar.low().subtract(tolerance)) >= 0
                && bar.close().compareTo(bar.high().add(tolerance)) <= 0;
    }

    private static OverseasSearchResponse normalize(OverseasSearchResponse response) {
        if (response == null || response.items() == null) return response;
        return new OverseasSearchResponse(
                response.items().stream().map(OverseasMarketService::normalize).toList(),
                response.hasNext(), response.updatedAt(), response.stale(), response.state(), response.error());
    }

    private static OverseasCandleResponse normalize(OverseasCandleResponse response) {
        if (response == null) return null;
        return new OverseasCandleResponse(
                normalize(response.instrument()), response.symbol(), response.source(), response.adjustment(),
                response.asOf(), response.fetchedAt(), response.stale(), response.supported(), response.bars());
    }

    private static OverseasInstrument normalize(OverseasInstrument instrument) {
        if (instrument == null || !"HK".equals(instrument.market()) || !"RMB".equals(instrument.currency())) {
            return instrument;
        }
        return new OverseasInstrument(
                instrument.symbol(), instrument.name(), instrument.market(), "CNY",
                instrument.exchange(), instrument.timezone());
    }

    private static MarketValidationException validation(String field, String message) {
        return new MarketValidationException(Map.of(field, message));
    }

    private static MarketProviderException invalid() {
        return new MarketProviderException("MARKET_UPSTREAM_INVALID", false);
    }
}
