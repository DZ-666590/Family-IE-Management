package com.familyfinance.loan;
import java.time.LocalDate;
public record LoanPayoffRequest(LocalDate paidOn,Long paymentAccountId,String interestAmount,String planToken,String idempotencyKey){}
