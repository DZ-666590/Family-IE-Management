package com.familyfinance.loan;
import java.time.LocalDate;
public record LoanPayoffQuote(String principalAmount,String dueInterestAmount,String interestAmount,String futureScheduledInterest,String cashAmount,long paymentAccountId,String availableBalance,LocalDate paidOn,String planToken){}
