package com.familyfinance.loan;

import static org.assertj.core.api.Assertions.assertThat;
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
class LoanPayoffApiTest {
 @Autowired MockMvc mvc; @Autowired ObjectMapper json; @org.springframework.test.context.bean.override.mockito.MockitoSpyBean JdbcTemplate jdbc; @Autowired LedgerReadService ledger;
 @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
 MockHttpSession session; long household,member,user,category,account;
 @BeforeEach void setup()throws Exception{
  String email=UUID.randomUUID()+"@payoff.test";
  mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"displayName\":\"Payoff\",\"password\":\"loan-test-password\",\"mode\":\"CREATE\",\"householdName\":\"Payoff test\"}")).andExpect(status().isCreated());
  session=(MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",email).param("password","loan-test-password")).andExpect(status().isOk()).andReturn().getRequest().getSession(false);
  user=jdbc.queryForObject("select id from app_users where email=?",Long.class,email);household=jdbc.queryForObject("select household_id from app_users where id=?",Long.class,user);
  member=jdbc.queryForObject("select id from family_members where household_id=?",Long.class,household);category=jdbc.queryForObject("select min(id) from categories where household_id=? and kind='EXPENSE'",Long.class,household);account=jdbc.queryForObject("select id from financial_accounts where household_id=?",Long.class,household);
 }
 @Test void quoteExcludesFutureInterestAndExactPayoffPostsOneSplitAndReplay()throws Exception{
  fund("2100.00");long loan=create();long journals=count("ledger_journals");
  JsonNode q=data(quote(loan).andExpect(status().isOk()).andExpect(jsonPath("$.data.cashAmount").value("2100.00")).andExpect(jsonPath("$.data.futureScheduledInterest").value("50.00")).andReturn());
  assertThat(count("ledger_journals")).isEqualTo(journals);
  String body=payoffBody(q,"payoff");
  JsonNode paid=data(payoff(loan,body).andExpect(status().isOk()).andExpect(jsonPath("$.data.operationKind").value("PAYOFF")).andExpect(jsonPath("$.data.cashAmount").value("2100.00")).andExpect(jsonPath("$.data.interestAmount").value("100.00")).andReturn());
  payoff(loan,body).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(paid.path("id").asLong()));
  payoff(loan,body.replace("2026-01-03","2026-01-04")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
  assertThat(count("financial_transactions")).isEqualTo(1);assertThat(count("loan_prepayments")).isEqualTo(1);assertThat(count("ledger_journals")).isEqualTo(journals+1);
  assertThat(ledger.balance(household,"CASH:"+account)).isZero();assertThat(ledger.balance(household,"LOAN:"+loan)).isZero();assertThat(ledger.balance(household,"EXPENSE:"+category)).isEqualTo(10000);
  mvc.perform(get("/api/loans/"+loan).session(session)).andExpect(jsonPath("$.data.paidRepaymentTotal").value("2100.00")).andExpect(jsonPath("$.data.remainingRepaymentTotal").value("0.00")).andExpect(jsonPath("$.data.scheduledRepaymentTotal").value("2100.00"));
  assertThat(ledger.balances(household)).isEqualTo(ledger.reconstructedBalances(household));
 }
 @Test void insufficientCashRollsBackAndSameKeyCanRetryAfterFunding()throws Exception{
  fund("2099.99");long loan=create();String body=payoffBody(data(quote(loan).andExpect(status().isOk()).andReturn()),"retry");long journals=count("ledger_journals");
  payoff(loan,body).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INSUFFICIENT_FUNDS"));
  assertThat(count("loan_prepayments")).isZero();assertThat(count("financial_transactions")).isZero();assertThat(count("ledger_journals")).isEqualTo(journals);assertThat(ledger.balance(household,"LOAN:"+loan)).isEqualTo(200000);
  assertThat(jdbc.queryForObject("select count(*) from loan_installments where loan_id=? and status='PENDING'",Long.class,loan)).isEqualTo(2);
  fund("2100.00");payoff(loan,body).andExpect(status().isOk());
 }
 @Test void tokensBindDateAccountInterestAndCurrentPlan()throws Exception{
  fund("5000.00");long loan=create();String body=payoffBody(data(quote(loan).andExpect(status().isOk()).andReturn()),"stale");
  payoff(loan,body.replace("2026-01-03","2026-01-04")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_PLAN_CHANGED"));
  payoff(loan,body.replace("\"interestAmount\":null","\"interestAmount\":\"101.00\"")).andExpect(status().isConflict());
  mvc.perform(post("/api/loan-installments/"+first(loan)+"/confirm").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"paidOn\":\"2026-01-03\"}")).andExpect(status().isOk());
  payoff(loan,body).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_PLAN_CHANGED"));
  mvc.perform(get("/api/loans/"+loan).session(session)).andExpect(jsonPath("$.data.paidRepaymentTotal").value("1100.00")).andExpect(jsonPath("$.data.remainingRepaymentTotal").value("1050.00")).andExpect(jsonPath("$.data.scheduledRepaymentTotal").value("2150.00"));
  payoff(loan,payoffBody(data(quote(loan).andReturn()),"fresh")).andExpect(status().isOk());
  assertThat(jdbc.queryForObject("select status from loan_installments where id=?",String.class,first(loan))).isEqualTo("PAID");
 }
 @Test void interestAndDateGuardsAndLegacyFullPrepayCannotWaiveDues()throws Exception{
  fund("5000.00");long loan=create();
  mvc.perform(get("/api/loans/"+loan+"/payoff-quote").session(session).param("paidOn","2026-01-03").param("interestAmount","99.99")).andExpect(status().isBadRequest());
  mvc.perform(get("/api/loans/"+loan+"/payoff-quote").session(session).param("paidOn","2099-01-01")).andExpect(status().isBadRequest());
  mvc.perform(get("/api/loans/"+loan+"/payoff-quote").session(session).param("paidOn","2025-01-01")).andExpect(status().isConflict());
  mvc.perform(post("/api/loans/"+loan+"/prepay").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"2000.00\",\"paidOn\":\"2026-01-03\",\"idempotencyKey\":\"legacy\"}")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_PAYOFF_REQUIRED"));
  assertThat(count("financial_transactions")).isZero();
 }
 @Test void financedPurchasePayoffDoesNotShrinkPurchasedAsset()throws Exception{
  fund("2200.00");long loan=createBody(body().replace("\"fundingMode\":\"OPENING\"","\"fundingMode\":\"FINANCED_PURCHASE\",\"createPurchasedAsset\":true"));
  long asset=jdbc.queryForObject("select purchased_asset_id from loans where id=?",Long.class,loan);
  var q=data(mvc.perform(get("/api/loans/"+loan+"/payoff-quote").session(session).param("paidOn","2026-01-03").param("interestAmount","200.00")).andExpect(status().isOk()).andReturn());
  payoff(loan,payoffBody(q,"purchased").replace("\"interestAmount\":null","\"interestAmount\":\"200.00\"")).andExpect(status().isOk()).andExpect(jsonPath("$.data.cashAmount").value("2200.00"));
  assertThat(ledger.balance(household,"ASSET:"+asset)).isEqualTo(200000);assertThat(jdbc.queryForObject("select current_value_cents from assets where id=?",Long.class,asset)).isEqualTo(200000);
  assertThat(jdbc.queryForObject("select count(*) from loan_installments where loan_id=? and cancelled_by_prepayment_id is not null",Long.class,loan)).isEqualTo(2);
 }
 @Test void selectedAccountNeverPoolsFundsAndAllThreePaymentPathsKeepDefault()throws Exception{
  fund("9000.00");long other=data(mvc.perform(post("/api/accounts").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"还款账户\",\"type\":\"BANK\",\"currency\":\"CNY\",\"openingBalance\":\"1100.00\",\"openingOn\":\"2026-01-01\"}")).andExpect(status().isCreated()).andReturn()).path("id").asLong();
  long loan=create();
  var q=data(mvc.perform(get("/api/loans/"+loan+"/payoff-quote").session(session).param("paidOn","2026-01-03").param("paymentAccountId",String.valueOf(other))).andExpect(status().isOk()).andReturn());
  payoff(loan,payoffBody(q,"wrong-funds").replace("\"paymentAccountId\":"+account,"\"paymentAccountId\":"+other)).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INSUFFICIENT_FUNDS"));
  mvc.perform(post("/api/loan-installments/"+first(loan)+"/confirm").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"paidOn\":\"2026-01-03\",\"paymentAccountId\":"+other+"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.paymentAccountId").value(other));
  assertThat(ledger.balance(household,"CASH:"+other)).isZero();assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(900000);
  mvc.perform(post("/api/loans/"+loan+"/prepay").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"100.00\",\"paidOn\":\"2026-01-03\",\"paymentAccountId\":"+other+",\"idempotencyKey\":\"selected-prepay\"}")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INSUFFICIENT_FUNDS"));
  assertThat(jdbc.queryForObject("select payment_account_id from loans where id=?",Long.class,loan)).isEqualTo(account);
  mvc.perform(patch("/api/accounts/"+other).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"openingBalance\":\"2200.00\",\"openingOn\":\"2026-01-01\"}")).andExpect(status().isOk());
  mvc.perform(post("/api/loans/"+loan+"/prepay").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"100.00\",\"paidOn\":\"2026-01-03\",\"paymentAccountId\":"+other+",\"idempotencyKey\":\"selected-prepay\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.paymentAccountId").value(other));
  q=data(mvc.perform(get("/api/loans/"+loan+"/payoff-quote").session(session).param("paidOn","2026-01-03").param("paymentAccountId",String.valueOf(other))).andExpect(status().isOk()).andReturn());
  payoff(loan,payoffBody(q,"selected-payoff").replace("\"paymentAccountId\":"+account,"\"paymentAccountId\":"+other)).andExpect(status().isOk()).andExpect(jsonPath("$.data.paymentAccountId").value(other));
  assertThat(ledger.balance(household,"CASH:"+other)).isEqualTo(10000);assertThat(ledger.balance(household,"CASH:"+account)).isEqualTo(900000);
  assertThat(jdbc.queryForObject("select payment_account_id from loans where id=?",Long.class,loan)).isEqualTo(account);
 }
 @Test void wholeLoanTotalsIncludeBeyondFirstPageAndExcludeCancelledHistory()throws Exception{
  fund("9000.00");var request=(tools.jackson.databind.node.ObjectNode)json.readTree(body());request.put("principal","6000.00");request.put("termMonths",60);var schedule=request.putArray("customSchedule");
  for(int i=0;i<60;i++)schedule.addObject().put("dueOn",java.time.LocalDate.of(2026,1,2).plusMonths(i).toString()).put("principal","100.00").put("interest","1.00");
  long loan=createBody(json.writeValueAsString(request));
  mvc.perform(get("/api/loans/"+loan+"/schedule").session(session)).andExpect(jsonPath("$.data.length()").value(50));
  mvc.perform(get("/api/loans/"+loan).session(session)).andExpect(jsonPath("$.data.scheduledRepaymentTotal").value("6060.00"));
  mvc.perform(post("/api/loan-installments/"+first(loan)+"/confirm").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"paidOn\":\"2026-01-03\"}")).andExpect(status().isOk());
  mvc.perform(post("/api/loans/"+loan+"/prepay").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"100.00\",\"paidOn\":\"2026-01-03\",\"idempotencyKey\":\"history\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.paidRepaymentTotal").value("201.00"));
  var detail=data(mvc.perform(get("/api/loans/"+loan).session(session)).andReturn());
  long pending=jdbc.queryForObject("select sum(principal_cents+interest_cents) from loan_installments where loan_id=? and status='PENDING'",Long.class,loan);
  assertThat(detail.path("remainingRepaymentTotal").asText()).isEqualTo(com.familyfinance.shared.Money.formatCents(pending));assertThat(detail.path("scheduledRepaymentTotal").asText()).isEqualTo(com.familyfinance.shared.Money.formatCents(pending+20100));
  mvc.perform(patch("/api/loans/"+loan).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Updated\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.scheduledRepaymentTotal").value(detail.path("scheduledRepaymentTotal").asText()));
 }
 @Test void earlierRepeatableReadSnapshotSeesCurrentPlanAndCurrentReplayTotals()throws Exception{
  fund("5000.00");long loan=create();String old=payoffBody(data(quote(loan).andReturn()),"old");
  withEarlierSnapshot(()->mvc.perform(post("/api/loan-installments/"+first(loan)+"/confirm").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"paidOn\":\"2026-01-03\"}")).andExpect(status().isOk()),()->payoff(loan,old).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_PLAN_CHANGED")));
  String fresh=payoffBody(data(quote(loan).andReturn()),"current");
  withEarlierSnapshot(()->payoff(loan,fresh).andExpect(status().isOk()),()->payoff(loan,fresh).andExpect(status().isOk()).andExpect(jsonPath("$.data.paidRepaymentTotal").value("2100.00")).andExpect(jsonPath("$.data.remainingRepaymentTotal").value("0.00")));
  assertThat(count("financial_transactions")).isEqualTo(2);
 }
 @Test void foreignAccountsAndNonAdminCannotQuoteOrSettle()throws Exception{
  fund("5000.00");long loan=create();String request=payoffBody(data(quote(loan).andReturn()),"permissions");
  var savedSession=session;long h=household,a=account,u=user,m=member,c=category;setup();long foreign=account;session=savedSession;household=h;account=a;user=u;member=m;category=c;
  mvc.perform(get("/api/loans/"+loan+"/payoff-quote").session(session).param("paidOn","2026-01-03").param("paymentAccountId",String.valueOf(foreign))).andExpect(status().isBadRequest());
  payoff(loan,request.replace("\"paymentAccountId\":"+account,"\"paymentAccountId\":"+foreign)).andExpect(status().isBadRequest());
  mvc.perform(post("/api/loan-installments/"+first(loan)+"/confirm").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"paidOn\":\"2026-01-03\",\"paymentAccountId\":"+foreign+"}")).andExpect(status().isConflict());
  jdbc.update("update household_memberships set role='MEMBER' where household_id=? and user_id=?",household,user);
  quote(loan).andExpect(status().isForbidden());payoff(loan,request).andExpect(status().isForbidden());assertThat(count("financial_transactions")).isZero();
 }
 @Test void quoteDoesNotAuthorizeArchivedAccountAfterEarlierSnapshot()throws Exception{
  fund("2100.00");long loan=create();String body=payoffBody(data(quote(loan).andReturn()),"archived");long journals=count("ledger_journals");
  withEarlierSnapshot(()->jdbc.update("update financial_accounts set archived_at=CURRENT_TIMESTAMP where id=?",account),()->payoff(loan,body).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNT_ARCHIVED")));
  assertThat(count("financial_transactions")).isZero();assertThat(count("loan_prepayments")).isZero();assertThat(count("ledger_journals")).isEqualTo(journals);
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"detail","list","history"})
 void readonlyTotalsRemainOneSnapshotWhenPayoffCommitsBetweenTotalsQueries(String read)throws Exception{
  fund("5000.00");long loan=createBody(body().replace("\"annualRate\":0.1","\"annualRate\":0"));
  mvc.perform(post("/api/loans/"+loan+"/prepay").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"100.00\",\"paidOn\":\"2026-01-01\",\"idempotencyKey\":\"before-read\"}")).andExpect(status().isOk());
  String payment=payoffBody(data(quote(loan).andReturn()),"during-read");
  var pool=java.util.concurrent.Executors.newSingleThreadExecutor();var intercepted=new java.util.concurrent.atomic.AtomicBoolean();long readerThread=Thread.currentThread().getId();
  org.mockito.Mockito.doAnswer(call->{
   Object result=call.callRealMethod();String sql=call.getArgument(0);
   if(Thread.currentThread().getId()==readerThread&&sql.startsWith("select i.status,i.principal_cents")&&!sql.endsWith("for update")&&intercepted.compareAndSet(false,true))
    pool.submit(()->{payoff(loan,payment).andExpect(status().isOk());return null;}).get(10,java.util.concurrent.TimeUnit.SECONDS);
   return result;
  }).when(jdbc).query(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowMapper.class),org.mockito.ArgumentMatchers.any(Object[].class));
  try{
   String path=read.equals("list")?"/api/loans":read.equals("history")?"/api/loans/"+loan+"/prepayments":"/api/loans/"+loan;
   JsonNode response=data(mvc.perform(get(path).session(session)).andExpect(status().isOk()).andReturn());
   assertThat(intercepted).isTrue();
   JsonNode summary=read.equals("list")?response.path("items").get(0):read.equals("history")?response.get(0):response;
   assertThat(summary.path("scheduledRepaymentTotal").asText()).isEqualTo("2000.00");
   assertThat(summary.path("remainingRepaymentTotal").asText()).isEqualTo("1900.00");assertThat(summary.path("paidRepaymentTotal").asText()).isEqualTo("100.00");
   if(read.equals("history"))assertThat(response.size()).isEqualTo(1);
   else assertThat(summary.path("currentPrincipal").asText()).isEqualTo("1900.00");
  }finally{pool.shutdownNow();assertThat(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
  mvc.perform(get("/api/loans/"+loan).session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.paidRepaymentTotal").value("2000.00")).andExpect(jsonPath("$.data.remainingRepaymentTotal").value("0.00"));
 }
 private void withEarlierSnapshot(Checked outside,Checked inside)throws Exception{
  var pool=java.util.concurrent.Executors.newSingleThreadExecutor();var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
  if(Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->"MySQL".equals(c.getMetaData().getDatabaseProductName()))))tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
  try{tx.executeWithoutResult(ignored->{jdbc.queryForObject("select count(*) from loan_installments where household_id=?",Long.class,household);try{pool.submit(()->{outside.run();return null;}).get(10,java.util.concurrent.TimeUnit.SECONDS);inside.run();if(ignored.isRollbackOnly())ignored.setRollbackOnly();}catch(Exception e){throw new RuntimeException(e);}});}finally{pool.shutdownNow();assertThat(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
 }
 @FunctionalInterface private interface Checked{void run()throws Exception;}
 private ResultActions quote(long loan)throws Exception{return mvc.perform(get("/api/loans/"+loan+"/payoff-quote").session(session).param("paidOn","2026-01-03"));}
 private String payoffBody(JsonNode q,String key){return "{\"paidOn\":\"2026-01-03\",\"paymentAccountId\":"+account+",\"interestAmount\":null,\"planToken\":\""+q.path("planToken").asText()+"\",\"idempotencyKey\":\""+key+"\"}";}
 private ResultActions payoff(long id,String body)throws Exception{return mvc.perform(post("/api/loans/"+id+"/payoff").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));}
 private void fund(String amount)throws Exception{mvc.perform(patch("/api/accounts/"+account).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"openingBalance\":\""+amount+"\",\"openingOn\":\"2026-01-01\"}")).andExpect(status().isOk());}
 private long create()throws Exception{return createBody(body());}
 private long createBody(String body)throws Exception{return data(mvc.perform(post("/api/loans").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn()).path("id").asLong();}
 private String body(){return "{\"name\":\"Loan\",\"type\":\"OTHER\",\"memberId\":"+member+",\"assignedUserId\":"+user+",\"paymentAccountId\":"+account+",\"paymentCategoryId\":"+category+",\"principal\":\"2000.00\",\"annualRate\":0.1,\"termMonths\":2,\"repaymentMethod\":\"CUSTOM\",\"startOn\":\"2025-01-01\",\"fundingMode\":\"OPENING\",\"accountingOn\":\"2026-01-01\",\"customSchedule\":[{\"dueOn\":\"2026-01-02\",\"principal\":\"1000.00\",\"interest\":\"100.00\"},{\"dueOn\":\"2026-02-02\",\"principal\":\"1000.00\",\"interest\":\"50.00\"}]}";}
 private long first(long loan){return jdbc.queryForObject("select min(id) from loan_installments where loan_id=?",Long.class,loan);}
 private long count(String table){return jdbc.queryForObject("select count(*) from "+table+" where household_id=?",Long.class,household);}
 private JsonNode data(MvcResult result)throws Exception{return json.readTree(result.getResponse().getContentAsString()).path("data");}
}
