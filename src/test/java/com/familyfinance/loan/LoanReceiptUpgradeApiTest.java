package com.familyfinance.loan;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
class LoanReceiptUpgradeApiTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;
 MockHttpSession session;long household,member,user,category,account;
 @BeforeEach void setup()throws Exception{
  String email=UUID.randomUUID()+"@upgrade.test";
  mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"displayName\":\"Upgrade\",\"password\":\"loan-test-password\",\"mode\":\"CREATE\",\"householdName\":\"Upgrade test\"}")).andExpect(status().isCreated());
  session=(MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",email).param("password","loan-test-password")).andExpect(status().isOk()).andReturn().getRequest().getSession(false);
  user=jdbc.queryForObject("select id from app_users where email=?",Long.class,email);household=jdbc.queryForObject("select household_id from app_users where id=?",Long.class,user);member=jdbc.queryForObject("select id from family_members where household_id=?",Long.class,household);category=jdbc.queryForObject("select min(id) from categories where household_id=? and kind='EXPENSE'",Long.class,household);account=jdbc.queryForObject("select id from financial_accounts where household_id=?",Long.class,household);
  mvc.perform(patch("/api/accounts/"+account).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"openingBalance\":\"5000.00\",\"openingOn\":\"2026-01-01\"}")).andExpect(status().isOk());
 }
 @ParameterizedTest @CsvSource({"3,false","4,false","4,true"})
 void historicalPrepaymentReceiptReplaysWithoutRepostingAndKeepsEveryOldFieldBound(int fields,boolean explicitAccount)throws Exception{
  long loan=data(create(createBody(),"create").andExpect(status().isCreated()).andReturn()).path("id").asLong();
  String old="{\"amount\":\"300.00\",\"paidOn\":\"2026-01-01\",\"idempotencyKey\":\"old-prepay\""+(fields==4?",\"paymentAccountId\":"+(explicitAccount?account:"null"):"")+"}";
  var original=data(postBody("/api/loans/"+loan+"/prepay",old,null).andExpect(status().isOk()).andReturn());
  String digest=seed("old-prepay","LOAN_PREPAYMENT:"+loan,old);var before=snapshot();
  postBody("/api/loans/"+loan+"/prepay",old,null).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(original.path("id").asLong())).andExpect(jsonPath("$.data.transactionId").value(original.path("transactionId").asLong()));
  for(String changed:List.of(old.replace("300.00","301.00"),old.replace("2026-01-01","2026-01-02"),append(old,"\"strategy\":\"REDUCE_TERM\""),append(old,"\"planToken\":\"new-token\""),fields==3?append(old,"\"paymentAccountId\":"+account):old.replace("\"paymentAccountId\":"+(explicitAccount?account:"null"),"\"paymentAccountId\":"+(account+999))))
   postBody("/api/loans/"+loan+"/prepay",changed,null).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
  assertThat(snapshot()).isEqualTo(before);assertThat(stored("old-prepay")).isEqualTo(digest);
 }
 @Test void historicalCreateReceiptReplaysWithoutDuplicateLoanOrAssetAndDoesNotStripExplicitFlag()throws Exception{
  String old=createBody();var original=data(create(old,"old-create").andExpect(status().isCreated()).andReturn());String digest=seed("old-create","LOAN_CREATE",old);var before=snapshot();
  create(old,"old-create").andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(original.path("id").asLong()));
  for(String changed:List.of(old.replace("1200.00","1300.00"),old.replace("2025-12-31","2025-12-30"),old.replace("\"paymentAccountId\":"+account,"\"paymentAccountId\":"+(account+999)),append(old,"\"createPurchasedAsset\":false"),append(old,"\"createPurchasedAsset\":true")))create(changed,"old-create").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
  assertThat(snapshot()).isEqualTo(before);assertThat(stored("old-create")).isEqualTo(digest);
 }
 @Test void historicalSingleDateInstallmentReceiptReplaysOriginalCashAndRejectsChangedDateOrAccount()throws Exception{
  long loan=data(create(createBody(),"create").andExpect(status().isCreated()).andReturn()).path("id").asLong();long installment=jdbc.queryForObject("select min(id) from loan_installments where loan_id=?",Long.class,loan);
  String path="/api/loan-installments/"+installment+"/confirm",old="{\"paidOn\":\"2026-01-31\"}";
  var original=data(postBody(path,old,"old-payment").andExpect(status().isOk()).andReturn());String digest=seed("old-payment","LOAN_PAYMENT:"+installment,old);var before=snapshot();
  postBody(path,old,"old-payment").andExpect(status().isOk()).andExpect(jsonPath("$.data.confirmedTransactionId").value(original.path("confirmedTransactionId").asLong()));
  postBody(path,old.replace("2026-01-31","2026-02-01"),"old-payment").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
  postBody(path,append(old,"\"paymentAccountId\":"+account),"old-payment").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
  assertThat(snapshot()).isEqualTo(before);assertThat(stored("old-payment")).isEqualTo(digest);
 }
 @Test void currentCreateFlagAndCurrentPaymentAccountReceiptsRetainExactBinding()throws Exception{
  String purchase=append(createBody().replace("\"fundingMode\":\"OPENING\"","\"fundingMode\":\"FINANCED_PURCHASE\""),"\"createPurchasedAsset\":true");long loan=data(create(purchase,"current-create").andExpect(status().isCreated()).andReturn()).path("id").asLong();var before=snapshot();
  create(purchase,"current-create").andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(loan));create(purchase.replace("\"createPurchasedAsset\":true","\"createPurchasedAsset\":null"),"current-create").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));assertThat(snapshot()).isEqualTo(before);
  long installment=jdbc.queryForObject("select min(id) from loan_installments where loan_id=?",Long.class,loan);String path="/api/loan-installments/"+installment+"/confirm",body="{\"paidOn\":\"2026-01-31\",\"paymentAccountId\":"+account+"}";
  postBody(path,body,"current-payment").andExpect(status().isOk());before=snapshot();postBody(path,body,"current-payment").andExpect(status().isOk());postBody(path,"{\"paidOn\":\"2026-01-31\"}","current-payment").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));assertThat(snapshot()).isEqualTo(before);
 }
 private String seed(String key,String operation,String literalOldShape)throws Exception{
  // Deliberately independent of production DTOs and digest helpers: literal JSON from the old wire contract.
  String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((operation+":"+user+":"+literalOldShape).getBytes(StandardCharsets.UTF_8)));
  assertThat(jdbc.update("update accounting_commands set request_digest=? where household_id=? and request_key=?",digest,household,key)).isEqualTo(1);return digest;
 }
 private String stored(String key){return jdbc.queryForObject("select request_digest from accounting_commands where household_id=? and request_key=?",String.class,household,key);}
 private List<Long> snapshot(){var values=new ArrayList<Long>();for(String table:List.of("loans","assets","loan_prepayments","financial_transactions","ledger_journals","accounting_commands","loan_installments"))values.add(jdbc.queryForObject("select count(*) from "+table+" where household_id=?",Long.class,household));values.addAll(jdbc.queryForList("select balance_cents from ledger_accounts where household_id=? order by account_code",Long.class,household));return values;}
 private String createBody(){return "{\"name\":\"Upgrade\",\"type\":\"OTHER\",\"linkedAssetId\":null,\"memberId\":"+member+",\"assignedUserId\":"+user+",\"paymentAccountId\":"+account+",\"paymentCategoryId\":"+category+",\"principal\":\"1200.00\",\"annualRate\":0,\"termMonths\":12,\"repaymentMethod\":\"EQUAL_PAYMENT\",\"startOn\":\"2025-12-31\",\"customSchedule\":null,\"fundingMode\":\"OPENING\",\"accountingOn\":\"2026-01-01\",\"disbursementAccountId\":null}";}
 private static String append(String json,String field){return json.substring(0,json.length()-1)+","+field+"}";}
 private ResultActions create(String body,String key)throws Exception{return postBody("/api/loans",body,key);}
 private ResultActions postBody(String path,String body,String key)throws Exception{var call=post(path).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);if(key!=null)call.header("Idempotency-Key",key);return mvc.perform(call);}
 private JsonNode data(MvcResult r)throws Exception{return json.readTree(r.getResponse().getContentAsString()).path("data");}
}
