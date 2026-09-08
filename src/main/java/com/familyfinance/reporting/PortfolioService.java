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
    @org.springframework.beans.factory.annotation.Autowired private com.familyfinance.fx.FxJournalRates fx;
    private final com.familyfinance.investment.BasePositionCalculator baseCalculator=new com.familyfinance.investment.BasePositionCalculator();

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
            BigDecimal priceAmount = price == null || price.price() == null ? null : new BigDecimal(price.price());
            InvestmentPosition position = calculator.calculateAtPrice(history.stream()
                    .map(InvestmentTrade::toPositionTrade).toList(), priceAmount);
            if(position.costCents()!=ledgerCosts.getOrDefault("POSITION:"+first.getAccount().getId()+":"+first.getSecurity().getId(),0L))
                throw new com.familyfinance.shared.ResourceConflictException("ACCOUNTING_BALANCE_MISMATCH","持仓历史成本与截止日账务成本不一致，请核对来源");
            if(position.quantity().signum()==0)position=calculator.calculateAtPrice(history.stream().map(InvestmentTrade::toPositionTrade).toList(),BigDecimal.ZERO);
            String currency=first.getAccount().getCurrency();
            Long baseMarket=position.marketValueCents()==null?null:convert(currency,position.marketValueCents(),asOf);
            Long estimated=convert(currency,position.marketValueCents()==null?position.costCents():position.marketValueCents(),asOf);
            var base=baseCalculator.calculate(history.stream().map(t->new com.familyfinance.investment.BasePositionCalculator.Trade(t.toPositionTrade(),
                    currency.equals("CNY")?BigDecimal.ONE:fx.sourceRate(householdId,"INVESTMENT_TRADE",t.getId(),currency))).toList(),baseMarket);
            var rate=currency.equals("CNY")?null:fx.reference(currency,asOf);
            calculated.add(new CalculatedPosition(first, position, price,base,baseMarket,estimated,rate==null?null:rate.effectiveOn(),
                    estimated==null?"MISSING":rate!=null&&java.time.temporal.ChronoUnit.DAYS.between(rate.effectiveOn(),asOf)>4?"STALE":"READY"));
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
        String allocation = value.baseMarket() == null || totalMarketValue == null || totalMarketValue == 0L ? "0.0"
                : BigDecimal.valueOf(value.baseMarket()).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(totalMarketValue), 1, RoundingMode.HALF_UP).toPlainString();
        return new PortfolioPositionResponse(trade.getAccount().getId(), trade.getAccount().getName(), trade.getAccount().getBrokerName(),
                trade.getSecurity().getId(), trade.getSecurity().getTsCode(), trade.getSecurity().getName(), position.quantity(),
                position.averageCostCents().movePointLeft(2).setScale(4, RoundingMode.HALF_UP).toPlainString(),
                Money.formatCents(position.costCents()), price == null ? null : price.price(), cents(position.marketValueCents()),
                Money.formatCents(position.realizedProfitCents()), cents(position.unrealizedProfitCents()), cents(totalProfit), allocation,
                price == null ? null : price.source(), price == null ? null : price.tradeDate(), price == null ? null : price.fetchedAt(),
                !closed && (price == null || price.stale()), closed ? null : price == null ? "NO_QUOTE" : price.error(),
                Money.formatCents(position.marketValueCents()==null?position.costCents():position.marketValueCents()),
                position.quantity().signum()==0?"CLOSED":position.marketValueCents()==null?"COST_ESTIMATE":"QUOTED",
                trade.getAccount().getCurrency(),trade.getSecurity().getMarket(),trade.getSecurity().getSymbol(),trade.getSecurity().getExchange(),trade.getSecurity().getTimezone(),
                new PortfolioPositionResponse.BaseValuation(cents(value.base().cost()),cents(value.baseMarket()),cents(value.base().realized()),cents(value.base().unrealized()),cents(value.base().total()),cents(value.estimated()),value.fxDate(),value.fxState()));
    }

    private static String cents(Long value) {
        return value == null ? null : Money.formatCents(value);
    }

    private record PositionKey(long accountId, long securityId) { }
    private Long convert(String currency,long amount,java.time.LocalDate day){
        if(currency.equals("CNY"))return amount;
        BigDecimal result=fx.convert(currency,BigDecimal.valueOf(amount,2),day);
        return result==null?null:result.movePointRight(2).longValueExact();
    }
    private record CalculatedPosition(InvestmentTrade trade,InvestmentPosition position,MarketPriceResponse price,
            com.familyfinance.investment.BasePositionCalculator.Result base,Long baseMarket,Long estimated,java.time.LocalDate fxDate,String fxState) {}
    private record Totals(Long cost,Long realized,Long marketValue,Long unrealized,int unpriced,Long estimated,int missingFx,long knownEstimated) {
        static Totals from(List<CalculatedPosition> positions){
            Long cost=0L,realized=0L,value=0L,unrealized=0L,estimated=0L;int unpriced=0,missingFx=0;long known=0;
            for(var p:positions){
                cost=add(cost,p.base().cost());realized=add(realized,p.base().realized());
                value=add(value,p.baseMarket());unrealized=add(unrealized,p.base().unrealized());estimated=add(estimated,p.estimated());
                if(p.position().marketValueCents()==null)unpriced++;
                if(p.estimated()==null)missingFx++;else known=Math.addExact(known,p.estimated());
            }
            return new Totals(cost,realized,value,unrealized,unpriced,estimated,missingFx,known);
        }
        private static Long add(Long a,Long b){return a==null||b==null?null:Math.addExact(a,b);}
        PortfolioTotalsResponse response(){
            return new PortfolioTotalsResponse(cents(cost),cents(marketValue),cents(realized),cents(unrealized),cents(add(realized,unrealized)),unpriced,cents(estimated),"CNY",missingFx,cents(knownEstimated));
        }
    }
}
