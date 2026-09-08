package com.familyfinance.loan;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
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
class LoanTermOptionsApiTest {
 @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
 MockHttpSession session;long household,member,user,category,account;
 @BeforeEach void setup()throws Exception{
  String email=UUID.randomUUID()+"@precision.test";
  mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"displayName\":\"Precision\",\"password\":\"loan-test-password\",\"mode\":\"CREATE\",\"householdName\":\"Precision\"}")).andExpect(status().isCreated());
  session=(MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",email).param("password","loan-test-password")).andExpect(status().isOk()).andReturn().getRequest().getSession(false);
  user=jdbc.queryForObject("select id from app_users where email=?",Long.class,email);household=jdbc.queryForObject("select household_id from app_users where id=?",Long.class,user);member=jdbc.queryForObject("select id from family_members where household_id=?",Long.class,household);category=jdbc.queryForObject("select min(id) from categories where household_id=? and kind='EXPENSE'",Long.class,household);account=jdbc.queryForObject("select id from financial_accounts where household_id=?",Long.class,household);
  mvc.perform(patch("/api/accounts/"+account).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"openingBalance\":\"5000.00\",\"openingOn\":\"2026-01-01\"}")).andExpect(status().isOk());
 }
 @Test void savesFractionalMetadataAndPostsInterestOnlyWithoutZeroLedgerLegOrClosingLoan()throws Exception{
  long loan=create("3.60","0.12",360);
  long first=jdbc.queryForObject("select min(id) from loan_installments where loan_id=?",Long.class,loan);
  mvc.perform(post("/api/loan-installments/"+first+"/confirm").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"paidOn\":\"2026-01-31\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.principal").value("0.00")).andExpect(jsonPath("$.data.interest").value("0.04"));
  assertThat(jdbc.queryForObject("select status from loans where id=?",String.class,loan)).isEqualTo("ACTIVE");
  assertThat(jdbc.queryForObject("select current_principal_cents from loans where id=?",Long.class,loan)).isEqualTo(360);
  assertThat(jdbc.queryForObject("select count(*) from ledger_entries e join ledger_journals j on e.journal_id=j.id where j.source_type='LOAN_PAYMENT' and j.source_id=?",Long.class,first)).isEqualTo(2);
  assertThat(jdbc.queryForObject("select precise_interest_amount from loan_installments where id=?",java.math.BigDecimal.class,first)).isEqualByComparingTo("0.036");
  assertThat(jdbc.queryForObject("select interest_carry_amount from loan_installments where id=?",java.math.BigDecimal.class,first)).isEqualByComparingTo("-0.004");
  var a=options(loan,"0.01","2026-01-31").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  var b=options(loan,"0.01","2026-01-31").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  assertThat(b).isEqualTo(a);
 }
 @Test void optionalMinimumChangesOnlyPolicyAndInvalidatesOldQuote()throws Exception{
  long loan=create("1200.00","0",12);
  var q=data(mvc.perform(get("/api/loans/"+loan+"/prepayment-preview").session(session).param("amount","300.00").param("paidOn","2026-01-01")).andExpect(status().isOk()).andReturn());
  var before=jdbc.queryForList("select * from loan_installments where loan_id=? order by id",loan);
  mvc.perform(patch("/api/loans/"+loan+"/repayment-policy").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"minimumInstallmentAmount\":\"100.00\",\"sourceNote\":\"My contract\",\"revision\":0}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(1));
  assertThat(jdbc.queryForList("select * from loan_installments where loan_id=? order by id",loan)).isEqualTo(before);
  mvc.perform(patch("/api/loans/"+loan).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"termMonths\":13}")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_CONTRACT_MINIMUM"));
  options(loan,"300.00","2026-01-01").andExpect(status().isOk()).andExpect(jsonPath("$.data.options[8].allowed").value(true)).andExpect(jsonPath("$.data.options[9].allowed").value(false));
  mvc.perform(post("/api/loans/"+loan+"/prepay").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"300.00\",\"paidOn\":\"2026-01-01\",\"planToken\":\""+q.path("planToken").asText()+"\",\"idempotencyKey\":\"stale-policy\"}")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_PLAN_CHANGED"));
  long first=jdbc.queryForObject("select min(id) from loan_installments where loan_id=?",Long.class,loan);
  mvc.perform(post("/api/loan-installments/"+first+"/confirm").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"paidOn\":\"2026-01-31\"}")).andExpect(status().isOk());
 }
 @Test void postDueProjectionOffersOnlyFeasibleTermsAndNeverMutates()throws Exception{
  long loan=create("12.00","0",12);
  options(loan,"10.99","2026-01-31").andExpect(status().isOk()).andExpect(jsonPath("$.data.remainingPrincipal").value("0.01")).andExpect(jsonPath("$.data.options.length()").value(11)).andExpect(jsonPath("$.data.options[0].allowed").value(true)).andExpect(jsonPath("$.data.options[1].allowed").value(false));
  options(loan,"11.00","2026-01-31").andExpect(status().isOk()).andExpect(jsonPath("$.data.remainingPrincipal").value("0.00")).andExpect(jsonPath("$.data.options.length()").value(0));
  assertThat(jdbc.queryForObject("select count(*) from loan_installments where loan_id=? and status='PENDING'",Long.class,loan)).isEqualTo(12);
  assertThat(jdbc.queryForObject("select count(*) from financial_transactions where household_id=?",Long.class,household)).isZero();
  jdbc.update("update household_memberships set role='MEMBER' where household_id=? and user_id=?",household,user);
  options(loan,"1.00","2026-01-01").andExpect(status().isForbidden());
  mvc.perform(patch("/api/loans/"+loan+"/repayment-policy").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"revision\":0}")).andExpect(status().isForbidden());
 }
 @Test void reloadAndRepreviewRetainPaidSubcentBaselineAndIgnoreCancelledFutureMetadata()throws Exception{
  long loan=create("49.00","0.0012",3);
  long first=jdbc.queryForObject("select min(id) from loan_installments where loan_id=?",Long.class,loan);
  mvc.perform(post("/api/loan-installments/"+first+"/confirm").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"paidOn\":\"2026-01-31\"}")).andExpect(status().isOk());
  assertThat(jdbc.queryForObject("select precise_interest_amount from loan_installments where id=?",java.math.BigDecimal.class,first)).isEqualByComparingTo("0.0049");
  var q=data(mvc.perform(get("/api/loans/"+loan+"/prepayment-preview").session(session).param("amount","31.66").param("paidOn","2026-01-31")).andExpect(status().isOk()).andExpect(jsonPath("$.data.after.schedule[0].principal").value("0.49")).andExpect(jsonPath("$.data.after.schedule[0].interest").value("0.01")).andExpect(jsonPath("$.data.after.schedule[1].principal").value("0.51")).andExpect(jsonPath("$.data.after.schedule[1].interest").value("0.00")).andReturn());
  mvc.perform(post("/api/loans/"+loan+"/prepay").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"31.66\",\"paidOn\":\"2026-01-31\",\"planToken\":\""+q.path("planToken").asText()+"\",\"idempotencyKey\":\"carry\"}")).andExpect(status().isOk());
  mvc.perform(get("/api/loans/"+loan+"/schedule").session(session).param("view","CURRENT")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].precisePrincipalAmount").value("0.499900000000")).andExpect(jsonPath("$.data[1].precisePrincipalAmount").value("0.500100000000"));
  long second=jdbc.queryForObject("select min(id) from loan_installments where loan_id=? and status='PENDING'",Long.class,loan);
  mvc.perform(post("/api/loan-installments/"+second+"/confirm").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"paidOn\":\"2026-02-28\"}")).andExpect(status().isOk());
  mvc.perform(get("/api/loans/"+loan+"/prepayment-preview").session(session).param("amount","0.01").param("paidOn","2026-02-28")).andExpect(status().isOk()).andExpect(jsonPath("$.data.after.schedule[0].principal").value("0.50")).andExpect(jsonPath("$.data.after.schedule[0].interest").value("0.00"));
 }
 @Test void policyValidationAndHouseholdScopeProtectStoredRules()throws Exception{
  long loan=create("1200.00","0",12);
  mvc.perform(patch("/api/loans/"+loan+"/repayment-policy").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"minimumInstallmentAmount\":\"1.001\",\"revision\":0}")).andExpect(status().isBadRequest());
  mvc.perform(patch("/api/loans/"+loan+"/repayment-policy").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"minimumInstallmentAmount\":\"100.00\",\"revision\":0}")).andExpect(status().isOk());
  mvc.perform(patch("/api/loans/"+loan+"/repayment-policy").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"minimumInstallmentAmount\":\"200.00\",\"revision\":0}")).andExpect(status().isConflict());
  assertThat(jdbc.queryForObject("select count(*) from loan_repayment_policy_history where loan_id=?",Long.class,loan)).isEqualTo(1);
  setup(); // An authenticated administrator of a different household.
  mvc.perform(get("/api/loans/"+loan+"/repayment-policy").session(session)).andExpect(status().isNotFound());
  options(loan,"1.00","2026-01-01").andExpect(status().isNotFound());
  mvc.perform(patch("/api/loans/"+loan+"/repayment-policy").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1}")).andExpect(status().isNotFound());
 }
 @Test void customRatioSurvivesSavingAndDoesNotDriftToRoundedInterestOnNextPreview()throws Exception{
  String body="{\"name\":\"Custom\",\"type\":\"OTHER\",\"memberId\":"+member+",\"assignedUserId\":"+user+",\"paymentAccountId\":"+account+",\"paymentCategoryId\":"+category+",\"principal\":\"1.00\",\"annualRate\":0,\"termMonths\":3,\"repaymentMethod\":\"CUSTOM\",\"startOn\":\"2025-12-31\",\"fundingMode\":\"OPENING\",\"accountingOn\":\"2026-01-01\",\"customSchedule\":[{\"dueOn\":\"2026-01-31\",\"principal\":\"0.33\",\"interest\":\"0.01\"},{\"dueOn\":\"2026-02-28\",\"principal\":\"0.33\",\"interest\":\"0.01\"},{\"dueOn\":\"2026-03-31\",\"principal\":\"0.34\",\"interest\":\"0.01\"}]}";
  mvc.perform(post("/api/loans").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body.replace("\"principal\":\"0.33\"","\"principal\":null"))).andExpect(status().isBadRequest());
  long loan=data(mvc.perform(post("/api/loans").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn()).path("id").asLong();
  mvc.perform(post("/api/loans/"+loan+"/prepay").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"0.01\",\"paidOn\":\"2026-01-01\",\"idempotencyKey\":\"custom-ratio\"}")).andExpect(status().isOk());
  assertThat(jdbc.queryForObject("select custom_rate_principal_amount from loan_installments where loan_id=? and status='PENDING' order by installment_no limit 1",java.math.BigDecimal.class,loan)).isEqualByComparingTo("1.00");
  mvc.perform(get("/api/loans/"+loan+"/prepayment-preview").session(session).param("amount","0.49").param("paidOn","2026-01-01")).andExpect(status().isOk()).andExpect(jsonPath("$.data.after.schedule[0].preciseInterestAmount").value("0.005000000000"));
  options(loan,"0.49","2026-01-01").andExpect(status().isOk()).andExpect(jsonPath("$.data.options[1].allowed").value(true)).andExpect(jsonPath("$.data.options[1].roundingPolicy").value("CUSTOM_REALLOCATION_V2"));
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={1,2})
 void oversizedCustomPrincipalReturnsValidationWithoutCreatingFinancialState(int periods)throws Exception{
  var before=new LinkedHashMap<String,List<Map<String,Object>>>();
  for(String table:List.of("loans","loan_installments","loan_prepayments","assets","ledger_journals","ledger_entries","ledger_accounts","ledger_sources","accounting_commands","financial_transactions"))
   before.put(table,jdbc.queryForList("select * from "+table+" where household_id=? order by 1,2,3",household));
  String rows=periods==1
    ?"[{\"dueOn\":\"2026-01-31\",\"principal\":\"92233720368547758.07\",\"interest\":\"0.01\"}]"
    :"[{\"dueOn\":\"2026-01-31\",\"principal\":\"92233720368547758.07\",\"interest\":\"0\"},{\"dueOn\":\"2026-02-28\",\"principal\":\"0.01\",\"interest\":\"0\"}]";
  String body="{\"name\":\"Oversized custom purchase\",\"type\":\"OTHER\",\"memberId\":"+member+",\"assignedUserId\":"+user+",\"paymentAccountId\":"+account+",\"paymentCategoryId\":"+category+",\"principal\":\"1.00\",\"annualRate\":0,\"termMonths\":"+periods+",\"repaymentMethod\":\"CUSTOM\",\"startOn\":\"2025-12-31\",\"fundingMode\":\"FINANCED_PURCHASE\",\"createPurchasedAsset\":true,\"accountingOn\":\"2026-01-01\",\"customSchedule\":"+rows+"}";
  mvc.perform(post("/api/loans").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.error.fields.customSchedule").exists());
  for(var entry:before.entrySet())assertThat(jdbc.queryForList("select * from "+entry.getKey()+" where household_id=? order by 1,2,3",household)).as(entry.getKey()).isEqualTo(entry.getValue());
 }
 private ResultActions options(long loan,String amount,String day)throws Exception{return mvc.perform(get("/api/loans/"+loan+"/term-options").session(session).param("additionalPrincipal",amount).param("paidOn",day).param("paymentAccountId",String.valueOf(account)));}
 private long create(String principal,String rate,int term)throws Exception{
  String body="{\"name\":\"Precision\",\"type\":\"OTHER\",\"memberId\":"+member+",\"assignedUserId\":"+user+",\"paymentAccountId\":"+account+",\"paymentCategoryId\":"+category+",\"principal\":\""+principal+"\",\"annualRate\":"+rate+",\"termMonths\":"+term+",\"repaymentMethod\":\"EQUAL_PAYMENT\",\"startOn\":\"2025-12-31\",\"fundingMode\":\"OPENING\",\"accountingOn\":\"2026-01-01\"}";
  return data(mvc.perform(post("/api/loans").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn()).path("id").asLong();
 }
 private JsonNode data(MvcResult r)throws Exception{return json.readTree(r.getResponse().getContentAsString()).path("data");}
}
