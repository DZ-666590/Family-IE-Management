package com.familyfinance.loan;
import com.familyfinance.shared.Money;
public record LoanPrepaymentResponse(long id,long transactionId,String amount,String remainingPrincipal,LoanStatus status,java.time.LocalDate paidOn,String principalAmount,String interestAmount) { static LoanPrepaymentResponse from(LoanPrepayment p,Loan loan){return new LoanPrepaymentResponse(p.getId(),p.getTransaction().getId(),Money.formatCents(p.getAmountCents()),Money.formatCents(loan.getCurrentPrincipalCents()),loan.getStatus(),p.getPaidOn(),Money.formatCents(p.getAmountCents()),"0.00");} }
