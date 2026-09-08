package com.familyfinance.loan;

import java.time.Instant;
import java.util.List;

/** Immutable operation snapshot; only its child transactions contribute to the ledger and paid totals. */
public record LoanRepaymentBatch(long id, long loanId, LoanStatus status, String remainingPrincipal,
        Instant recordedAt, LoanRepaymentPreview preview, List<Child> children) {
    public record Child(String sourceType, long sourceId, long transactionId,
            String principalAmount, String interestAmount, String cashAmount) {}
}
