package com.familyfinance.loan;

import com.familyfinance.household.Household;
import com.familyfinance.transaction.FinancialTransaction;
import jakarta.persistence.*;
import java.time.*;

@Entity @Table(name="loan_prepayments")
public class LoanPrepayment {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="household_id") private Household household;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="loan_id") private Loan loan;
 @Column(name="request_key",nullable=false) private String requestKey;
 @Column(name="amount_cents",nullable=false) private long amountCents;
 @Enumerated(EnumType.STRING) @Column(name="strategy") private PrepaymentStrategy strategy;
 @Column(name="interest_cents",nullable=false) private long interestCents;
 @Enumerated(EnumType.STRING) @Column(name="operation_kind",nullable=false) private LoanPrepaymentKind operationKind=LoanPrepaymentKind.PREPAYMENT;
 @Column(name="paid_on",nullable=false) private LocalDate paidOn;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="transaction_id") private FinancialTransaction transaction;
 @Column(name="created_at",nullable=false) private Instant createdAt;
 protected LoanPrepayment(){}
 LoanPrepayment(Loan loan,String requestKey,long amountCents,LocalDate paidOn,Instant createdAt){this.household=loan.getHousehold();this.loan=loan;this.requestKey=requestKey;this.amountCents=amountCents;this.paidOn=paidOn;this.createdAt=createdAt;}
 void attach(FinancialTransaction value){this.transaction=value;}
 void strategy(PrepaymentStrategy value){this.strategy=value;}
 public PrepaymentStrategy getStrategy(){return strategy;}
 void payoff(long interest){this.interestCents=interest;this.operationKind=LoanPrepaymentKind.PAYOFF;}
 public long getInterestCents(){return interestCents;} public LoanPrepaymentKind getOperationKind(){return operationKind;}
 public Long getId(){return id;} public long getAmountCents(){return amountCents;} public LocalDate getPaidOn(){return paidOn;} public FinancialTransaction getTransaction(){return transaction;}
}
