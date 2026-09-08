package com.familyfinance.loan;

import java.math.*;
import java.time.LocalDate;
import java.util.*;
import com.familyfinance.shared.*;

/** Only future dates; ADJUST_TERM is explicit, REDUCE_TERM preserves original cash caps. */
public final class LoanPrepaymentPlanner {
 private static final MathContext MC=MathContext.DECIMAL128;
 public List<InstallmentDraft> plan(List<InstallmentDraft> before,long amount,BigDecimal rate,RepaymentMethod method,LocalDate day,PrepaymentStrategy strategy){
  return plan(before,DecimalMoney.fromCents(amount),rate,method,day,strategy,null,LoanRoundingContext.ZERO,null);
 }
 public List<InstallmentDraft> plan(List<InstallmentDraft> before,BigDecimal amount,BigDecimal rate,RepaymentMethod method,LocalDate day,
       PrepaymentStrategy strategy,Integer targetPeriods,LoanRoundingContext context,BigDecimal minimum){
  BigDecimal original=validate(before,day);amount=DecimalMoney.settled(amount);
  if(amount.signum()<=0||amount.compareTo(original)>=0)throw new ResourceConflictException("LOAN_PAYOFF_REQUIRED","部分还款须小于剩余本金；全部偿还请使用一次结清");
  return planRemaining(before,original.subtract(amount),rate,method,strategy,targetPeriods,context,minimum);
 }
 public List<InstallmentDraft> planRemaining(List<InstallmentDraft> before,BigDecimal remaining,BigDecimal rate,RepaymentMethod method,
       PrepaymentStrategy strategy,Integer targetPeriods,LoanRoundingContext context,BigDecimal minimum){
  BigDecimal original=validate(before,null);remaining=DecimalMoney.settled(remaining);
  if(remaining.signum()<=0||remaining.compareTo(original)>0)throw invalid();
  if(strategy==null)strategy=PrepaymentStrategy.REDUCE_PAYMENT;
  int count=before.size();
  if(strategy==PrepaymentStrategy.ADJUST_TERM){
   if(targetPeriods==null||targetPeriods<1||targetPeriods>=before.size())throw new RequestValidationException(Map.of("targetPeriods","请选择比当前未来期数更小的正整数"));
   count=targetPeriods;
  }else if(targetPeriods!=null)throw new RequestValidationException(Map.of("targetPeriods","仅调整期限策略可指定期数"));
  List<InstallmentDraft> result;
  try{
   if(strategy==PrepaymentStrategy.REDUCE_TERM)result=capped(before,remaining,rate,method,context);
   else if(method==RepaymentMethod.CUSTOM)result=custom(before,remaining,count,context,strategy==PrepaymentStrategy.REDUCE_PAYMENT);
   else result=new PreciseLoanScheduleCalculator().calculate(remaining,rate,before.subList(0,count).stream().map(InstallmentDraft::dueOn).toList(),method,context).stream().map(PreciseInstallmentDraft::legacy).toList();
  }catch(IllegalArgumentException e){throw new ResourceConflictException("LOAN_FIXED_TERM_INFEASIBLE","所选期数不能形成每期正现金的还款计划，请选择可行期数或一次结清");}
  LoanTermOptions.requireMinimum(result,minimum);return result;
 }
 private List<InstallmentDraft> custom(List<InstallmentDraft> before,BigDecimal principal,int count,LoanRoundingContext context,boolean preserveCaps){
  var rates=customRates(before);BigDecimal weights=BigDecimal.ZERO;
  for(int i=0;i<count;i++)weights=weights.add(weight(before.get(i)));
  BigDecimal originalRemaining=before.stream().map(InstallmentDraft::principalAmount).reduce(BigDecimal.ZERO,BigDecimal::add);
  BigDecimal cumP=BigDecimal.ZERO,cumI=BigDecimal.ZERO,paidP=BigDecimal.ZERO,paidI=BigDecimal.ZERO;
  List<InstallmentDraft> rows=new ArrayList<>();
  for(int i=0;i<count;i++){
   var old=before.get(i);var rate=rates.get(i);boolean last=i==count-1;
   // CUSTOM interest is contractual against settled opening principal after cent allocation.
   BigDecimal rawI=principal.subtract(paidP).multiply(rate.interest,MC).divide(rate.principal,MC);
   BigDecimal rawP=weights.signum()==0?BigDecimal.ZERO:principal.multiply(weight(old),MC).divide(weights,MC);
   BigDecimal pp=last?principal.subtract(cumP):PreciseLoanScheduleCalculator.stored(rawP),pi=PreciseLoanScheduleCalculator.stored(rawI);
   cumP=cumP.add(pp);cumI=cumI.add(pi);
   BigDecimal p=last?principal.subtract(paidP):cumP.setScale(2,RoundingMode.FLOOR).subtract(paidP);
   BigDecimal interest=context.preciseInterestPaid().add(cumI).setScale(2,RoundingMode.HALF_UP).subtract(context.actualInterestPaid()).subtract(paidI);
   originalRemaining=originalRemaining.subtract(old.principalAmount());
   if(!last){
    BigDecimal lower=interest.signum()==0?new BigDecimal("0.01"):BigDecimal.ZERO;
    if(preserveCaps)lower=lower.max(principal.subtract(paidP).subtract(originalRemaining));
    int reserve=1; // The last selected row must retain principal.
    for(int j=i+1;j<count-1;j++)if(rates.get(j).interest.signum()==0)reserve++;
    BigDecimal upper=principal.subtract(paidP).subtract(BigDecimal.valueOf(reserve,2));
    if(preserveCaps)upper=upper.min(old.principalAmount());
    if(lower.compareTo(upper)>0)throw new IllegalArgumentException("custom bounds cannot produce positive cash");
    p=p.max(lower).min(upper);
   }else if(preserveCaps&&p.compareTo(old.principalAmount())>0)throw new IllegalArgumentException("custom final principal exceeds original cap");
   paidP=paidP.add(p);paidI=paidI.add(interest);BigDecimal remaining=principal.subtract(paidP);
   requireCash(p,interest,remaining,last);
   rows.add(draft(i,old,p,interest,remaining,pp,pi,context.preciseInterestPaid().add(cumI).subtract(context.actualInterestPaid()).subtract(paidI),preserveCaps?"CUSTOM_BOUNDED_CENTS_V1":"CUSTOM_REALLOCATION_V1",rate));
  }
  return List.copyOf(rows);
 }
 private List<InstallmentDraft> capped(List<InstallmentDraft> before,BigDecimal principal,BigDecimal annualRate,RepaymentMethod method,LoanRoundingContext context){
  var rates=method==RepaymentMethod.CUSTOM?customRates(before):List.<Ratio>of();
  BigDecimal balance=principal,cumP=BigDecimal.ZERO,cumI=BigDecimal.ZERO,paidP=BigDecimal.ZERO,paidI=BigDecimal.ZERO;
  List<InstallmentDraft> result=new ArrayList<>();
  for(int i=0;i<before.size();i++){
   var old=before.get(i);Ratio ratio=method==RepaymentMethod.CUSTOM?rates.get(i):null;
   BigDecimal rawI=ratio==null?balance.multiply(annualRate,MC).divide(BigDecimal.valueOf(12),MC):principal.subtract(paidP).multiply(ratio.interest,MC).divide(ratio.principal,MC);
   BigDecimal pi=PreciseLoanScheduleCalculator.stored(rawI);cumI=cumI.add(pi);
   BigDecimal interest=context.preciseInterestPaid().add(cumI).setScale(2,RoundingMode.HALF_UP).subtract(context.actualInterestPaid()).subtract(paidI);
   BigDecimal cap=old.principalAmount().add(old.interestAmount()),remaining=principal.subtract(paidP);
   BigDecimal p=cap.subtract(interest).min(remaining);boolean last=p.compareTo(remaining)==0;
   BigDecimal rawP=cap.subtract(rawI),pp=last?principal.subtract(cumP):PreciseLoanScheduleCalculator.stored(rawP);
   paidP=paidP.add(p);paidI=paidI.add(interest);cumP=cumP.add(pp);remaining=principal.subtract(paidP);
   requireCash(p,interest,remaining,last);
   if(pp.signum()<0||(!last&&rawP.compareTo(balance)>=0))throw invalid();
   result.add(draft(i,old,p,interest,remaining,pp,pi,context.preciseInterestPaid().add(cumI).subtract(context.actualInterestPaid()).subtract(paidI),ratio==null?"CAPPED_CASH_V1":"CUSTOM_CAPPED_CASH_V1",ratio));
   if(last)return List.copyOf(result);balance=balance.subtract(rawP,MC);
  }
  throw invalid();
 }
 private static InstallmentDraft draft(int i,InstallmentDraft old,BigDecimal p,BigDecimal interest,BigDecimal remaining,BigDecimal pp,BigDecimal pi,BigDecimal carry,String policy,Ratio ratio){
  return new InstallmentDraft(i+1,old.dueOn(),DecimalMoney.toCents(p),DecimalMoney.toCents(interest),DecimalMoney.toCents(remaining),pp.setScale(12),pi.setScale(12),carry.setScale(12),policy,ratio==null?null:ratio.principal,ratio==null?null:ratio.interest);
 }
 private static void requireCash(BigDecimal p,BigDecimal i,BigDecimal remaining,boolean last){
  if(p.signum()<0||i.signum()<0||p.add(i).signum()<=0||remaining.signum()<0||(!last&&remaining.signum()==0))throw new IllegalArgumentException("invalid cash allocation");
 }
 private static BigDecimal weight(InstallmentDraft row){return row.precisePrincipalAmount()==null?row.principalAmount():row.precisePrincipalAmount();}
 private record Ratio(BigDecimal principal,BigDecimal interest){}
 private static List<Ratio> customRates(List<InstallmentDraft> before){
  BigDecimal remaining=before.stream().map(InstallmentDraft::principalAmount).reduce(BigDecimal.ZERO,BigDecimal::add);List<Ratio> rates=new ArrayList<>();
  for(var old:before){
   BigDecimal denominator=old.customRatePrincipalAmount()==null?remaining:old.customRatePrincipalAmount();
   BigDecimal numerator=old.customRateInterestAmount()==null?old.interestAmount():old.customRateInterestAmount();
   if(denominator.signum()<=0||numerator.signum()<0)throw invalid();
   rates.add(new Ratio(denominator.setScale(12),numerator.setScale(12)));remaining=remaining.subtract(old.principalAmount());
  }
  return rates;
 }
 private static BigDecimal validate(List<InstallmentDraft> before,LocalDate day){
  if(before==null||before.isEmpty()||before.size()>360)throw invalid();
  BigDecimal total=BigDecimal.ZERO;LocalDate previous=null;
  for(var row:before){
   if(row.principalCents()<0||row.interestCents()<0||row.principalAmount().add(row.interestAmount()).signum()<=0||(previous!=null&&!row.dueOn().isAfter(previous)))throw invalid();
   if(day!=null&&!row.dueOn().isAfter(day))throw new ResourceConflictException("LOAN_OVERDUE_INSTALLMENTS","请先确认所有到期未付期次，再进行部分提前还款");
   total=total.add(row.principalAmount());previous=row.dueOn();
  }
  return total;
 }
 private static ResourceConflictException invalid(){return new ResourceConflictException("LOAN_PLAN_INVALID","原逐期付款上限或贷款计划不足以偿还本金，请核对计划");}
}
