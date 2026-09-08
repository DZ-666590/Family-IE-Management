package com.familyfinance.loan;
import java.util.*;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.security.*;
import com.familyfinance.loan.LoanPlanToken.Period;
/** Frozen pre-calculation-revision encoding for old quote/receipt compatibility. */
final class LegacyLoanPlanTokenV1 {
 static String token(Loan loan,List<Period> periods,String operation,LocalDate paidOn,long account,long interest,String extra){
  StringBuilder value=new StringBuilder("loan-plan-v1|").append(loan.getHousehold().getId()).append('|').append(loan.getId()).append('|').append(loan.getCurrentPrincipalCents()).append('|').append(loan.getStatus()).append('|').append(loan.getAccountingOn()).append('|').append(loan.getLastPaymentOn()).append('|').append(loan.getAnnualRate().toPlainString()).append('|').append(loan.getRepaymentMethod()).append('|').append(loan.getPaymentCategory().getId()).append('|').append(loan.getMember()==null?null:loan.getMember().getId()).append('|').append(operation).append('|').append(paidOn).append('|').append(account).append('|').append(interest).append('|').append(extra);
  value.append("|precision-v1|").append(loan.getRepaymentPolicyRevision()).append('|').append(loan.getMinimumInstallmentAmount()).append('|').append(loan.getRepaymentPolicySource());
  for(var p:periods)value.append('|').append(p.id()).append(':').append(p.installmentNo()).append(':').append(p.dueOn()).append(':').append(p.principalCents()).append(':').append(p.interestCents()).append(':').append(p.precisePrincipalAmount()).append(':').append(p.preciseInterestAmount()).append(':').append(p.interestCarryAmount()).append(':').append(p.roundingPolicy()).append(':').append(p.customRatePrincipalAmount()).append(':').append(p.customRateInterestAmount());
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
 }
}
