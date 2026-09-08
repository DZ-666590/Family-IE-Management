package com.familyfinance.loan;

import com.familyfinance.shared.Money;
import java.time.LocalDate;
import java.util.List;

public record LoanPrepaymentPreview(PrepaymentStrategy strategy,String principalAmount,String cashAmount,long paymentAccountId,String availableBalance,LocalDate paidOn,String planToken,ScheduleSummary before,ScheduleSummary after) {
 public record Period(int installmentNo,LocalDate dueOn,String principal,String interest,String paymentAmount,String remainingPrincipal,String precisePrincipalAmount,String preciseInterestAmount,String interestCarryAmount,String roundingPolicy,String principalRoundingAmount){}
 public record ScheduleSummary(String principalAmount,int periodCount,LocalDate maturityOn,LocalDate nextPaymentOn,String nextPaymentAmount,String totalInterest,String repaymentTotal,List<Period> schedule){}
 static ScheduleSummary summarize(List<InstallmentDraft> rows){
  long principal=0,interest=0;for(var row:rows){principal=Math.addExact(principal,row.principalCents());interest=Math.addExact(interest,row.interestCents());}
  return new ScheduleSummary(Money.formatCents(principal),rows.size(),rows.get(rows.size()-1).dueOn(),rows.get(0).dueOn(),Money.formatCents(Math.addExact(rows.get(0).principalCents(),rows.get(0).interestCents())),Money.formatCents(interest),Money.formatCents(Math.addExact(principal,interest)),rows.stream().map(r->new Period(r.installmentNo(),r.dueOn(),Money.formatCents(r.principalCents()),Money.formatCents(r.interestCents()),Money.formatCents(Math.addExact(r.principalCents(),r.interestCents())),Money.formatCents(r.remainingPrincipalCents()),text(r.precisePrincipalAmount()),text(r.preciseInterestAmount()),text(r.interestCarryAmount()),r.roundingPolicy(),r.precisePrincipalAmount()==null?null:text(r.principalAmount().subtract(r.precisePrincipalAmount())))).toList());
 }
 private static String text(java.math.BigDecimal value){return value==null?null:value.toPlainString();}
}
