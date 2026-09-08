package com.familyfinance.investment;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class DecimalUnitPriceTest {
    @Test void subCentUnitPricesAreMultipliedBeforeSettlement() {
        var buy=new PositionTrade(1,LocalDate.of(2026,1,1),InvestmentTradeType.BUY,new BigDecimal("1000"),new BigDecimal("0.001234"),0);
        var position=new PositionCalculator().calculateAtPrice(List.of(buy),new BigDecimal("0.001999"));
        assertThat(position.costCents()).isEqualTo(123);
        assertThat(position.marketValueCents()).isEqualTo(200);
        assertThat(position.unrealizedProfitCents()).isEqualTo(77);
    }
    @Test void partialAndFullSalesConsumeTheExactRoundedAggregateCost() {
        var day=LocalDate.of(2026,1,1);
        var position=new PositionCalculator().calculateAtPrice(List.of(
                new PositionTrade(1,day,InvestmentTradeType.BUY,new BigDecimal("1000"),new BigDecimal("0.001234"),1),
                new PositionTrade(2,day,InvestmentTradeType.SELL,new BigDecimal("333"),new BigDecimal("0.002000"),0),
                new PositionTrade(3,day,InvestmentTradeType.SELL,new BigDecimal("667"),new BigDecimal("0.002000"),0)),null);
        assertThat(position.quantity()).isZero();assertThat(position.costCents()).isZero();
        assertThat(position.realizedProfitCents()).isEqualTo(76);
    }
}
