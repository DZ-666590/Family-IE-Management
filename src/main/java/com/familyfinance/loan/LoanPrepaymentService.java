package com.familyfinance.loan;

import com.familyfinance.category.*;
import com.familyfinance.accounting.*;
import com.familyfinance.family.*;
import com.familyfinance.household.*;
import com.familyfinance.ledger.*;
import com.familyfinance.shared.*;
import com.familyfinance.transaction.*;
import java.time.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanPrepaymentService {
 private final LoanRepository loans; private final LoanPrepaymentRepository prepayments; private final FinancialTransactionRepository transactions; private final FinancialAccountRepository accounts; private final CategoryRepository categories; private final FamilyMemberRepository members; private final FamilyMutationAuthorization authorization; private final Clock clock; private final AmortizationCalculator calculator=new AmortizationCalculator();
 private final LoanTotalsService totals; private final LoanPlanToken plans; private final LoanAccountingService accounting; private final AccountingRequests requests; private final LoanInstallmentRepository installments;
 LoanPrepaymentService(LoanRepository loans,LoanPrepaymentRepository prepayments,FinancialTransactionRepository transactions,FinancialAccountRepository accounts,CategoryRepository categories,FamilyMemberRepository members,FamilyMutationAuthorization authorization,Clock clock,LoanAccountingService accounting,AccountingRequests requests,LoanInstallmentRepository installments,LoanTotalsService totals,LoanPlanToken plans){this.totals=totals;this.plans=plans;this.loans=loans;this.prepayments=prepayments;this.transactions=transactions;this.accounts=accounts;this.categories=categories;this.members=members;this.authorization=authorization;this.clock=clock;this.accounting=accounting;this.requests=requests;this.installments=installments;}
 @Transactional public LoanPrepaymentResponse prepay(Authentication authentication,long loanId,LoanPrepaymentRequest request){
  var access=authorization.requireAdmin(authentication); if(request==null||request.idempotencyKey()==null||request.idempotencyKey().trim().isEmpty()||request.idempotencyKey().length()>100)throw new RequestValidationException(Map.of("idempotencyKey","幂等键不能为空且不超过100个字符"));
  String key=AccountingRequests.key(request.idempotencyKey());long h=access.context().householdId();String digest=requests.digest("LOAN_PREPAYMENT:"+loanId,access.context().userId(),request);
  Long replay=requests.replay(h,key,digest);
  Loan loan=loans.findLockedByIdAndHouseholdId(loanId,h).orElseThrow(()->new ResourceNotFoundException("贷款不存在"));
  if(replay!=null){
   LoanPrepayment original=prepayments.findLockedByIdAndHouseholdId(replay,h).orElseThrow(()->new ResourceNotFoundException("提前还款记录不存在"));
   transactions.findLockedByIdAndHouseholdId(original.getTransaction().getId(),h).orElseThrow(()->new ResourceNotFoundException("原提前还款交易不存在"));
   return LoanPrepaymentResponse.from(original,loan,totals.read(loan,true));
  }
  if(loan.getStatus()!=LoanStatus.ACTIVE)throw new ResourceConflictException("LOAN_CLOSED","贷款已归档或结清"); long amount=parse(request.amount()); if(amount>loan.getCurrentPrincipalCents())throw new RequestValidationException(Map.of("amount","提前还款金额不能超过剩余本金")); LocalDate paidOn=request.paidOn();if(paidOn==null||paidOn.isAfter(LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")))))throw new RequestValidationException(Map.of("paidOn","还款日期不能为空且不能晚于今天"));
  accounting.requirePaymentDate(loan,paidOn);
  if(amount==loan.getCurrentPrincipalCents()&&plans.dueInterest(plans.pending(h,loanId,true),paidOn)>0)throw new ResourceConflictException("LOAN_PAYOFF_REQUIRED","存在到期未付利息，请使用一次结清核对实际扣款");
  LoanPrepayment prepayment=prepayments.saveAndFlush(new LoanPrepayment(loan,key,amount,paidOn,clock.instant()));
  FinancialTransaction transaction=FinancialTransaction.loanPrepayment(access.household(),account(loan,access.context().householdId(),request.paymentAccountId()),access.membership().getUser(),member(loan,access.context().householdId()),category(loan,access.context().householdId()),amount,paidOn,prepayment.getId(),clock.instant());
  transaction.loanSplit(amount,0);transactions.saveAndFlush(transaction);
  accounting.pay(loan,transaction,amount,0,key);
  prepayment.attach(transaction);
  var currentSchedule=installments.findAllLockedByLoanIdAndHouseholdIdOrderByInstallmentNo(loanId,h);
  int remainingTerms=(int)currentSchedule.stream().filter(i->i.getStatus()==LoanInstallmentStatus.PENDING).count();
  int nextNo=currentSchedule.stream().mapToInt(LoanInstallment::getInstallmentNo).max().orElse(0)+1;
  loan.applyPrincipalPayment(amount,clock.instant());accounting.requireBalance(loan);currentSchedule.forEach(i->i.cancel(prepayment.getId())); if(loan.getCurrentPrincipalCents()>0)regenerate(loan,paidOn,remainingTerms,nextNo); loans.flush(); requests.record(h,key,digest,prepayment.getId()); return LoanPrepaymentResponse.from(prepayment,loan,totals.read(loan,true));
 }
 private void regenerate(Loan loan,LocalDate paidOn,int remainingTerms,int no){List<InstallmentDraft> base=calculator.calculate(loan.getCurrentPrincipalCents(),loan.getAnnualRate(),Math.max(1,remainingTerms),paidOn,loan.getRepaymentMethod()==RepaymentMethod.CUSTOM?RepaymentMethod.EQUAL_PRINCIPAL:loan.getRepaymentMethod());installments.saveAll(base.stream().map(d->new LoanInstallment(loan,new InstallmentDraft(no+d.installmentNo()-1,d.dueOn(),d.principalCents(),d.interestCents(),d.remainingPrincipalCents()))).toList());}
 private static long parse(String amount){try{long value=Money.parseCents(amount);if(value<=0)throw new IllegalArgumentException();return value;}catch(IllegalArgumentException e){throw new RequestValidationException(Map.of("amount","提前还款金额必须为正且最多两位小数"));}}
 private FinancialAccount account(Loan l,long h,Long actual){return accounts.findLockedByIdAndHouseholdId(actual==null?l.getPaymentAccount().getId():actual,h).orElseThrow(LoanPrepaymentService::stale);} private Category category(Loan l,long h){return categories.findByIdAndHouseholdId(l.getPaymentCategory().getId(),h).filter(x->x.getKind()==TransactionKind.EXPENSE).orElseThrow(LoanPrepaymentService::stale);} private FamilyMember member(Loan l,long h){if(l.getMember()!=null)return members.findByIdAndHouseholdId(l.getMember().getId(),h).orElseThrow(LoanPrepaymentService::stale);if(l.getAssignedUser()!=null)return members.findFirstByHouseholdIdAndLinkedUserId(h,l.getAssignedUser().getId()).orElseThrow(LoanPrepaymentService::stale);throw stale();} private static ResourceConflictException stale(){return new ResourceConflictException("STALE_REFERENCE","贷款关联的账户、分类或成员已失效");}
}
