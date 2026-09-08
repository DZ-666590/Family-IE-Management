package com.familyfinance.investment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/** Performance in CNY using historical cash/cost translations, including FX effects. */
public final class BasePositionCalculator {
 public record Trade(PositionTrade nativeTrade,BigDecimal rate){}
 public record Result(Long cost,Long realized,Long unrealized,Long total){}
 public Result calculate(List<Trade> trades,Long marketValue){
  BigDecimal quantity=BigDecimal.ZERO;long cost=0,realized=0;boolean costKnown=true,realizedKnown=true;
  for(var row:trades.stream().sorted(Comparator.comparing((Trade x)->x.nativeTrade().tradedOn()).thenComparingLong(x->x.nativeTrade().id())).toList()){
   var t=row.nativeTrade();BigDecimal gross=t.quantity()==null?t.unitPrice():t.quantity().multiply(t.unitPrice()).setScale(2,RoundingMode.HALF_UP);
   BigDecimal fee=BigDecimal.valueOf(t.feeCents(),2);
   switch(t.type()){
    case BUY,OPENING->{
     Long added=convert(gross.add(fee),row.rate());
     if(added==null)costKnown=false;else if(costKnown)cost=Math.addExact(cost,added);
     quantity=quantity.add(t.quantity());
    }
    case SELL->{
     if(t.quantity().compareTo(quantity)>0)throw new InsufficientHoldingException();
     boolean full=t.quantity().compareTo(quantity)==0;
     Long allocated=costKnown?(full?cost:BigDecimal.valueOf(cost).multiply(t.quantity()).divide(quantity,0,RoundingMode.HALF_UP).longValueExact()):null;
     Long proceeds=convert(gross.subtract(fee),row.rate());
     if(allocated==null||proceeds==null)realizedKnown=false;else if(realizedKnown)realized=Math.addExact(realized,Math.subtractExact(proceeds,allocated));
     if(allocated!=null)cost=Math.subtractExact(cost,allocated);
     quantity=quantity.subtract(t.quantity());if(full){cost=0;costKnown=true;}
    }
    case DIVIDEND,FEE->{Long amount=convert(gross,row.rate());if(amount==null)realizedKnown=false;
     else if(realizedKnown)realized=Math.addExact(realized,t.type()==InvestmentTradeType.FEE?Math.negateExact(amount):amount);}
   }
  }
  Long remaining=costKnown?cost:null;
  Long unrealized=marketValue==null||remaining==null?null:Math.subtractExact(marketValue,remaining);
  return new Result(remaining,realizedKnown?realized:null,unrealized,!realizedKnown||unrealized==null?null:Math.addExact(realized,unrealized));
 }
 private static Long convert(BigDecimal amount,BigDecimal rate){
  if(amount.signum()==0)return 0L;if(rate==null)return null;
  return amount.multiply(rate).movePointRight(2).setScale(0,RoundingMode.HALF_UP).longValueExact();
 }
}
