package com.familyfinance.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class BaoStockQuoteProvider implements MarketQuoteProvider {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final MarketDataClient client;

    public BaoStockQuoteProvider(MarketDataClient client) {
        this.client = client;
    }

    @Override
    public boolean available() {
        return client.enabled();
    }

    @Override
    public QuoteSource source() {
        return QuoteSource.BAOSTOCK;
    }

    @Override
    public List<DailyQuote> fetchDaily(Set<String> symbols) {
        if (!available()) throw new MarketProviderException("MARKET_DISABLED", false);
        List<DailyQuote> quotes = new ArrayList<>();
        for (String symbol : symbols.stream().sorted().toList()) {
            CandleResponse response = client.candles(symbol, "none");
            validate(response, symbol, "none");
            if (!response.supported() || response.bars().isEmpty()) continue;
            CandleBar latest = response.bars().get(response.bars().size() - 1);
            BigDecimal previous = response.bars().size() == 1
                    ? latest.close()
                    : response.bars().get(response.bars().size() - 2).close();
            BigDecimal change = latest.close().subtract(previous).multiply(BigDecimal.valueOf(100))
                    .divide(previous, 4, RoundingMode.HALF_UP);
            quotes.add(new DailyQuote(
                    symbol,
                    Instant.ofEpochMilli(latest.timestamp()).atZone(SHANGHAI).toLocalDate(),
                    cents(latest.open()), cents(latest.high()), cents(latest.low()), cents(latest.close()),
                    cents(previous), change, QuoteSource.BAOSTOCK));
        }
        return quotes;
    }

    static void validate(CandleResponse response, String symbol, String adjustment) {
        if (!symbol.equals(response.symbol()) || !QuoteSource.BAOSTOCK.name().equals(response.source())
                || !adjustment.equals(response.adjustment()) || response.bars() == null) {
            throw new MarketProviderException("MARKET_UPSTREAM_INVALID", false);
        }
    }

    private static long cents(BigDecimal value) {
        try {
            long result = value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
            if (result <= 0 || result > 99_999_999_999L) throw new ArithmeticException();
            return result;
        } catch (RuntimeException exception) {
            throw new MarketProviderException("MARKET_UPSTREAM_INVALID", false);
        }
    }
}
