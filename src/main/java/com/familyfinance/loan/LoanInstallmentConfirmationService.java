package com.familyfinance.loan;

import com.familyfinance.category.*;
import com.familyfinance.accounting.*;
import com.familyfinance.family.*;
import com.familyfinance.household.*;
import com.familyfinance.ledger.*;
import com.familyfinance.shared.*;
import com.familyfinance.transaction.*;
import com.familyfinance.notification.NotificationService;
import java.time.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanInstallmentConfirmationService {
 private final LoanInstallmentRepository installments; private final FinancialTransactionRepository transactions; private final FinancialAccountRepository accounts; private final CategoryRepository categories; private final FamilyMemberRepository members; private final FamilyMutationAuthorization authorization; private final FamilyPermissionService permissions; private final NotificationService notifications; private final Clock clock;
 private final LoanAccountingService accounting; private final AccountingRequests requests; private final LoanRepository loans;
 LoanInstallmentConfirmationService(LoanInstallmentRepository installments, FinancialTransactionRepository transactions, FinancialAccountRepository accounts, CategoryRepository categories, FamilyMemberRepository members, FamilyMutationAuthorization authorization, FamilyPermissionService permissions, NotificationService notifications, Clock clock,LoanAccountingService accounting,AccountingRequests requests,LoanRepository loans) {this.installments=installments;this.transactions=transactions;this.accounts=accounts;this.categories=categories;this.members=members;this.authorization=authorization;this.permissions=permissions;this.notifications=notifications;this.clock=clock;this.accounting=accounting;this.requests=requests;this.loans=loans;}
 @Transactional public LoanInstallmentResponse confirm(Authentication authentication,long installmentId) {
  return confirm(authentication,installmentId,new LoanPaymentRequest(null),LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai"))),AccountingRequests.key(null));
 }
 @Transactional public LoanInstallmentResponse confirm(Authentication authentication,long installmentId,LoanPaymentRequest request,LocalDate defaultPaidOn,String key) {
  var access=authorization.requireCurrent(authentication); long householdId=access.context().householdId();
  String digest=requests.digest("LOAN_PAYMENT:"+installmentId,access.context().userId(),request);
  Long replay=requests.replay(householdId,key,digest);
  long loanId=installments.findCurrentLoanId(installmentId,householdId).orElseThrow(()->new ResourceNotFoundException("还款期次不存在"));
  // Lock/load the loan as a root entity before any installment association can populate a stale RR snapshot.
  Loan loan=loans.findLockedByIdAndHouseholdId(loanId,householdId).orElseThrow(()->new ResourceNotFoundException("贷款不存在"));
  LoanInstallment installment=installments.findLockedByIdAndHouseholdId(installmentId,householdId).orElseThrow(()->new ResourceNotFoundException("还款期次不存在"));
  Long assignee=loan.getAssignedUser()==null?null:loan.getAssignedUser().getId();
  if(assignee==null) throw new ResourceConflictException("INSTALLMENT_UNASSIGNED","贷款尚未分配确认人");
  permissions.requireCanConfirmAssignedOccurrence(access.context(),assignee);
  if(replay!=null)return currentResponse(installment,householdId);
  if(installment.getStatus()==LoanInstallmentStatus.PAID) {
   accounting.requireInitialized(loan);
   currentResponse(installment,householdId);
   if(request!=null&&request.paidOn()!=null&&!request.paidOn().equals(installment.getConfirmedTransaction().getOccurredOn()))
    throw new ResourceConflictException("LOAN_PAYMENT_ALREADY_POSTED","还款已入账，不能改变实际付款日期");
   requests.record(householdId,key,digest,installmentId);return LoanInstallmentResponse.from(installment);
  }
  if(installment.getStatus()==LoanInstallmentStatus.CANCELLED) throw new ResourceConflictException("INSTALLMENT_CANCELLED","还款期次已取消");
  if(installment.getDueOn().isAfter(LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai"))))) throw new ResourceConflictException("INSTALLMENT_NOT_DUE","还款期次尚未到期");
  if(loan.getStatus()!=LoanStatus.ACTIVE) throw new ResourceConflictException("LOAN_CLOSED","贷款已归档或结清");
  if(loan.getAssignedUser().getStatus()!=AppUserStatus.ACTIVE) throw stale();
  if(installments.findAllLockedByLoanIdAndHouseholdIdOrderByInstallmentNo(loan.getId(),householdId).stream().anyMatch(i->i.getStatus()==LoanInstallmentStatus.PENDING&&i.getInstallmentNo()<installment.getInstallmentNo()))
   throw new ResourceConflictException("LOAN_PAYMENT_ORDER","请先确认之前的待还期次");
  LocalDate paidOn=request==null||request.paidOn()==null?defaultPaidOn:request.paidOn();
  accounting.requirePaymentDate(loan,paidOn);
  if(paidOn.isBefore(installment.getDueOn()))throw new ResourceConflictException("INSTALLMENT_NOT_DUE","实际还款日期不能早于本期到期日；提前还本请使用提前还款");
  FinancialTransaction transaction=FinancialTransaction.loanPayment(access.household(),account(loan,householdId),access.membership().getUser(),member(loan,householdId),category(loan,householdId),Math.addExact(installment.getPrincipalCents(),installment.getInterestCents()),paidOn,installmentId,clock.instant());
  transaction.loanSplit(installment.getPrincipalCents(),installment.getInterestCents());transactions.saveAndFlush(transaction);
  accounting.pay(loan,transaction,installment.getPrincipalCents(),installment.getInterestCents(),key);
  installment.confirm(transaction); loan.applyPrincipalPayment(installment.getPrincipalCents(),clock.instant());accounting.requireBalance(loan);notifications.resolveReference(householdId,"LOAN_INSTALLMENT",installmentId); installments.flush();
  requests.record(householdId,key,digest,installmentId);return LoanInstallmentResponse.from(installment);
 }
 private FinancialAccount account(Loan loan,long h){return accounts.findLockedByIdAndHouseholdId(loan.getPaymentAccount().getId(),h).orElseThrow(LoanInstallmentConfirmationService::stale);}
 private LoanInstallmentResponse currentResponse(LoanInstallment installment,long household){
  if(installment.getConfirmedTransaction()!=null)transactions.findLockedByIdAndHouseholdId(installment.getConfirmedTransaction().getId(),household).orElseThrow(()->new ResourceConflictException("ACCOUNTING_BALANCE_MISMATCH","已付款期次缺少原交易记录"));
  return LoanInstallmentResponse.from(installment);
 }
 private Category category(Loan loan,long h){return categories.findByIdAndHouseholdId(loan.getPaymentCategory().getId(),h).filter(c->c.getKind()==TransactionKind.EXPENSE).orElseThrow(LoanInstallmentConfirmationService::stale);}
 private FamilyMember member(Loan loan,long h){if(loan.getMember()!=null)return members.findByIdAndHouseholdId(loan.getMember().getId(),h).orElseThrow(LoanInstallmentConfirmationService::stale);return members.findFirstByHouseholdIdAndLinkedUserId(h,loan.getAssignedUser().getId()).orElseThrow(LoanInstallmentConfirmationService::stale);}
 private static ResourceConflictException stale(){return new ResourceConflictException("STALE_REFERENCE","贷款关联的账户、分类或成员已失效");}
}
