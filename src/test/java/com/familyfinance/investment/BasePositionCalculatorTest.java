package com.familyfinance.investment;
import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
class BasePositionCalculatorTest {
 @Test void usesHistoricalPurchaseCostAndSaleDateRateInsteadOfTodayTimesNativeProfit(){
  var day=LocalDate.of(2026,1,1);
  var value=new BasePositionCalculator().calculate(List.of(
    new BasePositionCalculator.Trade(new PositionTrade(1,day,InvestmentTradeType.BUY,new BigDecimal("100"),new BigDecimal("1"),0),new BigDecimal("7")),
    new BasePositionCalculator.Trade(new PositionTrade(2,day,InvestmentTradeType.SELL,new BigDecimal("50"),new BigDecimal("2"),0),new BigDecimal("8"))),80000L);
  assertThat(value.cost()).isEqualTo(35000);assertThat(value.realized()).isEqualTo(45000);
  assertThat(value.unrealized()).isEqualTo(45000);assertThat(value.total()).isEqualTo(90000);
 }
 @Test void missingHistoricalRateIsNotReplacedByCurrentRate(){
  var value=new BasePositionCalculator().calculate(List.of(new BasePositionCalculator.Trade(
    new PositionTrade(1,LocalDate.of(2026,1,1),InvestmentTradeType.BUY,BigDecimal.ONE,BigDecimal.ONE,0),null)),700L);
  assertThat(value.cost()).isNull();assertThat(value.unrealized()).isNull();
 }
}
