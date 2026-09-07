package com.familyfinance.loan;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import com.familyfinance.shared.ResourceConflictException;

/** Pure rescheduling over the preserved future date sequence. */
public final class LoanPrepaymentPlanner {
 public List<InstallmentDraft> plan(List<InstallmentDraft> before,long amount,BigDecimal annualRate,RepaymentMethod method,LocalDate paidOn,PrepaymentStrategy strategy){
  if(before.isEmpty()||before.size()>360)throw new ResourceConflictException("LOAN_PLAN_INVALID","贷款未来计划为空或超出360期，请核对计划");
  long original=0;LocalDate previous=null;
  for(var row:before){
   if(row.principalCents()<=0||row.interestCents()<0||(previous!=null&&!row.dueOn().isAfter(previous)))throw new ResourceConflictException("LOAN_PLAN_INVALID","贷款计划金额或日期异常，请核对计划");
   if(!row.dueOn().isAfter(paidOn))throw new ResourceConflictException("LOAN_OVERDUE_INSTALLMENTS","请先确认所有到期未付期次，再进行部分提前还款");
   original=Math.addExact(original,row.principalCents());previous=row.dueOn();
  }
  if(amount<=0||amount>=original)throw new ResourceConflictException("LOAN_PAYOFF_REQUIRED","部分还款须小于剩余本金；全部偿还请使用一次结清");
  long remaining=Math.subtractExact(original,amount),initial=remaining,oldRemaining=original;
  boolean fixed=strategy==PrepaymentStrategy.REDUCE_PAYMENT;
  if(fixed&&remaining<before.size())throw new ResourceConflictException("LOAN_FIXED_TERM_INFEASIBLE","剩余本金不足每期至少一分钱，请选择缩短期限或一次结清");
  List<InstallmentDraft> standard=fixed&&method!=RepaymentMethod.CUSTOM?new AmortizationCalculator().calculate(remaining,annualRate,before.size(),paidOn,method):List.of();
  if(standard.stream().anyMatch(row->row.principalCents()<=0))throw new ResourceConflictException("LOAN_FIXED_TERM_INFEASIBLE","剩余本金无法按原还款方式保留全部期次，请选择缩短期限或一次结清");
  List<InstallmentDraft> result=new ArrayList<>();
  for(int i=0;i<before.size()&&remaining>0;i++){
   var old=before.get(i);
   // For custom periods calculate the exact rational interest directly, avoiding a rounded intermediate rate.
   long interest=method==RepaymentMethod.CUSTOM?BigDecimal.valueOf(remaining).multiply(BigDecimal.valueOf(old.interestCents())).divide(BigDecimal.valueOf(oldRemaining),0,RoundingMode.HALF_UP).longValueExact():AmortizationCalculator.periodInterest(remaining,annualRate);
   long principal;
   if(!fixed){principal=Math.min(remaining,Math.subtractExact(Math.addExact(old.principalCents(),old.interestCents()),interest));}
   else if(method!=RepaymentMethod.CUSTOM){principal=standard.get(i).principalCents();}
   else if(i==before.size()-1){principal=remaining;}
   else{
    long desired=BigDecimal.valueOf(initial).multiply(BigDecimal.valueOf(old.principalCents())).divide(BigDecimal.valueOf(original),0,RoundingMode.DOWN).longValueExact();
    long lower=Math.max(1,remaining-(oldRemaining-old.principalCents())),upper=Math.min(remaining-(before.size()-i-1),old.principalCents());
    principal=Math.max(lower,Math.min(desired,upper));
   }
   if(principal<=0||principal>remaining)throw new ResourceConflictException("LOAN_PLAN_INVALID","原逐期付款上限不足以偿还本金，请核对计划");
   remaining=Math.subtractExact(remaining,principal);oldRemaining=Math.subtractExact(oldRemaining,old.principalCents());
   result.add(new InstallmentDraft(i+1,old.dueOn(),principal,interest,remaining));
  }
  if(remaining!=0)throw new ResourceConflictException("LOAN_PLAN_INVALID","原期限内无法偿还全部本金，请核对计划");
  return List.copyOf(result);
 }
}
