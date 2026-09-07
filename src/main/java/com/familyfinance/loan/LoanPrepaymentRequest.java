package com.familyfinance.loan;
import java.time.LocalDate;
public record LoanPrepaymentRequest(String amount, LocalDate paidOn, String idempotencyKey,Long paymentAccountId) {
 public LoanPrepaymentRequest(String amount,LocalDate paidOn,String key){this(amount,paidOn,key,null);}
}
