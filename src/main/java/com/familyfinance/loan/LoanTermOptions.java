package com.familyfinance.loan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import com.familyfinance.shared.DecimalMoney;

/** Every candidate is evaluated; callers must not assume allowed counts form an interval. */
public final class LoanTermOptions {
 public enum EvaluationStatus { FEASIBLE, INFEASIBLE, UNDETERMINED }
 public record Option(int periods,boolean allowed,String reason,String firstPaymentAmount,String roundingPolicy,EvaluationStatus evaluationStatus){
  public Option(int periods,boolean allowed,String reason,String firstPaymentAmount,String roundingPolicy){
   this(periods,allowed,reason,firstPaymentAmount,roundingPolicy,allowed?EvaluationStatus.FEASIBLE:"LOAN_PLAN_SEARCH_LIMIT".equals(reason)?EvaluationStatus.UNDETERMINED:EvaluationStatus.INFEASIBLE);
  }
 }
 public List<Option> evaluate(BigDecimal principal,BigDecimal annualRate,List<LocalDate> dates,RepaymentMethod method,
        LoanRoundingContext context,BigDecimal minimumInstallmentAmount){
  List<Option> result=new ArrayList<>();
  for(int count=1;count<=dates.size();count++){
   try{
    var rows=new PreciseLoanScheduleCalculator().calculate(principal,annualRate,dates.subList(0,count),method,context);
    String reason=minimumViolation(rows.stream().map(PreciseInstallmentDraft::cashAmount).toList(),minimumInstallmentAmount);
    result.add(new Option(count,reason==null,reason,DecimalMoney.format(rows.get(0).cashAmount()),rows.get(0).roundingPolicy()));
   }catch(IllegalArgumentException e){result.add(new Option(count,false,method==RepaymentMethod.CUSTOM?"CUSTOM_PLAN_REQUIRED":"NO_POSITIVE_CASH_SCHEDULE",null,null));}
  }
  return List.copyOf(result);
 }
 public List<Option> evaluateCustom(List<InstallmentDraft> before,BigDecimal principal,LoanRoundingContext context,BigDecimal minimum){
  return evaluateCustom(before,principal,context,minimum,LoanPlanningBudget.standard());
 }
 public List<Option> evaluateCustom(List<InstallmentDraft> before,BigDecimal principal,LoanRoundingContext context,BigDecimal minimum,LoanPlanningBudget budget){
  List<Option> result=new ArrayList<>();
  for(int count=1;count<=before.size();count++){
   try{
    var rows=new LoanPrepaymentPlanner().planRemaining(before,principal,BigDecimal.ZERO,RepaymentMethod.CUSTOM,
      count==before.size()?PrepaymentStrategy.REDUCE_PAYMENT:PrepaymentStrategy.ADJUST_TERM,count==before.size()?null:count,context,minimum,budget);
    result.add(new Option(count,true,null,DecimalMoney.format(rows.get(0).principalAmount().add(rows.get(0).interestAmount())),rows.get(0).roundingPolicy()));
   }catch(com.familyfinance.shared.ResourceConflictException e){result.add(new Option(count,false,e.code(),null,null));}
  }
  return List.copyOf(result);
 }
 static String minimumViolation(List<BigDecimal> cash,BigDecimal minimum){
  if(minimum!=null)for(int i=0;i<cash.size()-1;i++)if(cash.get(i).compareTo(minimum)<0)return "BELOW_CONTRACT_MINIMUM";
  return null;
 }
 public static void requireMinimum(List<InstallmentDraft> rows,BigDecimal minimum){
  if(minimumViolation(rows.stream().map(r->r.principalAmount().add(r.interestAmount())).toList(),minimum)!=null)
   throw new com.familyfinance.shared.ResourceConflictException("LOAN_CONTRACT_MINIMUM","新计划常规付款低于合同最低金额，请选择可行期数");
 }
}
