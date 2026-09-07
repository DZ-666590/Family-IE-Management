package com.familyfinance.loan;

import java.time.LocalDate;

/** Actual cash payment date; the installment's due date is retained separately. */
public record LoanPaymentRequest(LocalDate paidOn,Long paymentAccountId) {
 public LoanPaymentRequest(LocalDate paidOn){this(paidOn,null);}
}
