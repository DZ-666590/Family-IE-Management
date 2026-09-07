package com.familyfinance.loan;

import java.util.List;

public record LoanSchedulePage(
        List<LoanInstallmentResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {}
