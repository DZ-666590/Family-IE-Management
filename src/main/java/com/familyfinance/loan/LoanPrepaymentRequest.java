package com.familyfinance.loan;
import java.time.LocalDate;
public record LoanPrepaymentRequest(String amount, LocalDate paidOn, String idempotencyKey,Long paymentAccountId,PrepaymentStrategy strategy,String planToken) {
 public LoanPrepaymentRequest(String amount,LocalDate paidOn,String key,Long account){this(amount,paidOn,key,account,null,null);}
 public LoanPrepaymentRequest(String amount,LocalDate paidOn,String key){this(amount,paidOn,key,null);}
}
