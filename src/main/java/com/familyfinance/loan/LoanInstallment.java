package com.familyfinance.loan;
import com.familyfinance.household.Household;
import com.familyfinance.transaction.FinancialTransaction;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.math.BigDecimal;
import com.familyfinance.shared.DecimalMoney;
@Entity @Table(name="loan_installments")
public class LoanInstallment {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="loan_id") private Loan loan;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="household_id") private Household household;
 @Column(name="installment_no",nullable=false) private int installmentNo;
 @Column(name="due_on",nullable=false) private LocalDate dueOn;
 @Column(name="principal_amount",nullable=false,precision=21,scale=2) private BigDecimal principalAmount;
 @Column(name="interest_amount",nullable=false,precision=21,scale=2) private BigDecimal interestAmount;
 @Column(name="precise_principal_amount",precision=30,scale=12) private BigDecimal precisePrincipalAmount;
 @Column(name="precise_interest_amount",precision=30,scale=12) private BigDecimal preciseInterestAmount;
 @Column(name="interest_carry_amount",precision=30,scale=12) private BigDecimal interestCarryAmount;
 @Column(name="rounding_policy") private String roundingPolicy;
 @Column(name="custom_rate_principal_amount",precision=30,scale=12) private BigDecimal customRatePrincipalAmount;
 @Column(name="custom_rate_interest_amount",precision=30,scale=12) private BigDecimal customRateInterestAmount;
 @Enumerated(EnumType.STRING) @Column(nullable=false) private LoanInstallmentStatus status=LoanInstallmentStatus.PENDING;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="confirmed_transaction_id") private FinancialTransaction confirmedTransaction;
 @Column(name="cancelled_by_prepayment_id") private Long cancelledByPrepaymentId;
 protected LoanInstallment(){} LoanInstallment(Loan loan, InstallmentDraft d){this.loan=loan;this.household=loan.getHousehold();installmentNo=d.installmentNo();dueOn=d.dueOn();principalAmount=d.principalAmount();interestAmount=d.interestAmount();precisePrincipalAmount=d.precisePrincipalAmount();preciseInterestAmount=d.preciseInterestAmount();interestCarryAmount=d.interestCarryAmount();roundingPolicy=d.roundingPolicy();customRatePrincipalAmount=d.customRatePrincipalAmount();customRateInterestAmount=d.customRateInterestAmount();}
 public Long getId(){return id;} public Loan getLoan(){return loan;} public Household getHousehold(){return household;} public int getInstallmentNo(){return installmentNo;} public LocalDate getDueOn(){return dueOn;} public long getPrincipalCents(){return DecimalMoney.toCents(principalAmount);} public long getInterestCents(){return DecimalMoney.toCents(interestAmount);} public LoanInstallmentStatus getStatus(){return status;} public FinancialTransaction getConfirmedTransaction(){return confirmedTransaction;}
 public BigDecimal getPrincipalAmount(){return principalAmount;} public BigDecimal getInterestAmount(){return interestAmount;}
 public BigDecimal getPrecisePrincipalAmount(){return precisePrincipalAmount;} public BigDecimal getPreciseInterestAmount(){return preciseInterestAmount;}
 public BigDecimal getInterestCarryAmount(){return interestCarryAmount;} public String getRoundingPolicy(){return roundingPolicy;}
 public BigDecimal getCustomRatePrincipalAmount(){return customRatePrincipalAmount;} public BigDecimal getCustomRateInterestAmount(){return customRateInterestAmount;}
 void confirm(FinancialTransaction transaction){ if(status==LoanInstallmentStatus.PENDING){confirmedTransaction=transaction;status=LoanInstallmentStatus.PAID;} }
 void cancel(){if(status==LoanInstallmentStatus.PENDING)status=LoanInstallmentStatus.CANCELLED;}
 void cancel(long event){if(status==LoanInstallmentStatus.PENDING){cancelledByPrepaymentId=event;cancel();}}
 public Long getCancelledByPrepaymentId(){return cancelledByPrepaymentId;}
}
