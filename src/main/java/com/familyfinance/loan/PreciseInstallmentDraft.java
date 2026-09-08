package com.familyfinance.loan;

import java.math.BigDecimal;
import java.time.LocalDate;
import com.familyfinance.shared.DecimalMoney;

public record PreciseInstallmentDraft(int installmentNo, LocalDate dueOn, BigDecimal principalAmount,
        BigDecimal interestAmount, BigDecimal remainingPrincipal, BigDecimal precisePrincipalAmount,
        BigDecimal preciseInterestAmount, BigDecimal interestCarryAmount, String roundingPolicy) {
    public BigDecimal cashAmount(){return principalAmount.add(interestAmount);}
    public BigDecimal principalRoundingAmount(){return principalAmount.subtract(precisePrincipalAmount);}
    public InstallmentDraft legacy(){return new InstallmentDraft(installmentNo,dueOn,DecimalMoney.toCents(principalAmount),
            DecimalMoney.toCents(interestAmount),DecimalMoney.toCents(remainingPrincipal),precisePrincipalAmount,preciseInterestAmount,interestCarryAmount,roundingPolicy);}
}
