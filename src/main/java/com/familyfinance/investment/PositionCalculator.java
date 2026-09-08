package com.familyfinance.investment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/**
 * Replays trades using weighted average cost. Each BUY capitalizes its rounded gross and fee;
 * each SELL allocates the current aggregate cost half-up to cents (or all remaining cost for a
 * full sale). Dividends and standalone fees affect realized profit and cash only.
 */
public class PositionCalculator {

    private static final BigDecimal ZERO_QUANTITY = new BigDecimal("0.0000");

    public InvestmentPosition calculate(List<PositionTrade> input, Long marketPriceCents) {
        return calculateAtPrice(input,marketPriceCents==null?null:BigDecimal.valueOf(marketPriceCents,2));
    }
    public InvestmentPosition calculateAtPrice(List<PositionTrade> input,BigDecimal marketPrice) {
        List<PositionTrade> trades = input.stream()
                .sorted(Comparator.comparing(PositionTrade::tradedOn).thenComparingLong(PositionTrade::id))
                .toList();
        BigDecimal quantity = ZERO_QUANTITY;
        long costCents = 0;
        long realizedProfitCents = 0;
        long cashImpactCents = 0;

        for (PositionTrade trade : trades) {
            validate(trade);
            switch (trade.type()) {
                case OPENING, BUY -> {
                    long gross = roundedProduct(trade.quantity(), trade.unitPrice());
                    long addedCost = Math.addExact(gross, trade.feeCents());
                    costCents = Math.addExact(costCents, addedCost);
                    if (trade.type() == InvestmentTradeType.BUY)
                        cashImpactCents = Math.subtractExact(cashImpactCents, addedCost);
                    quantity = quantity.add(trade.quantity()).setScale(4);
                }
                case SELL -> {
                    if (trade.quantity().compareTo(quantity) > 0) {
                        throw new InsufficientHoldingException();
                    }
                    long proceeds = roundedProduct(trade.quantity(), trade.unitPrice());
                    long allocatedCost = trade.quantity().compareTo(quantity) == 0
                            ? costCents
                            : BigDecimal.valueOf(costCents)
                                    .multiply(trade.quantity())
                                    .divide(quantity, 0, RoundingMode.HALF_UP)
                                    .longValueExact();
                    long netProceeds = Math.subtractExact(proceeds, trade.feeCents());
                    realizedProfitCents = Math.addExact(
                            realizedProfitCents, Math.subtractExact(netProceeds, allocatedCost));
                    cashImpactCents = Math.addExact(cashImpactCents, netProceeds);
                    costCents = Math.subtractExact(costCents, allocatedCost);
                    quantity = quantity.subtract(trade.quantity()).setScale(4);
                }
                case DIVIDEND -> {
                    realizedProfitCents = Math.addExact(realizedProfitCents, trade.priceCents());
                    cashImpactCents = Math.addExact(cashImpactCents, trade.priceCents());
                }
                case FEE -> {
                    realizedProfitCents = Math.subtractExact(realizedProfitCents, trade.priceCents());
                    cashImpactCents = Math.subtractExact(cashImpactCents, trade.priceCents());
                }
            }
        }

        BigDecimal averageCostCents = quantity.signum() == 0
                ? ZERO_QUANTITY
                : BigDecimal.valueOf(costCents).divide(quantity, 4, RoundingMode.HALF_UP);
        Long marketValueCents = marketPrice == null ? null : roundedProduct(quantity, marketPrice);
        Long marketPriceCents = marketPrice == null ? null : marketPrice.movePointRight(2).setScale(0,RoundingMode.HALF_UP).longValueExact();
        Long unrealizedProfitCents = marketValueCents == null
                ? null
                : Math.subtractExact(marketValueCents, costCents);
        return new InvestmentPosition(
                quantity,
                costCents,
                averageCostCents,
                realizedProfitCents,
                cashImpactCents,
                marketPriceCents,
                marketValueCents,
                unrealizedProfitCents);
    }

    private static long roundedProduct(BigDecimal quantity, BigDecimal price) {
        return quantity.multiply(price).movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private static void validate(PositionTrade trade) {
        if (trade == null || trade.tradedOn() == null || trade.type() == null) {
            throw new IllegalArgumentException("交易标识、日期和类型不能为空");
        }
        if (trade.unitPrice()==null || trade.unitPrice().signum()<=0 || trade.unitPrice().scale()>6 || trade.feeCents() < 0) {
            throw new IllegalArgumentException("价格必须为正数且费用不能为负数");
        }
        if (trade.type() == InvestmentTradeType.OPENING || trade.type() == InvestmentTradeType.BUY || trade.type() == InvestmentTradeType.SELL) {
            if (trade.quantity() == null
                    || trade.quantity().signum() <= 0
                    || trade.quantity().scale() > 4) {
                throw new IllegalArgumentException("买卖数量必须为最多四位小数的正数");
            }
        } else if (trade.quantity() != null || trade.feeCents() != 0) {
            throw new IllegalArgumentException("分红和独立费用不能填写数量或附加费用");
        }
    }
}
