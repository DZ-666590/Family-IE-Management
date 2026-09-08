package com.familyfinance.plugins.loancontract;

import java.util.List;
import java.util.Map;

public record LoanContractExtractionResponse(
        String documentName,
        LoanContractFields fields,
        Map<String, Double> confidence,
        List<String> warnings) {
}
