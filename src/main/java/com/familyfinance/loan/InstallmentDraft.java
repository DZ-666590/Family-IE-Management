package com.familyfinance.loan;

import java.time.LocalDate;
import java.math.BigDecimal;
import com.familyfinance.shared.DecimalMoney;

public record InstallmentDraft(int installmentNo, LocalDate dueOn, long principalCents, long interestCents,
                               long remainingPrincipalCents, BigDecimal precisePrincipalAmount,
                               BigDecimal preciseInterestAmount, BigDecimal interestCarryAmount, String roundingPolicy,
                               BigDecimal customRatePrincipalAmount,BigDecimal customRateInterestAmount) {
    public InstallmentDraft(int no,LocalDate date,long principal,long interest,long remaining,BigDecimal pp,BigDecimal pi,BigDecimal carry,String policy){this(no,date,principal,interest,remaining,pp,pi,carry,policy,null,null);}
    public InstallmentDraft(int no,LocalDate date,long principal,long interest,long remaining){this(no,date,principal,interest,remaining,null,null,null,null);}
    public BigDecimal principalAmount(){return DecimalMoney.fromCents(principalCents);}
    public BigDecimal interestAmount(){return DecimalMoney.fromCents(interestCents);}
    public InstallmentDraft renumber(int no){return new InstallmentDraft(no,dueOn,principalCents,interestCents,remainingPrincipalCents,precisePrincipalAmount,preciseInterestAmount,interestCarryAmount,roundingPolicy,customRatePrincipalAmount,customRateInterestAmount);}
}
