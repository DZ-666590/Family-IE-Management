package com.familyfinance.loan;
import com.familyfinance.shared.DecimalMoney;
public record LoanRepaymentPolicy(String minimumInstallmentAmount,String sourceNote,long revision){
 static LoanRepaymentPolicy from(Loan loan){return new LoanRepaymentPolicy(loan.getMinimumInstallmentAmount()==null?null:DecimalMoney.format(loan.getMinimumInstallmentAmount()),loan.getRepaymentPolicySource(),loan.getRepaymentPolicyRevision());}
}
