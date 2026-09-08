package com.familyfinance.reporting;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.familyfinance.accounting.AccountingTestFixtures;
import com.familyfinance.fx.*;
import com.familyfinance.market.*;
import com.familyfinance.investment.OverseasInvestmentService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties={"app.seed.enabled=true","app.multicurrency.enabled=true","app.fx.integration-case=true"})
@AutoConfigureMockMvc @ActiveProfiles("test") @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class MultiCurrencyReportingApiTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Autowired ExchangeRateStore fx;
 @Autowired ApplicationContext context;@Autowired NetWorthService worth;@Autowired OverseasInvestmentService overseas;
 @Autowired NetWorthSnapshotService snapshots;
 @MockitoBean MarketDataClient client;
 long h;
 MockHttpSession setup()throws Exception{
  var session=(MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username","demo").param("password","demo1234")).andReturn().getRequest().getSession(false);
  h=jdbc.queryForObject("select household_id from app_users where username='demo'",Long.class);AccountingTestFixtures.postFixtureTransactions(context,h);return session;
 }
 long account(MockHttpSession s,String currency,String amount)throws Exception{
  var result=mvc.perform(post("/api/accounts").session(s).with(csrf()).contentType("application/json").content("""
   {"name":"%s","type":"BANK","currency":"%s","openingBalance":"%s","openingOn":"2026-01-01"}
   """.formatted(UUID.randomUUID(),currency,amount))).andExpect(status().isCreated()).andReturn();
  return json.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
 }
 @Test void foreignCashIsConvertedInsteadOfAddedAsYuan()throws Exception{
  var session=setup();long baseline=worth.calculate(h,LocalDate.of(2026,1,2)).assetCents();
  fx.save(new ExchangeRateBatch("ECB",LocalDate.of(2026,1,1),Map.of("USD",new BigDecimal("7"),"HKD",new BigDecimal("0.9"))),Instant.now());
  account(session,"USD","100.00");
  assertThat(worth.calculate(h,LocalDate.of(2026,1,2)).assetCents()).isEqualTo(baseline+70000);
 }
 @Test void missingRateReturnsPartialNativeDetailNotAFalseCompleteTotal()throws Exception{
  var session=setup();long baseline=worth.calculate(h,LocalDate.of(2026,1,2)).assetCents();account(session,"USD","100.00");
  mvc.perform(get("/api/net-worth").param("asOf","2026-01-02").session(session)).andExpect(status().isOk())
   .andExpect(jsonPath("$.data.asset").doesNotExist()).andExpect(jsonPath("$.data.knownAsset").value(com.familyfinance.shared.Money.formatCents(baseline)))
   .andExpect(jsonPath("$.data.unconverted[0].currency").value("USD")).andExpect(jsonPath("$.data.unconverted[0].nativeAmount").value("100.00"));
 }
 @Test void regeneratedSnapshotsKeepTheirPreviousSavedValues()throws Exception{
  var session=setup();var day=LocalDate.of(2026,1,2);long baseline=worth.calculate(h,day).assetCents();
  fx.save(new ExchangeRateBatch("ECB",LocalDate.of(2026,1,1),Map.of("USD",new BigDecimal("7"),"HKD",new BigDecimal("0.9"))),Instant.now());
  account(session,"USD","100.00");snapshots.generate(h,day);
  fx.save(new ExchangeRateBatch("ECB",LocalDate.of(2026,1,1),Map.of("USD",new BigDecimal("8"),"HKD",BigDecimal.ONE)),Instant.now());
  snapshots.generate(h,day);snapshots.generate(h,day);
  var versions=snapshots.revisions(h,day);assertThat(versions).hasSize(2);
  assertThat(versions.get(0).asset()).isEqualTo(com.familyfinance.shared.Money.formatCents(baseline+80000));
  assertThat(versions.get(1).asset()).isEqualTo(com.familyfinance.shared.Money.formatCents(baseline+70000));
 }
 @Test void hongKongBuyQuotesAndBaseValuationWorkTogetherWithSubcentPrices()throws Exception{
  var session=setup();fx.save(new ExchangeRateBatch("ECB",LocalDate.of(2026,1,1),Map.of("USD",new BigDecimal("7"),"HKD",new BigDecimal("0.9"))),Instant.now());
  long cash=account(session,"HKD","500.00");
  var instrument=new OverseasInstrument("00700","腾讯控股","HK","HKD","HKEX","Asia/Hong_Kong");
  when(client.overseasSearch("HK","00700")).thenReturn(new OverseasSearchResponse(List.of(instrument),false,Instant.now(),false,"READY",null));
  var result=mvc.perform(post("/api/securities/overseas/resolve").session(session).with(csrf()).contentType("application/json").content("{\"market\":\"HK\",\"symbol\":\"00700\"}")).andExpect(status().isOk()).andReturn();
  long security=json.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
  var investment=mvc.perform(post("/api/investment-accounts").session(session).with(csrf()).contentType("application/json").content("""
   {"name":"HK broker","brokerName":"test","currency":"HKD","fundingAccountId":%d}
   """.formatted(cash))).andExpect(status().isCreated()).andReturn();
  long account=json.readTree(investment.getResponse().getContentAsString()).path("data").path("id").asLong();
  mvc.perform(post("/api/investment-trades").session(session).with(csrf()).contentType("application/json").content("""
   {"accountId":%d,"securityId":%d,"type":"BUY","quantity":"1000","price":"0.001234","fee":"0.01","tradedOn":"2026-01-02"}
   """.formatted(account,security))).andExpect(status().isCreated()).andExpect(jsonPath("$.data.trade.price").value("0.001234"));
  var price=new BigDecimal("0.002000");
  long stamp=LocalDate.of(2026,1,2).atStartOfDay(ZoneId.of("Asia/Hong_Kong")).toInstant().toEpochMilli();
  when(client.overseasCandles("HK","00700")).thenReturn(new OverseasCandleResponse(instrument,"00700","SINA","none",LocalDate.of(2026,1,2),Instant.now(),false,true,List.of(new CandleBar(stamp,price,price,price,price,1000,null))));
  overseas.refresh(security);
  mvc.perform(get("/api/accounts/"+cash).session(session)).andExpect(jsonPath("$.data.balance").value("498.76"));
  mvc.perform(get("/api/portfolio").param("asOf","2026-01-02").session(session)).andExpect(status().isOk())
   .andExpect(jsonPath("$.data.positions[0].currency").value("HKD")).andExpect(jsonPath("$.data.positions[0].cost").value("1.24"))
   .andExpect(jsonPath("$.data.positions[0].base.cost").value("1.12")).andExpect(jsonPath("$.data.totals.marketValue").value("1.80"));
 }
}
