package com.familyfinance.loan;

import com.familyfinance.family.*;
import com.familyfinance.household.*;
import com.familyfinance.ledger.*;
import com.familyfinance.shared.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class LoanRepaymentPolicyService {
 private final LoanRepository loans;private final CurrentMembership current;private final FamilyPermissionService permissions;
 private final FamilyMutationAuthorization mutations;private final JdbcTemplate jdbc;private final LoanPlanToken plans;private final FinancialAccountRepository accounts;private final Clock clock;
 public LoanRepaymentPolicyService(LoanRepository loans,CurrentMembership current,FamilyPermissionService permissions,FamilyMutationAuthorization mutations,
        JdbcTemplate jdbc,LoanPlanToken plans,FinancialAccountRepository accounts,Clock clock){
  this.loans=loans;this.current=current;this.permissions=permissions;this.mutations=mutations;this.jdbc=jdbc;this.plans=plans;this.accounts=accounts;this.clock=clock;
 }
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public LoanRepaymentPolicy get(Authentication a,long id){var c=current.require(a);permissions.requireAdmin(c);return LoanRepaymentPolicy.from(find(id,c.householdId()));}
 @Transactional
 public LoanRepaymentPolicy update(Authentication a,long id,LoanRepaymentPolicyRequest r){
  var access=mutations.requireAdmin(a);long h=access.context().householdId();
  var loan=loans.findLockedByIdAndHouseholdId(id,h).orElseThrow(()->new ResourceNotFoundException("贷款不存在"));
  if(r==null||r.revision()==null)throw new RequestValidationException(Map.of("revision","请提供当前规则版本"));
  if(r.revision()!=loan.getRepaymentPolicyRevision())throw new ResourceConflictException("LOAN_POLICY_CHANGED","规则已变化，请刷新后重试");
  BigDecimal minimum=r.minimumInstallmentAmount()==null?null:positive(r.minimumInstallmentAmount(),"minimumInstallmentAmount");
  String source=r.sourceNote()==null?null:r.sourceNote().trim();
  if(source!=null&&source.length()>1000)throw new RequestValidationException(Map.of("sourceNote","来源说明不能超过1000字"));
  loan.repaymentPolicy(minimum,source);loans.flush();
  jdbc.update("insert into loan_repayment_policy_history(loan_id,household_id,revision,minimum_installment_amount,source_note,changed_by,changed_at) values(?,?,?,?,?,?,?)",
        id,h,loan.getRepaymentPolicyRevision(),minimum,source,access.context().userId(),java.sql.Timestamp.from(clock.instant()));
  return LoanRepaymentPolicy.from(loan);
 }
 public record Projection(List<LoanPlanToken.Period> due,List<LoanPlanToken.Period> future,BigDecimal duePrincipal,
        BigDecimal dueInterest,BigDecimal remainingPrincipal,LoanRoundingContext roundingContext){}
 /** Pure projection reused by combined repayment: paid histories enter once, then selected dues enter once. */
 public Projection project(Loan loan,List<LoanPlanToken.Period> pending,LocalDate paidOn,BigDecimal additional,LoanRoundingContext paidContext){
  additional=DecimalMoney.settled(additional);
  if(additional.signum()<0)throw new RequestValidationException(Map.of("additionalPrincipal","额外本金不能为负"));
  BigDecimal total=BigDecimal.ZERO,duePrincipal=BigDecimal.ZERO,dueInterest=BigDecimal.ZERO;
  List<LoanPlanToken.Period> due=new ArrayList<>(),future=new ArrayList<>();var context=paidContext;
  LocalDate previous=null;
  for(var row:pending){
   if(row.principalCents()<0||row.interestCents()<0||row.principalAmount().add(row.interestAmount()).signum()<=0||(previous!=null&&!row.dueOn().isAfter(previous)))
    throw new ResourceConflictException("LOAN_PLAN_INVALID","贷款计划金额或日期异常");
   previous=row.dueOn();total=total.add(row.principalAmount());
   if(!row.dueOn().isAfter(paidOn)){due.add(row);duePrincipal=duePrincipal.add(row.principalAmount());dueInterest=dueInterest.add(row.interestAmount());context=context.plus(row.settledPrecisionInterest(),row.interestAmount());}
   else future.add(row);
  }
  if(total.compareTo(loan.getCurrentPrincipalAmount())!=0)throw new ResourceConflictException("LOAN_PLAN_INVALID","待还本金与贷款余额不一致");
  BigDecimal remaining=loan.getCurrentPrincipalAmount().subtract(duePrincipal).subtract(additional);
  if(remaining.signum()<0)throw new RequestValidationException(Map.of("additionalPrincipal","额外本金超过到期款扣除后的本金"));
  return new Projection(List.copyOf(due),List.copyOf(future),duePrincipal.setScale(2),dueInterest.setScale(2),remaining.setScale(2),context);
 }
 public record TermOptionsResponse(String remainingPrincipal,String duePrincipal,String dueInterest,LoanRepaymentPolicy policy,List<LoanTermOptions.Option> options){}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public TermOptionsResponse options(Authentication a,long id,String raw,LocalDate day,Long accountId){
  var c=current.require(a);permissions.requireAdmin(c);long h=c.householdId();var loan=find(id,h);
  if(day==null||day.isAfter(LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")))))throw new RequestValidationException(Map.of("paidOn","日期不能为空或晚于今天"));
  if(loan.getStatus()!=LoanStatus.ACTIVE)throw new ResourceConflictException("LOAN_CLOSED","贷款已归档或结清");
  if(loan.getAccountingOn()==null||jdbc.queryForObject("select count(*) from ledger_sources where household_id=? and source_type=? and source_id=? and current_journal_id is not null",Long.class,h,LoanAccountingService.source(loan),id)==0)throw new ResourceConflictException("ACCOUNTING_NOT_INITIALIZED","贷款尚未确认账务期初");
  if(day.isBefore(loan.getAccountingOn()))throw new ResourceConflictException("LOAN_PAYMENT_BEFORE_OPENING","日期不能早于贷款开账");
  if(loan.getLastPaymentOn()!=null&&day.isBefore(loan.getLastPaymentOn()))throw new ResourceConflictException("LOAN_PAYMENT_CHRONOLOGY","日期不能早于最近付款");
  var balance=jdbc.queryForList("select balance_amount from ledger_accounts where household_id=? and account_code=?",BigDecimal.class,h,"LOAN:"+id);
  if(balance.size()!=1||balance.get(0).compareTo(loan.getCurrentPrincipalAmount())!=0)throw new ResourceConflictException("ACCOUNTING_BALANCE_MISMATCH","贷款余额不一致");
  var account=accounts.findByIdAndHouseholdId(accountId==null?loan.getPaymentAccount().getId():accountId,h).orElseThrow(()->new RequestValidationException(Map.of("paymentAccountId","付款账户必须属于当前家庭")));
  if(account.isArchived())throw new ResourceConflictException("ACCOUNT_ARCHIVED","付款账户已归档");
  if(!account.isOpeningConfirmed()||account.getOpeningOn()==null)throw new ResourceConflictException("ACCOUNTING_NOT_INITIALIZED","付款账户未开账");
  if(day.isBefore(account.getOpeningOn()))throw new ResourceConflictException("ACCOUNT_ACTIVITY_BEFORE_OPENING","日期不能早于账户开账");
  BigDecimal extra;try{extra=DecimalMoney.settled(new BigDecimal(raw));}catch(RuntimeException e){throw new RequestValidationException(Map.of("additionalPrincipal","额外本金必须精确到分"));}
  var projection=project(loan,plans.pending(h,id,false),day,extra,plans.roundingContext(h,id,false));
  var choices=projection.remainingPrincipal().signum()==0?List.<LoanTermOptions.Option>of():
        loan.getRepaymentMethod()==RepaymentMethod.CUSTOM?
        new LoanTermOptions().evaluateCustom(projection.future().stream().map(p->p.draft(0)).toList(),projection.remainingPrincipal(),projection.roundingContext(),loan.getMinimumInstallmentAmount()):
        new LoanTermOptions().evaluate(projection.remainingPrincipal(),loan.getAnnualRate(),projection.future().stream().map(LoanPlanToken.Period::dueOn).toList(),loan.getRepaymentMethod(),projection.roundingContext(),loan.getMinimumInstallmentAmount());
  return new TermOptionsResponse(DecimalMoney.format(projection.remainingPrincipal()),DecimalMoney.format(projection.duePrincipal()),DecimalMoney.format(projection.dueInterest()),LoanRepaymentPolicy.from(loan),choices);
 }
 private Loan find(long id,long h){return loans.findByIdAndHouseholdId(id,h).orElseThrow(()->new ResourceNotFoundException("贷款不存在"));}
 private static BigDecimal positive(String raw,String field){try{var value=DecimalMoney.settled(new BigDecimal(raw));if(value.signum()<=0)throw new IllegalArgumentException();return value;}catch(RuntimeException e){throw new RequestValidationException(Map.of(field,"金额必须为正且精确到分"));}}
}
