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
  var reverse=posting.reverse(h,"FX_TEST",1,"undo",actor);
  assertThat(rates.historical(reverse.journalId(),"USD",new BigDecimal("100"))).isEqualByComparingTo("700.00");
  assertThat(rates.convert("USD",new BigDecimal("100"),day)).isEqualByComparingTo("800.00");
 }
}
