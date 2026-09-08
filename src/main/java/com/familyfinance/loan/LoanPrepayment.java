package com.familyfinance.loan;

import com.familyfinance.household.Household;
import com.familyfinance.transaction.FinancialTransaction;
import jakarta.persistence.*;
import java.time.*;
import java.math.BigDecimal;
import com.familyfinance.shared.DecimalMoney;

@Entity @Table(name="loan_prepayments")
public class LoanPrepayment {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="household_id") private Household household;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="loan_id") private Loan loan;
 @Column(name="request_key",nullable=false) private String requestKey;
 @Column(name="amount",nullable=false,precision=21,scale=2) private BigDecimal amount;
 @Enumerated(EnumType.STRING) @Column(name="strategy") private PrepaymentStrategy strategy;
 @Column(name="interest_amount",nullable=false,precision=21,scale=2) private BigDecimal interestAmount=DecimalMoney.fromCents(0);
 @Enumerated(EnumType.STRING) @Column(name="operation_kind",nullable=false) private LoanPrepaymentKind operationKind=LoanPrepaymentKind.PREPAYMENT;
 @Column(name="paid_on",nullable=false) private LocalDate paidOn;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="transaction_id") private FinancialTransaction transaction;
 @Column(name="created_at",nullable=false) private Instant createdAt;
 protected LoanPrepayment(){}
 LoanPrepayment(Loan loan,String requestKey,long amountCents,LocalDate paidOn,Instant createdAt){this.household=loan.getHousehold();this.loan=loan;this.requestKey=requestKey;this.amount=DecimalMoney.fromCents(amountCents);this.paidOn=paidOn;this.createdAt=createdAt;}
 void attach(FinancialTransaction value){this.transaction=value;}
 void strategy(PrepaymentStrategy value){this.strategy=value;}
 public PrepaymentStrategy getStrategy(){return strategy;}
 void payoff(long interest){this.interestAmount=DecimalMoney.fromCents(interest);this.operationKind=LoanPrepaymentKind.PAYOFF;}
 public BigDecimal getAmount(){return amount;} public BigDecimal getInterestAmount(){return interestAmount;}
 public long getInterestCents(){return DecimalMoney.toCents(interestAmount);} public LoanPrepaymentKind getOperationKind(){return operationKind;}
 public Long getId(){return id;} public long getAmountCents(){return DecimalMoney.toCents(amount);} public LocalDate getPaidOn(){return paidOn;} public FinancialTransaction getTransaction(){return transaction;}
}
