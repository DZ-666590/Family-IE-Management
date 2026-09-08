package com.familyfinance.plugins.loancontract;

import com.familyfinance.loan.LoanType;
import com.familyfinance.loan.RepaymentMethod;
import java.time.LocalDate;

public record LoanContractFields(
        String suggestedName,
        LoanType loanType,
        String principal,
        String annualRatePercent,
        Integer termMonths,
        RepaymentMethod repaymentMethod,
        LocalDate startOn) {
}
