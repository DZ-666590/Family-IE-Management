package com.familyfinance.loan;

import java.math.BigDecimal;
import java.math.RoundingMode;
import com.familyfinance.shared.DecimalMoney;

/** Full paid-interest baseline, never an isolated signed carry. */
public record LoanRoundingContext(BigDecimal preciseInterestPaid, BigDecimal actualInterestPaid) {
    public static final LoanRoundingContext ZERO = new LoanRoundingContext(BigDecimal.ZERO, BigDecimal.ZERO);
    public LoanRoundingContext {
        if (preciseInterestPaid == null || actualInterestPaid == null || preciseInterestPaid.signum()<0 || actualInterestPaid.signum()<0)
            throw new IllegalArgumentException("paid interest must be nonnegative");
        preciseInterestPaid=preciseInterestPaid.setScale(12,RoundingMode.UNNECESSARY);
        actualInterestPaid=DecimalMoney.settled(actualInterestPaid);
    }
    public LoanRoundingContext plus(BigDecimal preciseInterest, BigDecimal actualInterest) {
        return new LoanRoundingContext(preciseInterestPaid.add(preciseInterest),actualInterestPaid.add(actualInterest));
    }
}
