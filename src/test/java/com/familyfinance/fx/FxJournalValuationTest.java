package com.familyfinance.fx;

import static org.assertj.core.api.Assertions.*;
import static com.familyfinance.accounting.LedgerAccountKind.*;
import com.familyfinance.accounting.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties="app.seed.enabled=false") @ActiveProfiles("test")
class FxJournalValuationTest {
 @Autowired FxJournalRates rates;@Autowired ExchangeRateStore store;@Autowired LedgerPostingService posting;@Autowired JdbcTemplate jdbc;
 @Test void currentEstimatesCanUseLastPublicationWithoutGrantingJournalAuthority(){
  var friday=LocalDate.of(2021,9,3);var sunday=LocalDate.of(2021,9,5);var fetched=Instant.parse("2021-09-08T00:00:00Z");
  store.save(new ExchangeRateBatch("ECB",friday,Map.of("USD",new BigDecimal("7"),"HKD",new BigDecimal("0.9"))),fetched);
  store.save(new ExchangeRateBatch("ECB",LocalDate.of(2021,9,7),Map.of("USD",new BigDecimal("8"),"HKD",BigDecimal.ONE)),fetched);
  var estimate=rates.valuationReference("USD",sunday);
  assertThat(estimate).isNotNull();assertThat(estimate.effectiveOn()).isEqualTo(friday);
  assertThat(estimate.source()).isEqualTo("ECB");assertThat(estimate.state()).isEqualTo("ESTIMATE");
  assertThat(rates.valuationConvert("USD",new BigDecimal("100"),sunday)).isEqualByComparingTo("700.00");
  assertThat(rates.reference("USD",sunday)).isNull();
  assertThat(rates.valuationReference("USD",LocalDate.of(2021,9,15)).state()).isEqualTo("STALE");
  assertThat(rates.valuationReference("CNY",sunday).state()).isEqualTo("IDENTITY");
 }
 @Test void arbitraryEarlierCachedWeekdayIsNotAnAuthoritativeJournalReference(){
  store.save(new ExchangeRateBatch("ECB",LocalDate.of(2026,7,1),Map.of("USD",new BigDecimal("7"),"HKD",new BigDecimal("0.9"))),Instant.parse("2026-09-08T00:00:00Z"));
  store.save(new ExchangeRateBatch("ECB",LocalDate.of(2026,9,8),Map.of("USD",new BigDecimal("8"),"HKD",BigDecimal.ONE)),Instant.parse("2026-09-08T00:00:00Z"));
  assertThat(rates.reference("USD",LocalDate.of(2026,8,3))).isNull();
 }
 @Test void journalAndItsReversalKeepTheirOriginalReferenceRate(){
  LocalDate day=LocalDate.of(2026,1,1);Instant fetched=Instant.parse("2026-01-02T00:00:00Z");
  store.save(new ExchangeRateBatch("ECB",day,Map.of("USD",new BigDecimal("7"),"HKD",new BigDecimal("0.9"))),fetched);
  String name=UUID.randomUUID().toString();jdbc.update("insert into households(name,created_at) values(?,current_timestamp)",name);
  long h=jdbc.queryForObject("select id from households where name=?",Long.class,name);
  jdbc.update("insert into app_users(household_id,username,password_hash,email,display_name,created_at) values(?,?,?,?,?,current_timestamp)",h,name,"unused",name+"@test.invalid","test");
  long actor=jdbc.queryForObject("select id from app_users where username=?",Long.class,name);
  var receipt=posting.post(new LedgerPostingCommand(h,"FX_TEST",1,"new",day,actor,List.of(
   new LedgerEntryInput("EQUITY:OPENING:USD",EQUITY,10000,0,null,null,"USD"),new LedgerEntryInput("INCOME:INVESTMENT_GAIN:USD",INCOME,0,10000,null,null,"USD"))));
  store.save(new ExchangeRateBatch("ECB",day,Map.of("USD",new BigDecimal("8"),"HKD",BigDecimal.ONE)),fetched.plusSeconds(1));
  assertThat(rates.historical(receipt.journalId(),"USD",new BigDecimal("100"))).isEqualByComparingTo("700.00");
  jdbc.update("update fx_journal_rates set reference_status='LEGACY_UNVERIFIED' where journal_id=?",receipt.journalId());
  var reverse=posting.reverse(h,"FX_TEST",1,"undo",actor);
  assertThat(rates.historical(reverse.journalId(),"USD",new BigDecimal("100"))).isEqualByComparingTo("700.00");
  assertThat(jdbc.queryForObject("select reference_status from fx_journal_rates where journal_id=?",String.class,reverse.journalId())).isEqualTo("LEGACY_UNVERIFIED");
  assertThat(rates.convert("USD",new BigDecimal("100"),day)).isEqualByComparingTo("800.00");
 }
 @Test void pendingHistoricalJournalFetchDoesNotHoldLedgerTransactionOrChangeNativeAmounts() throws Exception {
  String name=UUID.randomUUID().toString();jdbc.update("insert into households(name,created_at) values(?,current_timestamp)",name);
  long h=jdbc.queryForObject("select id from households where name=?",Long.class,name);
  jdbc.update("insert into app_users(household_id,username,password_hash,email,display_name,created_at) values(?,?,?,?,?,current_timestamp)",h,name,"unused",name+"@test.invalid","test");
  long actor=jdbc.queryForObject("select id from app_users where username=?",Long.class,name);
  var saturday=LocalDate.of(2025,8,2);var friday=saturday.minusDays(1);
  var receipt=posting.post(new LedgerPostingCommand(h,"FX_PENDING",1,"new",saturday,actor,List.of(
   new LedgerEntryInput("EQUITY:OPENING:USD",EQUITY,10000,0,null,null,"USD"),new LedgerEntryInput("INCOME:INVESTMENT_GAIN:USD",INCOME,0,10000,null,null,"USD"))));
  assertThat(rates.historical(receipt.journalId(),"USD",new BigDecimal("100"))).isNull();
  var nativeBefore=jdbc.queryForList("select debit_amount,credit_amount,currency from ledger_entries where journal_id=? order by id",receipt.journalId());
  var balancesBefore=jdbc.queryForList("select account_code,balance_amount from ledger_accounts where household_id=? order by account_code",h);
  ExchangeRateProvider provider=day->{
   assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
   assertThat(day).isEqualTo(saturday);
   return new ExchangeRateBatch("ECB",friday,Map.of("USD",new BigDecimal("7"),"HKD",new BigDecimal("0.9")));
  };
  try(var history=new ExchangeRateHistory(provider,store,rates,Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"),ZoneOffset.UTC))){
   history.acquirePending();
   long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
   while(rates.historical(receipt.journalId(),"USD",new BigDecimal("100"))==null&&System.nanoTime()<end)Thread.sleep(10);
   assertThat(rates.historical(receipt.journalId(),"USD",new BigDecimal("100"))).isEqualByComparingTo("700.00");
  }
  assertThat(jdbc.queryForList("select debit_amount,credit_amount,currency from ledger_entries where journal_id=? order by id",receipt.journalId())).isEqualTo(nativeBefore);
  assertThat(jdbc.queryForList("select account_code,balance_amount from ledger_accounts where household_id=? order by account_code",h)).isEqualTo(balancesBefore);
 }
}
