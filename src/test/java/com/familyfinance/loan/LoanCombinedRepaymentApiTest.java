package com.familyfinance.loan;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.familyfinance.accounting.LedgerReadService;
import com.familyfinance.shared.ResourceConflictException;
import com.familyfinance.transaction.FinancialTransaction;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.*;

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class LoanCombinedRepaymentApiTest {
 @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired LedgerReadService ledger;
 @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
 @MockitoSpyBean LoanAccountingService accounting;
 @MockitoSpyBean LoanPlanToken plans;
 @MockitoSpyBean LoanPlanningBudgetFactory planningBudgets;
 MockHttpSession session; long household,member,user,category,account;
 @BeforeEach void setup()throws Exception{
  String email=UUID.randomUUID()+"@combined.test";
  mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"displayName\":\"Combined\",\"password\":\"loan-test-password\",\"mode\":\"CREATE\",\"householdName\":\"Combined\"}")).andExpect(status().isCreated());
  session=(MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",email).param("password","loan-test-password")).andExpect(status().isOk()).andReturn().getRequest().getSession(false);
  user=jdbc.queryForObject("select id from app_users where email=?",Long.class,email);household=jdbc.queryForObject("select household_id from app_users where id=?",Long.class,user);member=jdbc.queryForObject("select id from family_members where household_id=?",Long.class,household);category=jdbc.queryForObject("select min(id) from categories where household_id=? and kind='EXPENSE'",Long.class,household);account=jdbc.queryForObject("select id from financial_accounts where household_id=?",Long.class,household);fund("5000.00");
 }
 @Test void quotedDuePlusExtraPostsExactCashAndGroupsChildrenWithoutDuplicatingExpense()throws Exception{
  long loan=sample(); var before=snapshot();
  var q=data(quote(loan,"3000.00").andExpect(status().isOk())
   .andExpect(jsonPath("$.data.duePrincipalAmount").value("1000.00")).andExpect(jsonPath("$.data.dueInterestAmount").value("100.00"))
   .andExpect(jsonPath("$.data.totalPrincipalAmount").value("4000.00")).andExpect(jsonPath("$.data.totalInterestAmount").value("100.00"))
   .andExpect(jsonPath("$.data.totalCashAmount").value("4100.00")).andExpect(jsonPath("$.data.balanceAfter").value("900.00"))
   .andExpect(jsonPath("$.data.before.principalAmount").value("9000.00")).andExpect(jsonPath("$.data.after.principalAmount").value("6000.00")).andReturn());
  assertSnapshot(before); String request=body(q,"x".repeat(100));
  var result=data(repay(loan,request).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCashAmount").value("4100.00")).andReturn());
  assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(90000);
  assertThat(ledger.balance(household,"LOAN:"+loan)).isEqualTo(600000);
  assertThat(ledger.balance(household,"EXPENSE:"+category)).isEqualTo(10000);
  assertThat(count("financial_transactions")).isEqualTo(2);
  assertThat(jdbc.queryForObject("select interest_amount from loan_prepayments where loan_id=?",BigDecimal.class,loan)).isEqualByComparingTo("0.00");
  assertThat(jdbc.queryForObject("select count(*) from loan_installments where loan_id=? and status='PAID'",Long.class,loan)).isEqualTo(1);
  assertThat(jdbc.queryForObject("select count(*) from loan_repayment_batch_children where household_id=?",Long.class,household)).isEqualTo(2);
  assertThat(data(repay(loan,request).andExpect(status().isOk()).andReturn())).isEqualTo(result);
  mvc.perform(get("/api/loans/"+loan+"/repayments").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].batchId").value(result.path("batchId").asLong()));
  mvc.perform(get("/api/loans/"+loan+"/prepayments").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].repaymentBatchId").value(result.path("batchId").asLong()));
  mvc.perform(get("/api/loans/"+loan).session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.paidRepaymentTotal").value("4100.00"));
  assertThat(ledger.balances(household)).isEqualTo(ledger.reconstructedBalances(household));
 }
 @Test void totalCashShortfallRejectsBeforeAnyDuePosting()throws Exception{
  long loan=sample();fund("4099.99");var q=data(quote(loan,"3000.00").andExpect(status().isOk()).andReturn());var before=snapshot();
  repay(loan,body(q,"shortfall")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INSUFFICIENT_FUNDS"));assertSnapshot(before);
 }
 @Test void failureAfterRealDueJournalAndPaidMarkRollsBackEveryChildAndNotification()throws Exception{
  long loan=sample();
  jdbc.update("insert into notifications(household_id,user_id,type,title,reference_type,reference_id) select household_id,?,'LOAN_DUE','Due fixture','LOAN_INSTALLMENT',id from loan_installments where loan_id=?",user,loan);
  var q=data(quote(loan,"3000.00").andExpect(status().isOk()).andReturn());var before=snapshot();
  var witnessed=new java.util.concurrent.atomic.AtomicBoolean();
  doAnswer(invocation->{
   FinancialTransaction tx=invocation.getArgument(1);
   if(tx.getSourceType()==com.familyfinance.transaction.TransactionSourceType.LOAN_PREPAYMENT){
    assertThat(jdbc.queryForObject("select count(*) from loan_installments where loan_id=? and status='PAID' and confirmed_transaction_id is not null",Long.class,loan)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from ledger_journals where household_id=? and source_type='LOAN_PAYMENT'",Long.class,household)).isEqualTo(1);
    assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(390000);
    assertThat(jdbc.queryForObject("select count(*) from notifications where household_id=? and resolved_at is not null",Long.class,household)).isEqualTo(1);
    witnessed.set(true);throw new ResourceConflictException("TEST_AFTER_DUE","Injected after real due settlement");
   }
   return invocation.callRealMethod();
  }).when(accounting).pay(any(Loan.class),any(FinancialTransaction.class),any(BigDecimal.class),any(BigDecimal.class),anyString());
  repay(loan,body(q,"rollback")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TEST_AFTER_DUE"));
  assertThat(witnessed).isTrue();assertSnapshot(before);
 }
 @Test void equalityClosesRemainingLoanAndReceiptStaysOriginalAfterClosure()throws Exception{
  long loan=sample();fund("12000.00");var q=data(quote(loan,"9000.00").andExpect(status().isOk()).andExpect(jsonPath("$.data.after.periodCount").value(0)).andExpect(jsonPath("$.data.termOptions.length()").value(0)).andReturn());
  String request=body(q,"close");var result=data(repay(loan,request).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CLOSED")).andExpect(jsonPath("$.data.totalCashAmount").value("10100.00")).andReturn());
  assertThat(jdbc.queryForObject("select current_principal_amount from loans where id=?",BigDecimal.class,loan)).isEqualByComparingTo("0.00");
  assertThat(jdbc.queryForObject("select count(*) from loan_installments where loan_id=? and status='PENDING'",Long.class,loan)).isZero();
  assertThat(data(repay(loan,request).andExpect(status().isOk()).andReturn())).isEqualTo(result);
  repay(loan,request.replace("9000.00","8999.00")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.CsvSource({"0.60,300,36,0.64,8.08,11.08","0.61,299,35,0.65,8.05,11.04"})
 void fractionalInterestOnlyDueKeepsPreciseContextAndNoZeroPrincipalLeg(String extra,long remainingCents,long cashCents,String totalCash,String futureInterest,String futureCash)throws Exception{
  long loan=create("3.60","0.12",360,null);fund("1.00");
  var q=data(quote(loan,extra).andExpect(status().isOk()).andExpect(jsonPath("$.data.duePrincipalAmount").value("0.00")).andExpect(jsonPath("$.data.dueInterestAmount").value("0.04")).andExpect(jsonPath("$.data.totalCashAmount").value(totalCash)).andExpect(jsonPath("$.data.after.periodCount").value(359)).andExpect(jsonPath("$.data.after.totalInterest").value(futureInterest)).andExpect(jsonPath("$.data.after.repaymentTotal").value(futureCash)).andExpect(jsonPath("$.data.after.schedule[358].paymentAmount").value("0.04")).andReturn());
  repay(loan,body(q,"fractional")).andExpect(status().isOk());
  assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(cashCents);assertThat(ledger.balance(household,"LOAN:"+loan)).isEqualTo(remainingCents);assertThat(ledger.balance(household,"EXPENSE:"+category)).isEqualTo(4);
  assertThat(jdbc.queryForObject("select count(*) from ledger_entries e join ledger_journals j on e.journal_id=j.id where j.household_id=? and j.source_type='LOAN_PAYMENT'",Long.class,household)).isEqualTo(2);
  assertThat(jdbc.queryForObject("select precise_interest_amount from loan_installments where loan_id=? and status='PAID'",BigDecimal.class,loan)).isEqualByComparingTo("0.036");
  var context=plans.roundingContext(household,loan,false);
  assertThat(context.preciseInterestPaid()).isEqualByComparingTo("0.036");assertThat(context.actualInterestPaid()).isEqualByComparingTo("0.04");
  assertThat(q.path("after").path("schedule").get(0).path("interest").asText()).isEqualTo("0.03");
  assertThat(jdbc.queryForObject("select sum(interest_amount) from loan_installments where loan_id=? and status='PENDING'",BigDecimal.class,loan)).isEqualByComparingTo(futureInterest);
  assertThat(jdbc.queryForList("select distinct rounding_policy from loan_installments where loan_id=? and status='PENDING'",String.class,loan)).containsExactly("CUMULATIVE_CENTS_V1");
  assertThat(jdbc.queryForObject("select principal_amount from loan_installments where loan_id=? and status='PENDING' order by installment_no desc limit 1",BigDecimal.class,loan)).isEqualByComparingTo("0.04");
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
 void customRoundedInterestLookaheadSurvivesCombinedConfirmationAndReload(boolean legacy)throws Exception{
  long loan=create("0.06","0",3,"[{\"dueOn\":\"2026-01-31\",\"principal\":\"0.04\",\"interest\":\"0.01\"},{\"dueOn\":\"2026-02-28\",\"principal\":\"0.01\",\"interest\":\"0.01\"},{\"dueOn\":\"2026-03-31\",\"principal\":\"0.01\",\"interest\":\"0.01\"}]");
  if(legacy)jdbc.update("update loan_installments set precise_principal_amount=null,precise_interest_amount=null,interest_carry_amount=null,rounding_policy=null,custom_rate_principal_amount=null,custom_rate_interest_amount=null where loan_id=?",loan);
  var original=snapshot();
  mvc.perform(get("/api/loans/"+loan+"/term-options").session(session).param("additionalPrincipal","0.03").param("paidOn","2026-01-01")).andExpect(status().isOk()).andExpect(jsonPath("$.data.options[2].allowed").value(true));
  var q=data(quote(loan,"0.03","2026-01-01",account,"REDUCE_PAYMENT",null).andExpect(status().isOk()).andExpect(jsonPath("$.data.after.periodCount").value(3)).andExpect(jsonPath("$.data.after.totalInterest").value("0.03")).andExpect(jsonPath("$.data.after.repaymentTotal").value("0.06")).andReturn());
  assertSnapshot(original);
  repay(loan,body(q,"custom-lookahead")).andExpect(status().isOk());
  assertThat(jdbc.queryForList("select principal_cents from loan_installments where loan_id=? and status='PENDING' order by installment_no",Long.class,loan)).containsExactly(1L,1L,1L);
  assertThat(jdbc.queryForList("select interest_cents from loan_installments where loan_id=? and status='PENDING' order by installment_no",Long.class,loan)).containsExactly(1L,1L,1L);
  assertThat(jdbc.queryForObject("select precise_principal_amount from loan_installments where loan_id=? and status='PENDING' order by installment_no limit 1",BigDecimal.class,loan)).isEqualByComparingTo("0.02");
  for(var row:jdbc.queryForList("select id from loan_installments where loan_id=? and status='PENDING' order by installment_no",Long.class,loan))
   mvc.perform(post("/api/loan-installments/"+row+"/confirm").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"paidOn\":\"2026-03-31\"}")).andExpect(status().isOk());
  assertThat(ledger.balance(household,"LOAN:"+loan)).isZero();
  assertThat(jdbc.queryForObject("select status from loans where id=?",String.class,loan)).isEqualTo("CLOSED");
  assertThat(plans.roundingContext(household,loan,false).preciseInterestPaid()).isEqualByComparingTo("0.025");
  assertThat(plans.roundingContext(household,loan,false).actualInterestPaid()).isEqualByComparingTo("0.03");
 }
 @Test void tokenBindsAmountsDateAccountTermPolicyAndPrecision()throws Exception{
  long loan=sample();var q=data(quote(loan,"3000.00").andExpect(status().isOk()).andReturn());String request=body(q,"bound");
  long other=account("Other","5000.00","2026-01-01");
  for(String changed:List.of(request.replace("3000.00","3001.00"),request.replace("2026-01-31","2026-02-01"),request.replace("REDUCE_PAYMENT","REDUCE_TERM"),request.replace("\"paymentAccountId\":"+account,"\"paymentAccountId\":"+other)))
   repay(loan,changed).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_PLAN_CHANGED"));
  jdbc.update("update loan_installments set precise_interest_amount=0.000000000001 where loan_id=? and installment_no=2",loan);
  repay(loan,request).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_PLAN_CHANGED"));
  q=data(quote(loan,"3000.00").andExpect(status().isOk()).andReturn());request=body(q,"policy");
  mvc.perform(patch("/api/loans/"+loan+"/repayment-policy").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"revision\":0,\"sourceNote\":\"Revised contract\"}")).andExpect(status().isOk());
  repay(loan,request).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_PLAN_CHANGED"));
  assertThat(count("financial_transactions")).isZero();
 }
 @Test void exhaustedSearchIsUndeterminedAndPostsNothing()throws Exception{
  long loan=sample();var q=data(quote(loan,"3000.00").andExpect(status().isOk()).andReturn());var before=snapshot();
  doReturn(new LoanPlanningBudget(0)).when(planningBudgets).create();
  repay(loan,body(q,"bounded-search")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_PLAN_SEARCH_LIMIT"));
  assertSnapshot(before);
  mvc.perform(get("/api/loans/"+loan+"/term-options").session(session).param("additionalPrincipal","3000.00").param("paidOn","2026-01-31")).andExpect(status().isOk()).andExpect(jsonPath("$.data.options[0].evaluationStatus").value("UNDETERMINED")).andExpect(jsonPath("$.data.options[0].reason").value("LOAN_PLAN_SEARCH_LIMIT"));
  assertSnapshot(before);
 }
 @Test void previewSharesSelectedPlanBudgetWithAllAlternateTerms()throws Exception{
  long loan=sample();var budget=new LoanPlanningBudget(5);doReturn(budget).when(planningBudgets).create();
  quote(loan,"3000.00").andExpect(status().isOk()).andExpect(jsonPath("$.data.after.periodCount").value(2))
   .andExpect(jsonPath("$.data.termOptions[0].evaluationStatus").value("FEASIBLE"))
   .andExpect(jsonPath("$.data.termOptions[1].evaluationStatus").value("UNDETERMINED"));
  assertThat(budget.used()).isEqualTo(5);assertThat(count("financial_transactions")).isZero();
 }
 @Test void calculationRevisionRejectsPreviousQuoteWithoutChangingItsInputs()throws Exception{
  long loan=sample();var previousToken=new java.util.concurrent.atomic.AtomicReference<String>();
  doAnswer(invocation->{previousToken.set(LegacyLoanPlanTokenV1.token(invocation.getArgument(0),invocation.getArgument(1),invocation.getArgument(2),invocation.getArgument(3),invocation.getArgument(4),invocation.getArgument(5),invocation.getArgument(6)));return invocation.callRealMethod();})
   .when(plans).token(any(Loan.class),anyList(),anyString(),any(java.time.LocalDate.class),anyLong(),anyLong(),anyString());
  var q=data(quote(loan,"3000.00").andExpect(status().isOk()).andReturn());var before=snapshot();
  String request=body(q,"old-calculation").replace(q.path("planToken").asText(),previousToken.get());
  repay(loan,request).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_PLAN_CHANGED"));assertSnapshot(before);
 }
 @Test void successfulPreviousCalculationReceiptReplaysBeforeNewCalculationChecks()throws Exception{
  long loan=sample();
  doAnswer(invocation->LegacyLoanPlanTokenV1.token(invocation.getArgument(0),invocation.getArgument(1),invocation.getArgument(2),invocation.getArgument(3),invocation.getArgument(4),invocation.getArgument(5),invocation.getArgument(6)))
   .when(plans).token(any(Loan.class),anyList(),anyString(),any(java.time.LocalDate.class),anyLong(),anyLong(),anyString());
  String request=body(data(quote(loan,"3000.00").andExpect(status().isOk()).andReturn()),"old-calculation-receipt");
  var original=data(repay(loan,request).andExpect(status().isOk()).andReturn());var before=snapshot();
  doCallRealMethod().when(plans).token(any(Loan.class),anyList(),anyString(),any(java.time.LocalDate.class),anyLong(),anyLong(),anyString());
  doReturn(new LoanPlanningBudget(0)).when(planningBudgets).create();
  assertThat(data(repay(loan,request).andExpect(status().isOk()).andReturn())).isEqualTo(original);assertSnapshot(before);
 }
 @Test void changedBalanceRequiresNewQuoteForTheDisplayedResult()throws Exception{
  long loan=sample();String request=body(data(quote(loan,"3000.00").andExpect(status().isOk()).andReturn()),"balance");fund("5001.00");var before=snapshot();
  repay(loan,request).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_PLAN_CHANGED"));assertSnapshot(before);
 }
 @Test void explicitShortenedTermUsesItsQuotedPrefixAndFullReceiptRejectsChangedRawBody()throws Exception{
  long loan=sample();var q=data(quote(loan,"3000.00","2026-01-31",account,"ADJUST_TERM",1).andExpect(status().isOk()).andExpect(jsonPath("$.data.after.periodCount").value(1)).andExpect(jsonPath("$.data.after.schedule[0].principal").value("6000.00")).andExpect(jsonPath("$.data.after.maturityOn").value("2026-02-28")).andReturn());
  String request=body(q,"term");repay(loan,request.replace("\"targetPeriods\":1","\"targetPeriods\":2")).andExpect(status().isBadRequest());
  repay(loan,request).andExpect(status().isOk());repay(loan,request.replace("3000.00","3000.0")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
  assertThat(jdbc.queryForObject("select principal_amount from loan_installments where loan_id=? and status='PENDING'",BigDecimal.class,loan)).isEqualByComparingTo("6000.00");
  assertThat(jdbc.queryForObject("select custom_rate_principal_amount from loan_installments where loan_id=? and status='PENDING'",BigDecimal.class,loan)).isEqualByComparingTo("9000.00");
 }
 @Test void adminAndOriginalAssigneeAreBothRequiredButNoDuesDoNotNeedAssignment()throws Exception{
  long loan=sample();String request=body(data(quote(loan,"3000.00").andExpect(status().isOk()).andReturn()),"roles");
  jdbc.update("update household_memberships set role='MEMBER' where household_id=? and user_id=?",household,user);
  quote(loan,"3000.00").andExpect(status().isForbidden());repay(loan,request).andExpect(status().isForbidden());
  jdbc.update("update household_memberships set role='ADMIN' where household_id=? and user_id=?",household,user);
  jdbc.update("update loans set assigned_user_id=null where id=?",loan);
  quote(loan,"3000.00").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INSTALLMENT_UNASSIGNED"));repay(loan,request).andExpect(status().isConflict());
  var q=data(quote(loan,"3000.00","2026-01-01",account,"REDUCE_PAYMENT",null).andExpect(status().isOk()).andReturn());repay(loan,body(q,"no-dues")).andExpect(status().isOk());
 }
 @Test void invalidMoneyDatesAndForeignOrArchivedAccountsLeaveNoPayments()throws Exception{
  long loan=sample();String request=body(data(quote(loan,"3000.00").andExpect(status().isOk()).andReturn()),"validation");
  for(String extra:List.of("0","-1","0.001","9000.01"))quote(loan,extra).andExpect(status().isBadRequest());
  quote(loan,"9000.01").andExpect(jsonPath("$.error.fields.additionalPrincipal").value(org.hamcrest.Matchers.containsString("9000.00")));
  quote(loan,"3000.00","2099-01-01",account,"REDUCE_PAYMENT",null).andExpect(status().isBadRequest());
  quote(loan,"3000.00","2025-12-31",account,"REDUCE_PAYMENT",null).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_PAYMENT_BEFORE_OPENING"));
  long late=account("Late","5000.00","2026-02-01");quote(loan,"3000.00","2026-01-31",late,"REDUCE_PAYMENT",null).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNT_ACTIVITY_BEFORE_OPENING"));
  jdbc.update("update financial_accounts set archived_at=CURRENT_TIMESTAMP where id=?",account);var before=snapshot();
  quote(loan,"3000.00").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNT_ARCHIVED"));repay(loan,request).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNT_ARCHIVED"));assertSnapshot(before);
  long oldAccount=account;long oldHousehold=household;MockHttpSession original=session;setup();long foreign=account;session=original;household=oldHousehold;account=oldAccount;
  quote(loan,"3000.00","2026-01-31",foreign,"REDUCE_PAYMENT",null).andExpect(status().isBadRequest());
  repay(loan,request.replace("\"paymentAccountId\":"+oldAccount,"\"paymentAccountId\":"+foreign)).andExpect(status().isBadRequest());
 }
 @Test void historyAndReplayRemainOriginalAfterLaterPaymentAndChangedAssignment()throws Exception{
  long loan=sample();String first=body(data(quote(loan,"3000.00").andExpect(status().isOk()).andReturn()),"original");var result=data(repay(loan,first).andExpect(status().isOk()).andReturn());
  var q=data(quote(loan,"100.00").andExpect(status().isOk()).andReturn());repay(loan,body(q,"later")).andExpect(status().isOk());
  jdbc.update("update loans set assigned_user_id=null where id=?",loan);
  assertThat(data(repay(loan,first).andExpect(status().isOk()).andReturn())).isEqualTo(result);
  var history=data(mvc.perform(get("/api/loans/"+loan+"/repayments").session(session)).andExpect(status().isOk()).andReturn());assertThat(history.get(0)).isEqualTo(result);
  assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(80000);assertThat(ledger.balance(household,"LOAN:"+loan)).isEqualTo(590000);
  assertThat(plans.roundingContext(household,loan,false).actualInterestPaid()).isEqualByComparingTo("100.00");
 }
 @Test void previewKeepsOneRepeatableReadSnapshotAcrossConcurrentMetadataEdit()throws Exception{
  long loan=sample();var original=data(quote(loan,"3000.00").andExpect(status().isOk()).andReturn());var once=new java.util.concurrent.atomic.AtomicBoolean();
  doAnswer(invocation->{
   if(!invocation.<Boolean>getArgument(2)&&once.compareAndSet(false,true)){
    var pool=java.util.concurrent.Executors.newSingleThreadExecutor();try{pool.submit(()->jdbc.update("update loan_installments set precise_interest_amount=0.000000000001 where loan_id=? and installment_no=2",loan)).get(10,java.util.concurrent.TimeUnit.SECONDS);}finally{pool.shutdownNow();}
   }
   return invocation.callRealMethod();
  }).when(plans).pending(eq(household),eq(loan),anyBoolean());
  var concurrent=data(quote(loan,"3000.00").andExpect(status().isOk()).andReturn());assertThat(concurrent).isEqualTo(original);
  repay(loan,body(original,"stale-read")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_PLAN_CHANGED"));
 }
 @Test void earlierSnapshotCannotReplayAnOldPlanButCanReadCommittedReceipt()throws Exception{
  long loan=sample();String original=body(data(quote(loan,"3000.00").andExpect(status().isOk()).andReturn()),"current");
  withEarlierSnapshot(()->repay(loan,original).andExpect(status().isOk()),()->repay(loan,original.replace("current","stale")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_PLAN_CHANGED")));
  withEarlierSnapshot(()->{},()->repay(loan,original).andExpect(status().isOk()).andExpect(jsonPath("$.data.balanceAfter").value("900.00")));
  assertThat(count("financial_transactions")).isEqualTo(2);
 }
 @Test void concurrentRepaymentsSerializeWholeDebitsWithoutOverspending()throws Exception{
  long first=sample(),second=sample();String a=body(data(quote(first,"3000.00").andExpect(status().isOk()).andReturn()),"race-a"),b=body(data(quote(second,"3000.00").andExpect(status().isOk()).andReturn()),"race-b");
  var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var ready=new java.util.concurrent.CountDownLatch(2);var go=new java.util.concurrent.CountDownLatch(1);
  try{
   var left=pool.submit(()->{ready.countDown();go.await();return repay(first,a).andReturn().getResponse().getStatus();});
   var right=pool.submit(()->{ready.countDown();go.await();return repay(second,b).andReturn().getResponse().getStatus();});
   assertThat(ready.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();go.countDown();
   assertThat(List.of(left.get(20,java.util.concurrent.TimeUnit.SECONDS),right.get(20,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
  }finally{pool.shutdownNow();assertThat(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
  assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(90000);assertThat(count("financial_transactions")).isEqualTo(2);assertThat(count("loan_repayment_batches")).isEqualTo(1);
 }
 @Test void allDueRowsSettleInOrderBeforeExtraAndFutureOnlyIsReplaced()throws Exception{
  long loan=sample();fund("10000.00");var q=data(quote(loan,"3000.00","2026-02-28",account,"REDUCE_PAYMENT",null).andExpect(status().isOk()).andExpect(jsonPath("$.data.dueInstallments.length()").value(2)).andExpect(jsonPath("$.data.totalCashAmount").value("8600.00")).andReturn());
  repay(loan,body(q,"two-due")).andExpect(status().isOk()).andExpect(jsonPath("$.data.children.length()").value(3)).andExpect(jsonPath("$.data.remainingPrincipal").value("1500.00"));
  assertThat(jdbc.queryForList("select status from loan_installments where loan_id=? order by installment_no",String.class,loan)).containsExactly("PAID","PAID","CANCELLED","PENDING");
  assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(140000);assertThat(ledger.balance(household,"EXPENSE:"+category)).isEqualTo(10000);
 }
 @Test void laterIncomeCannotFundEarlierCombinedCashEvenWhenCurrentBalanceIsEnough()throws Exception{
  long loan=sample();fund("0.00");long income=jdbc.queryForObject("select min(id) from categories where household_id=? and kind='INCOME'",Long.class,household);
  mvc.perform(post("/api/transactions").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"INCOME\",\"amount\":\"5000.00\",\"occurredOn\":\"2026-02-01\",\"accountId\":"+account+",\"memberId\":"+member+",\"categoryId\":"+income+"}")).andExpect(status().isCreated());
  var q=data(quote(loan,"3000.00").andExpect(status().isOk()).andExpect(jsonPath("$.data.availableBalance").value("5000.00")).andReturn());var before=snapshot();
  repay(loan,body(q,"historical-cash")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INSUFFICIENT_FUNDS"));assertSnapshot(before);
 }
 @Test void fallbackAccountingMemberChangeInvalidatesQuoteEvenWithSameAssignee()throws Exception{
  long loan=sample();jdbc.update("update loans set member_id=null where id=?",loan);String request=body(data(quote(loan,"3000.00").andExpect(status().isOk()).andReturn()),"member-dimension");
  withEarlierSnapshot(()->{
   jdbc.update("update family_members set linked_user_id=null where id=?",member);
   jdbc.update("insert into family_members(household_id,linked_user_id,name,role_label,created_at) values(?,?,'Replacement','Member',CURRENT_TIMESTAMP)",household,user);
  },()->repay(loan,request).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_PLAN_CHANGED")));
  assertThat(count("financial_transactions")).isZero();
 }
 @Test void ownerCannotConfirmAnotherAssigneeButAssignedAdminCan()throws Exception{
  long loan=sample();String request=body(data(quote(loan,"3000.00").andExpect(status().isOk()).andReturn()),"owner-assignee");
  String invite=data(mvc.perform(post("/api/family/invites").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}")).andExpect(status().isCreated()).andReturn()).path("token").asText();String email=UUID.randomUUID()+"@assigned.test";
  mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"displayName\":\"Assignee\",\"password\":\"loan-test-password\",\"mode\":\"JOIN\",\"inviteToken\":\""+invite+"\"}")).andExpect(status().isCreated());
  long assignee=jdbc.queryForObject("select id from app_users where email=?",Long.class,email);jdbc.update("update loans set assigned_user_id=? where id=?",assignee,loan);
  quote(loan,"3000.00").andExpect(status().isForbidden());repay(loan,request).andExpect(status().isForbidden());assertThat(count("financial_transactions")).isZero();
  session=(MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",email).param("password","loan-test-password")).andExpect(status().isOk()).andReturn().getRequest().getSession(false);
  var q=data(quote(loan,"3000.00").andExpect(status().isOk()).andReturn());repay(loan,body(q,"assigned-admin")).andExpect(status().isOk());
 }
 @Test void differentHouseholdCannotSeeBatchHistoryOrReplayRequest()throws Exception{
  long loan=sample();String request=body(data(quote(loan,"3000.00").andExpect(status().isOk()).andReturn()),"private");repay(loan,request).andExpect(status().isOk());setup();
  quote(loan,"3000.00").andExpect(status().isNotFound());repay(loan,request).andExpect(status().isNotFound());mvc.perform(get("/api/loans/"+loan+"/repayments").session(session)).andExpect(status().isNotFound());
 }
 private void withEarlierSnapshot(Checked outside,Checked inside)throws Exception{
  var pool=java.util.concurrent.Executors.newSingleThreadExecutor();var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
  if(Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->"MySQL".equals(c.getMetaData().getDatabaseProductName()))))tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
  try{tx.executeWithoutResult(s->{jdbc.queryForObject("select count(*) from loan_installments where household_id=?",Long.class,household);try{pool.submit(()->{outside.run();return null;}).get(15,java.util.concurrent.TimeUnit.SECONDS);inside.run();if(s.isRollbackOnly())s.setRollbackOnly();}catch(Exception e){throw new RuntimeException(e);}});}finally{pool.shutdownNow();assertThat(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
 }
 @FunctionalInterface private interface Checked{void run()throws Exception;}
 private long sample()throws Exception{return create("10000.00","0",3,"[{\"dueOn\":\"2026-01-31\",\"principal\":\"1000.00\",\"interest\":\"100.00\"},{\"dueOn\":\"2026-02-28\",\"principal\":\"4500.00\",\"interest\":\"0.00\"},{\"dueOn\":\"2026-03-31\",\"principal\":\"4500.00\",\"interest\":\"0.00\"}]");}
 private long create(String principal,String rate,int term,String custom)throws Exception{
  String body="{\"name\":\"Combined\",\"type\":\"OTHER\",\"memberId\":"+member+",\"assignedUserId\":"+user+",\"paymentAccountId\":"+account+",\"paymentCategoryId\":"+category+",\"principal\":\""+principal+"\",\"annualRate\":"+rate+",\"termMonths\":"+term+",\"repaymentMethod\":\""+(custom==null?"EQUAL_PAYMENT":"CUSTOM")+"\",\"startOn\":\"2025-12-31\",\"fundingMode\":\"OPENING\",\"accountingOn\":\"2026-01-01\""+(custom==null?"":",\"customSchedule\":"+custom)+"}";
  return data(mvc.perform(post("/api/loans").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn()).path("id").asLong();
 }
 private ResultActions quote(long loan,String extra)throws Exception{return quote(loan,extra,"2026-01-31",account,"REDUCE_PAYMENT",null);}
 private ResultActions quote(long loan,String extra,String day,long selected,String strategy,Integer target)throws Exception{var builder=get("/api/loans/"+loan+"/repayment-preview").session(session).param("additionalPrincipal",extra).param("paidOn",day).param("paymentAccountId",String.valueOf(selected)).param("strategy",strategy);if(target!=null)builder.param("targetPeriods",target.toString());return mvc.perform(builder);}
 private long account(String name,String opening,String day)throws Exception{return data(mvc.perform(post("/api/accounts").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\""+name+"\",\"type\":\"BANK\",\"currency\":\"CNY\",\"openingBalance\":\""+opening+"\",\"openingOn\":\""+day+"\"}")).andExpect(status().isCreated()).andReturn()).path("id").asLong();}
 private String body(JsonNode q,String key){var b=json.createObjectNode();for(String field:List.of("additionalPrincipal","paidOn","paymentAccountId","strategy","targetPeriods","planToken"))if(q.has(field))b.set(field,q.path(field));b.put("idempotencyKey",key);return b.toString();}
 private ResultActions repay(long loan,String body)throws Exception{return mvc.perform(post("/api/loans/"+loan+"/repayment").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));}
 private long count(String table){return jdbc.queryForObject("select count(*) from "+table+" where household_id=?",Long.class,household);}
 private Map<String,List<Map<String,Object>>> snapshot(){var result=new LinkedHashMap<String,List<Map<String,Object>>>();for(String table:List.of("loans","loan_installments","loan_prepayments","loan_repayment_batches","loan_repayment_batch_children","financial_transactions","ledger_journals","ledger_entries","ledger_sources","ledger_accounts","accounting_commands","notifications"))result.put(table,jdbc.queryForList("select * from "+table+" where household_id=? order by 1,2,3",household));return result;}
 private void assertSnapshot(Map<String,List<Map<String,Object>>> expected){assertThat(snapshot()).isEqualTo(expected);}
 private void fund(String amount)throws Exception{mvc.perform(patch("/api/accounts/"+account).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"openingBalance\":\""+amount+"\",\"openingOn\":\"2026-01-01\"}")).andExpect(status().isOk());}
 private JsonNode data(MvcResult result)throws Exception{return json.readTree(result.getResponse().getContentAsString()).path("data");}
}
