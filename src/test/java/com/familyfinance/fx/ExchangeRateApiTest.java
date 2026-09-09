package com.familyfinance.fx;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties="app.seed.enabled=true") @AutoConfigureMockMvc @ActiveProfiles("test")
class ExchangeRateApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExchangeRateStore store;
    @Autowired com.familyfinance.accounting.LedgerPostingService posting;
    @MockitoBean ExchangeRateProvider provider;
    MockHttpSession login() throws Exception {return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf())
            .param("username","demo").param("password","demo1234")).andExpect(status().isOk()).andReturn().getRequest().getSession(false);}
    @Test void authenticationAndCsrfRequired() throws Exception {
        mvc.perform(get("/api/exchange-rates")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/exchange-rates").with(user("outsider"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/exchange-rates/refresh").session(login())).andExpect(status().isForbidden());
        mvc.perform(get("/api/exchange-rates/audit")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/exchange-rates/audit").with(user("outsider"))).andExpect(status().isForbidden());
    }
    @Test void auditReportsOnlyCurrentHouseholdsLegacyReferencesAndDoesNotRewriteThem() throws Exception {
        var session=login();var day=LocalDate.of(2020,8,3);
        store.save(new ExchangeRateBatch("ECB",day,Map.of("USD",new BigDecimal("7"),"HKD",new BigDecimal("0.9"))),java.time.Instant.parse("2026-09-08T00:00:00Z"));
        long actor=jdbc.queryForObject("select id from app_users where username='demo'",Long.class);
        long own=jdbc.queryForObject("select household_id from app_users where id=?",Long.class,actor);
        String name=java.util.UUID.randomUUID().toString();jdbc.update("insert into households(name,created_at) values(?,current_timestamp)",name);
        long other=jdbc.queryForObject("select id from households where name=?",Long.class,name);
        jdbc.update("insert into app_users(household_id,username,password_hash,email,display_name,created_at) values(?,?,?,?,?,current_timestamp)",other,name,"unused",name+"@test.invalid","test");
        long otherActor=jdbc.queryForObject("select id from app_users where username=?",Long.class,name);
        long legacy=auditJournal(own,actor,1,day);auditJournal(own,actor,2,day);
        long foreignLegacy=auditJournal(other,otherActor,1,day);
        jdbc.update("update fx_journal_rates set reference_status='LEGACY_UNVERIFIED' where journal_id in (?,?)",legacy,foreignLegacy);
        var before=jdbc.queryForList("select * from fx_journal_rates where journal_id in (?,?) order by journal_id",legacy,foreignLegacy);
        mvc.perform(get("/api/exchange-rates/audit").param("householdId",Long.toString(other)).session(session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.unverifiedReferences").value(1));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForList("select * from fx_journal_rates where journal_id in (?,?) order by journal_id",legacy,foreignLegacy)).isEqualTo(before);
    }
    long auditJournal(long household,long actor,long source,LocalDate day){
        var entries=java.util.List.of(
            new com.familyfinance.accounting.LedgerEntryInput("EQUITY:OPENING:USD",com.familyfinance.accounting.LedgerAccountKind.EQUITY,10000,0,null,null,"USD"),
            new com.familyfinance.accounting.LedgerEntryInput("INCOME:INVESTMENT_GAIN:USD",com.familyfinance.accounting.LedgerAccountKind.INCOME,0,10000,null,null,"USD"));
        return posting.post(new com.familyfinance.accounting.LedgerPostingCommand(household,"FX_AUDIT",source,java.util.UUID.randomUUID().toString(),day,actor,entries)).journalId();
    }
    @Test void memberCanReadButCannotRefreshAndOwnerCanRefresh() throws Exception {
        var session=login();
        long id=jdbc.queryForObject("select id from app_users where username='demo'",Long.class);
        String original=jdbc.queryForObject("select role from household_memberships where user_id=?",String.class,id);
        try {
            jdbc.update("update household_memberships set role='MEMBER' where user_id=?",id);
            mvc.perform(get("/api/exchange-rates").session(session)).andExpect(status().isOk());
            mvc.perform(post("/api/exchange-rates/refresh").with(csrf()).session(session)).andExpect(status().isForbidden());
        } finally {jdbc.update("update household_memberships set role=? where user_id=?",original,id);}
        when(provider.fetch(any())).thenReturn(new ExchangeRateBatch("ECB",LocalDate.of(2026,9,7),Map.of("USD",new BigDecimal("7"),"HKD",new BigDecimal("0.9"))));
        mvc.perform(post("/api/exchange-rates/refresh").param("asOf","2026-09-08").with(csrf()).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.refreshState").value("SUCCESS"))
                .andExpect(jsonPath("$.data.rows[2].cnyPerUnit").value("7.000000000000"));
        mvc.perform(get("/api/exchange-rates/history").param("from","2026-09-01").param("to","2026-09-08").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows[0].source").value("ECB"))
                .andExpect(jsonPath("$.data.state").exists());
    }
}
