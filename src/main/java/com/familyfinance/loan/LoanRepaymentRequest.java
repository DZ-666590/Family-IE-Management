package com.familyfinance.loan;

import java.time.LocalDate;

/** This full-body receipt contract is independent of the legacy /prepay request. */
public record LoanRepaymentRequest(String additionalPrincipal, LocalDate paidOn, Long paymentAccountId,
        PrepaymentStrategy strategy, Integer targetPeriods, String planToken, String idempotencyKey) {}
