package com.familyfinance.reporting;

import com.familyfinance.investment.InvestmentPosition;
import com.familyfinance.investment.InvestmentTrade;
import com.familyfinance.investment.InvestmentTradeRepository;
import com.familyfinance.investment.PositionCalculator;
import com.familyfinance.investment.PositionTrade;
import com.familyfinance.market.MarketPriceResponse;
import com.familyfinance.market.QuoteRefreshService;
import com.familyfinance.shared.Money;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class PortfolioService {
    private final InvestmentTradeRepository trades;
    private final QuoteRefreshService prices;
    private final com.familyfinance.accounting.LedgerReportingService ledger;
    private final java.time.Clock clock;
    private final PositionCalculator calculator = new PositionCalculator();

    public PortfolioService(InvestmentTradeRepository trades, QuoteRefreshService prices,com.familyfinance.accounting.LedgerReportingService ledger,java.time.Clock clock) {
        this.trades = trades;
        this.prices = prices;
        this.ledger=ledger;this.clock=clock;
    }

    public PortfolioResponse portfolio(long householdId) {
        return portfolio(householdId,java.time.LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Shanghai"))));
    }

    public PortfolioResponse portfolio(long householdId,java.time.LocalDate asOf) {
        ledger.requireComplete(householdId);
        if(asOf==null||asOf.getYear()<1000||asOf.isAfter(java.time.LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Shanghai")))))
            throw new com.familyfinance.shared.RequestValidationException(java.util.Map.of("asOf","截止日期必须在1000年至今天之间"));
        var ledgerCosts=ledger.balancesAsOf(householdId,asOf);
        Map<Long, MarketPriceResponse> effective = new LinkedHashMap<>();
        Map<PositionKey, List<InvestmentTrade>> grouped = new LinkedHashMap<>();
        for (InvestmentTrade trade : trades.historyAsOf(householdId,asOf)) {
            grouped.computeIfAbsent(new PositionKey(trade.getAccount().getId(), trade.getSecurity().getId()), ignored -> new ArrayList<>())
                    .add(trade);
        }

        List<CalculatedPosition> calculated = new ArrayList<>();
        for (List<InvestmentTrade> history : grouped.values()) {
            InvestmentTrade first = history.get(0);
            MarketPriceResponse price = effective.computeIfAbsent(first.getSecurity().getId(),id->prices.effectivePriceAsOf(householdId,first.getSecurity(),asOf));
            Long priceCents = price == null || price.price() == null ? null : Money.parseCents(price.price());
            InvestmentPosition position = calculator.calculate(history.stream()
                    .map(trade -> new PositionTrade(trade.getId(), trade.getTradedOn(), trade.getType(), trade.getQuantity(),
                            trade.getPriceCents(), trade.getFeeCents()))
                    .toList(), priceCents);
            if(position.costCents()!=ledgerCosts.getOrDefault("POSITION:"+first.getAccount().getId()+":"+first.getSecurity().getId(),0L))
                throw new com.familyfinance.shared.ResourceConflictException("ACCOUNTING_BALANCE_MISMATCH","持仓历史成本与截止日账务成本不一致，请核对来源");
            if(position.quantity().signum()==0)position=calculator.calculate(history.stream().map(trade -> new PositionTrade(trade.getId(),trade.getTradedOn(),trade.getType(),trade.getQuantity(),trade.getPriceCents(),trade.getFeeCents())).toList(),0L);
            calculated.add(new CalculatedPosition(first, position, price));
        }
        calculated.sort(Comparator.comparing((CalculatedPosition value) -> value.trade().getAccount().getName())
                .thenComparing(value -> value.trade().getSecurity().getTsCode())
                .thenComparing(value -> value.trade().getAccount().getId()));

        Totals totals = Totals.from(calculated);
        return new PortfolioResponse(calculated.stream().map(value -> response(value, totals.marketValue())).toList(), totals.response());
    }

    private static PortfolioPositionResponse response(CalculatedPosition value, Long totalMarketValue) {
        InvestmentTrade trade = value.trade();
        InvestmentPosition position = value.position();
        boolean closed = position.quantity().signum()==0;
        MarketPriceResponse price = closed ? null : value.price();
        Long totalProfit = position.unrealizedProfitCents() == null ? null
                : Math.addExact(position.realizedProfitCents(), position.unrealizedProfitCents());
        String allocation = position.marketValueCents() == null || totalMarketValue == null || totalMarketValue == 0L ? "0.0"
                : BigDecimal.valueOf(position.marketValueCents()).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(totalMarketValue), 1, RoundingMode.HALF_UP).toPlainString();
        return new PortfolioPositionResponse(trade.getAccount().getId(), trade.getAccount().getName(), trade.getAccount().getBrokerName(),
                trade.getSecurity().getId(), trade.getSecurity().getTsCode(), trade.getSecurity().getName(), position.quantity(),
                position.averageCostCents().movePointLeft(2).setScale(4, RoundingMode.HALF_UP).toPlainString(),
                Money.formatCents(position.costCents()), price == null ? null : price.price(), cents(position.marketValueCents()),
                Money.formatCents(position.realizedProfitCents()), cents(position.unrealizedProfitCents()), cents(totalProfit), allocation,
                price == null ? null : price.source(), price == null ? null : price.tradeDate(), price == null ? null : price.fetchedAt(),
                !closed && (price == null || price.stale()), closed ? null : price == null ? "NO_QUOTE" : price.error(),
                Money.formatCents(position.marketValueCents()==null?position.costCents():position.marketValueCents()),
                position.quantity().signum()==0?"CLOSED":position.marketValueCents()==null?"COST_ESTIMATE":"QUOTED");
    }

    private static String cents(Long value) {
        return value == null ? null : Money.formatCents(value);
    }

    private record PositionKey(long accountId, long securityId) { }
    private record CalculatedPosition(InvestmentTrade trade, InvestmentPosition position, MarketPriceResponse price) { }

    private record Totals(long cost, long realized, Long marketValue, Long unrealized, int unpriced,long estimated) {
        static Totals from(List<CalculatedPosition> positions) {
            BigInteger cost = BigInteger.ZERO;
            BigInteger realized = BigInteger.ZERO;
            BigInteger value = BigInteger.ZERO;
            BigInteger unrealized = BigInteger.ZERO;
            BigInteger estimated=BigInteger.ZERO;
            int unpriced = 0;
            for (CalculatedPosition position : positions) {
                cost = cost.add(BigInteger.valueOf(position.position().costCents()));
                realized = realized.add(BigInteger.valueOf(position.position().realizedProfitCents()));
                estimated=estimated.add(BigInteger.valueOf(position.position().marketValueCents()==null?position.position().costCents():position.position().marketValueCents()));
                if (position.position().marketValueCents() == null) unpriced++;
                else {
                    value = value.add(BigInteger.valueOf(position.position().marketValueCents()));
                    unrealized = unrealized.add(BigInteger.valueOf(position.position().unrealizedProfitCents()));
                }
            }
            return new Totals(cost.longValueExact(), realized.longValueExact(), unpriced == 0 ? value.longValueExact() : null,
                    unpriced == 0 ? unrealized.longValueExact() : null, unpriced,estimated.longValueExact());
        }

        PortfolioTotalsResponse response() {
            Long total = unrealized == null ? null : Math.addExact(realized, unrealized);
            return new PortfolioTotalsResponse(Money.formatCents(cost), cents(marketValue), Money.formatCents(realized),
                    cents(unrealized), cents(total), unpriced,Money.formatCents(estimated));
        }
    }
}
