package com.familyfinance.loan;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.familyfinance.accounting.LedgerReadService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.*;

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class LoanPrepaymentStrategyApiTest {
 @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired LedgerReadService ledger; @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
 MockHttpSession session;long household,member,user,category,account;
 @BeforeEach void setup()throws Exception{
  String email=UUID.randomUUID()+"@strategy.test";
  mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"displayName\":\"Strategy\",\"password\":\"loan-test-password\",\"mode\":\"CREATE\",\"householdName\":\"Strategy test\"}")).andExpect(status().isCreated());
  session=(MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",email).param("password","loan-test-password")).andExpect(status().isOk()).andReturn().getRequest().getSession(false);
  user=jdbc.queryForObject("select id from app_users where email=?",Long.class,email);household=jdbc.queryForObject("select household_id from app_users where id=?",Long.class,user);member=jdbc.queryForObject("select id from family_members where household_id=?",Long.class,household);category=jdbc.queryForObject("select min(id) from categories where household_id=? and kind='EXPENSE'",Long.class,household);account=jdbc.queryForObject("select id from financial_accounts where household_id=?",Long.class,household);fund("5000.00");
 }
 @Test void previewAndSuccessiveStrategiesKeepDatesHistoryAndWholeLoanTotals()throws Exception{
  long loan=create("0");var q=data(quote(loan,"300.00","REDUCE_TERM",account).andExpect(status().isOk()).andExpect(jsonPath("$.data.before.periodCount").value(12)).andExpect(jsonPath("$.data.after.periodCount").value(9)).andExpect(jsonPath("$.data.after.maturityOn").value("2026-09-30")).andExpect(jsonPath("$.data.cashAmount").value("300.00")).andReturn());
  assertThat(count("financial_transactions")).isZero();String body=body(q,"first");var result=data(prepay(loan,body).andExpect(status().isOk()).andExpect(jsonPath("$.data.strategy").value("REDUCE_TERM")).andReturn());
  long event=result.path("id").asLong();prepay(loan,body).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(event));
  assertThat(jdbc.queryForObject("select count(*) from loan_installments where loan_id=? and cancelled_by_prepayment_id=?",Long.class,loan,event)).isEqualTo(12);
  mvc.perform(get("/api/loans/"+loan).session(session)).andExpect(jsonPath("$.data.remainingTerm").value(9)).andExpect(jsonPath("$.data.maturityOn").value("2026-09-30")).andExpect(jsonPath("$.data.nextPaymentAmount").value("100.00")).andExpect(jsonPath("$.data.termMonths").value(12)).andExpect(jsonPath("$.data.startOn").value("2025-12-31")).andExpect(jsonPath("$.data.repaymentMethod").value("EQUAL_PAYMENT"));
  var second=data(quote(loan,"90.00","REDUCE_PAYMENT",account).andExpect(status().isOk()).andReturn());prepay(loan,body(second,"second")).andExpect(status().isOk()).andExpect(jsonPath("$.data.remainingRepaymentTotal").value("810.00")).andExpect(jsonPath("$.data.paidRepaymentTotal").value("390.00")).andExpect(jsonPath("$.data.scheduledRepaymentTotal").value("1200.00"));
  mvc.perform(get("/api/loans/"+loan).session(session)).andExpect(jsonPath("$.data.latestStrategy").value("REDUCE_PAYMENT")).andExpect(jsonPath("$.data.remainingTerm").value(9)).andExpect(jsonPath("$.data.maturityOn").value("2026-09-30")).andExpect(jsonPath("$.data.nextPaymentAmount").value("90.00"));
  assertThat(jdbc.queryForObject("select min(installment_no) from loan_installments where loan_id=? and status='PENDING'",Long.class,loan)).isEqualTo(22);assertThat(count("financial_transactions")).isEqualTo(2);assertThat(ledger.balances(household)).isEqualTo(ledger.reconstructedBalances(household));
 }
 @Test void quoteLiteralInterestMatchesPostedScheduleAndNoUnquotedCash()throws Exception{
  long loan=create("0.12");var q=data(quote(loan,"300.00","REDUCE_TERM",account).andExpect(status().isOk()).andExpect(jsonPath("$.data.after.totalInterest").value("45.02")).andExpect(jsonPath("$.data.after.nextPaymentAmount").value("106.62")).andReturn());
  prepay(loan,body(q,"literal")).andExpect(status().isOk()).andExpect(jsonPath("$.data.cashAmount").value("300.00")).andExpect(jsonPath("$.data.interestAmount").value("0.00")).andExpect(jsonPath("$.data.scheduledRepaymentTotal").value("1245.02"));
  var rows=data(mvc.perform(get("/api/loans/"+loan+"/schedule").session(session)).andReturn());int i=0;for(var row:rows)if(row.path("status").asText().equals("PENDING")){assertThat(row.path("dueOn")).isEqualTo(q.path("after").path("schedule").get(i).path("dueOn"));assertThat(row.path("principal")).isEqualTo(q.path("after").path("schedule").get(i).path("principal"));assertThat(row.path("interest")).isEqualTo(q.path("after").path("schedule").get(i++).path("interest"));}assertThat(i).isEqualTo(9);
 }
 @Test void selectedAccountShortfallRollsBackEntireScheduleAndSameTokenRetriesAfterFunding()throws Exception{
  long loan=create("0");long selected=data(mvc.perform(post("/api/accounts").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Selected\",\"type\":\"BANK\",\"currency\":\"CNY\",\"openingBalance\":\"299.99\",\"openingOn\":\"2026-01-01\"}")).andExpect(status().isCreated()).andReturn()).path("id").asLong();
  var q=data(quote(loan,"300.00","REDUCE_TERM",selected).andExpect(status().isOk()).andExpect(jsonPath("$.data.availableBalance").value("299.99")).andReturn());String body=body(q,"funds");long journals=count("ledger_journals");
  prepay(loan,body.replace("\"paymentAccountId\":"+selected,"\"paymentAccountId\":"+account)).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_PLAN_CHANGED"));
  prepay(loan,body).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INSUFFICIENT_FUNDS"));assertThat(count("loan_prepayments")).isZero();assertThat(count("financial_transactions")).isZero();assertThat(count("ledger_journals")).isEqualTo(journals);assertThat(pending(loan)).isEqualTo(12);assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(500000);
  mvc.perform(patch("/api/accounts/"+selected).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"openingBalance\":\"300.00\",\"openingOn\":\"2026-01-01\"}")).andExpect(status().isOk());prepay(loan,body).andExpect(status().isOk());assertThat(ledger.balance(household,"CASH:"+selected)).isZero();assertThat(jdbc.queryForObject("select payment_account_id from loans where id=?",Long.class,loan)).isEqualTo(account);
 }
 @Test void tokenBindsEveryInputAndRejectsOldPlanWhileExactReceiptReplays()throws Exception{
  long loan=create("0");var q=data(quote(loan,"300.00","REDUCE_TERM",account).andReturn());String body=body(q,"bound");
  for(String changed:List.of(body.replace("300.00","301.00"),body.replace("REDUCE_TERM","REDUCE_PAYMENT"),body.replace("2026-01-01","2026-01-02")))prepay(loan,changed).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_PLAN_CHANGED"));
  prepay(loan,body).andExpect(status().isOk());prepay(loan,body.replace("bound","new-key")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_PLAN_CHANGED"));prepay(loan,body).andExpect(status().isOk());assertThat(count("financial_transactions")).isEqualTo(1);
 }
 @Test void overdueGuardPreservesDueRowsAndLegacyFullRoutesSafely()throws Exception{
  long loan=create("0.12");String overdue="{\"amount\":\"100.00\",\"paidOn\":\"2026-01-31\",\"idempotencyKey\":\"overdue\"}";
  prepay(loan,overdue).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_OVERDUE_INSTALLMENTS"));prepay(loan,overdue.replace("100.00","1200.00")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_PAYOFF_REQUIRED"));assertThat(pending(loan)).isEqualTo(12);assertThat(count("financial_transactions")).isZero();
  quote(loan,"1200.00","REDUCE_TERM",account).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_PAYOFF_REQUIRED"));
  mvc.perform(get("/api/loans/"+loan+"/prepayment-preview").session(session).param("amount","1200.00").param("paidOn","2026-01-31").param("strategy","REDUCE_TERM")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_PAYOFF_REQUIRED"));
  prepay(loan,overdue.replace("100.00","1200.00").replace("2026-01-31","2026-01-01")).andExpect(status().isOk()).andExpect(jsonPath("$.data.cashAmount").value("1200.00"));
 }
 @Test void paidHistorySurvivesAndEarlierSnapshotUsesCurrentPlanAndReceipt()throws Exception{
  long loan=create("0");String original=body(data(quote(loan,"300.00","REDUCE_TERM",account).andReturn()),"snapshot");
  withEarlierSnapshot(()->prepay(loan,original).andExpect(status().isOk()),()->prepay(loan,original.replace("snapshot","stale")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_PLAN_CHANGED")));
  withEarlierSnapshot(()->{},()->prepay(loan,original).andExpect(status().isOk()).andExpect(jsonPath("$.data.remainingPrincipal").value("900.00")));
  long first=jdbc.queryForObject("select min(id) from loan_installments where loan_id=? and status='PENDING'",Long.class,loan);
  mvc.perform(post("/api/loan-installments/"+first+"/confirm").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"paidOn\":\"2026-01-31\"}")).andExpect(status().isOk());
  var q=data(mvc.perform(get("/api/loans/"+loan+"/prepayment-preview").session(session).param("amount","100.00").param("paidOn","2026-01-31").param("strategy","REDUCE_PAYMENT")).andExpect(status().isOk()).andReturn());prepay(loan,body(q,"after-paid")).andExpect(status().isOk()).andExpect(jsonPath("$.data.paidRepaymentTotal").value("500.00"));
  assertThat(jdbc.queryForObject("select status from loan_installments where id=?",String.class,first)).isEqualTo("PAID");assertThat(jdbc.queryForObject("select principal_cents from loan_installments where id=?",Long.class,first)).isEqualTo(10000);
 }
 @Test void reducePaymentUsesLiteralInterestAndLegacyDefaultWithoutAdditionalDebit()throws Exception{
  long loan=create("0.12");var q=data(quote(loan,"300.00","REDUCE_PAYMENT",account).andExpect(status().isOk()).andExpect(jsonPath("$.data.after.totalInterest").value("59.57")).andExpect(jsonPath("$.data.after.periodCount").value(12)).andExpect(jsonPath("$.data.after.schedule[11].paymentAmount").value("80.01")).andExpect(jsonPath("$.data.after.maturityOn").value("2026-12-31")).andReturn());
  prepay(loan,body(q,"payment")).andExpect(status().isOk()).andExpect(jsonPath("$.data.remainingRepaymentTotal").value("959.57")).andExpect(jsonPath("$.data.cashAmount").value("300.00"));
  long legacy=create("0");prepay(legacy,"{\"amount\":\"300.00\",\"paidOn\":\"2026-01-01\",\"idempotencyKey\":\"legacy-default\"}").andExpect(status().isOk()).andExpect(jsonPath("$.data.strategy").value("REDUCE_PAYMENT"));assertThat(pending(legacy)).isEqualTo(12);
 }
 @Test void currentPlanFiltersBeforePaginationWhileCompleteHistoryRemainsAccessible()throws Exception{
  long loan=create("0");mvc.perform(patch("/api/loans/"+loan).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"termMonths\":120}")).andExpect(status().isOk());
  var q=data(quote(loan,"300.00","REDUCE_TERM",account).andExpect(status().isOk()).andReturn());prepay(loan,body(q,"long-plan")).andExpect(status().isOk());
  mvc.perform(get("/api/loans/"+loan+"/schedule").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].status").value("CANCELLED")).andExpect(header().string("X-Total-Elements","210"));
  mvc.perform(get("/api/loans/"+loan+"/schedule").session(session).param("view","CURRENT")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(50)).andExpect(jsonPath("$.data[0].installmentNo").value(121)).andExpect(jsonPath("$.data[0].status").value("PENDING")).andExpect(header().string("X-Total-Elements","90")).andExpect(header().string("X-Total-Pages","2")).andExpect(header().string("X-Has-Next","true"));
  mvc.perform(get("/api/loans/"+loan+"/schedule").session(session).param("view","HISTORY").param("page","2")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(20)).andExpect(jsonPath("$.data[0].installmentNo").value(101)).andExpect(jsonPath("$.data[0].status").value("CANCELLED")).andExpect(header().string("X-Total-Elements","120")).andExpect(header().string("X-Has-Next","false"));
 }
 @Test void actualAccountAndBalanceAreRecheckedAfterEarlierSnapshot()throws Exception{
  long loan=create("0");String body=body(data(quote(loan,"300.00","REDUCE_TERM",account).andReturn()),"archived");long journals=count("ledger_journals");
  withEarlierSnapshot(()->jdbc.update("update financial_accounts set archived_at=CURRENT_TIMESTAMP where id=?",account),()->prepay(loan,body).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNT_ARCHIVED")));
  assertThat(count("loan_prepayments")).isZero();assertThat(count("financial_transactions")).isZero();assertThat(count("ledger_journals")).isEqualTo(journals);assertThat(pending(loan)).isEqualTo(12);assertThat(ledger.balance(household,"LOAN:"+loan)).isEqualTo(120000);
 }
 @Test void fullAndTinyResidualPreviewAreExplicitAndPermissionsCoverBothRoutes()throws Exception{
  long loan=create("0");quote(loan,"1199.99","REDUCE_PAYMENT",account).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_FIXED_TERM_INFEASIBLE"));
  var q=data(quote(loan,"1199.99","REDUCE_TERM",account).andExpect(status().isOk()).andExpect(jsonPath("$.data.after.periodCount").value(1)).andExpect(jsonPath("$.data.after.principalAmount").value("0.01")).andReturn());
  String body=body(q,"permission");jdbc.update("update household_memberships set role='MEMBER' where household_id=? and user_id=?",household,user);
  quote(loan,"300.00","REDUCE_TERM",account).andExpect(status().isForbidden());prepay(loan,body).andExpect(status().isForbidden());assertThat(count("financial_transactions")).isZero();
 }
 @Test void fractionalFixedTermPreservesPositiveCashAndPostsTheQuoted360PeriodPlan()throws Exception{
  long loan=create("0.12");
  mvc.perform(patch("/api/loans/"+loan).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"termMonths\":360}")).andExpect(status().isOk());
  var before=new LinkedHashMap<String,List<Map<String,Object>>>();
  for(String table:List.of("loans","loan_installments","loan_prepayments","financial_transactions","ledger_journals","ledger_entries","ledger_sources","ledger_accounts","accounting_commands"))
   before.put(table,jdbc.queryForList("select * from "+table+" where household_id=? order by 1,2,3",household));
  var fixed=data(quote(loan,"1196.40","REDUCE_PAYMENT",account).andExpect(status().isOk()).andExpect(jsonPath("$.data.after.periodCount").value(360)).andExpect(jsonPath("$.data.after.totalInterest").value("9.73")).andReturn());
  for(var entry:before.entrySet())assertThat(jdbc.queryForList("select * from "+entry.getKey()+" where household_id=? order by 1,2,3",household)).as(entry.getKey()).isEqualTo(entry.getValue());
  assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(500000);
  assertThat(ledger.balance(household,"LOAN:"+loan)).isEqualTo(120000);
  var shortened=data(quote(loan,"1196.40","REDUCE_TERM",account).andExpect(status().isOk())
   .andExpect(jsonPath("$.data.after.periodCount").value(1)).andExpect(jsonPath("$.data.after.principalAmount").value("3.60"))
   .andExpect(jsonPath("$.data.after.totalInterest").value("0.04")).andReturn());
  prepay(loan,body(fixed,"valid-fractional")).andExpect(status().isOk()).andExpect(jsonPath("$.data.remainingPrincipal").value("3.60"));
  assertThat(pending(loan)).isEqualTo(360);
  assertThat(jdbc.queryForObject("select count(*) from loan_installments where loan_id=? and status='PENDING' and principal_amount+interest_amount<=0",Long.class,loan)).isZero();
 }
 private void withEarlierSnapshot(Checked outside,Checked inside)throws Exception{var pool=java.util.concurrent.Executors.newSingleThreadExecutor();var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);if(Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->"MySQL".equals(c.getMetaData().getDatabaseProductName()))))tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);try{tx.executeWithoutResult(s->{jdbc.queryForObject("select count(*) from loan_installments where household_id=?",Long.class,household);try{pool.submit(()->{outside.run();return null;}).get(10,java.util.concurrent.TimeUnit.SECONDS);inside.run();if(s.isRollbackOnly())s.setRollbackOnly();}catch(Exception e){throw new RuntimeException(e);}});}finally{pool.shutdownNow();assertThat(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}}
 @FunctionalInterface private interface Checked{void run()throws Exception;}
 private ResultActions quote(long loan,String amount,String strategy,long selected)throws Exception{return mvc.perform(get("/api/loans/"+loan+"/prepayment-preview").session(session).param("amount",amount).param("paidOn","2026-01-01").param("strategy",strategy).param("paymentAccountId",String.valueOf(selected)));}
 private String body(JsonNode q,String key){return "{\"amount\":\""+q.path("principalAmount").asText()+"\",\"paidOn\":\""+q.path("paidOn").asText()+"\",\"strategy\":\""+q.path("strategy").asText()+"\",\"paymentAccountId\":"+q.path("paymentAccountId").asLong()+",\"planToken\":\""+q.path("planToken").asText()+"\",\"idempotencyKey\":\""+key+"\"}";}
 private ResultActions prepay(long loan,String body)throws Exception{return mvc.perform(post("/api/loans/"+loan+"/prepay").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));}
 private long create(String rate)throws Exception{String body="{\"name\":\"Strategy\",\"type\":\"OTHER\",\"memberId\":"+member+",\"assignedUserId\":"+user+",\"paymentAccountId\":"+account+",\"paymentCategoryId\":"+category+",\"principal\":\"1200.00\",\"annualRate\":"+rate+",\"termMonths\":12,\"repaymentMethod\":\"EQUAL_PAYMENT\",\"startOn\":\"2025-12-31\",\"fundingMode\":\"OPENING\",\"accountingOn\":\"2026-01-01\"}";return data(mvc.perform(post("/api/loans").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn()).path("id").asLong();}
 private long count(String table){return jdbc.queryForObject("select count(*) from "+table+" where household_id=?",Long.class,household);}private long pending(long loan){return jdbc.queryForObject("select count(*) from loan_installments where loan_id=? and status='PENDING'",Long.class,loan);}
 private void fund(String amount)throws Exception{mvc.perform(patch("/api/accounts/"+account).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"openingBalance\":\""+amount+"\",\"openingOn\":\"2026-01-01\"}")).andExpect(status().isOk());}
 private JsonNode data(MvcResult r)throws Exception{return json.readTree(r.getResponse().getContentAsString()).path("data");}
}
