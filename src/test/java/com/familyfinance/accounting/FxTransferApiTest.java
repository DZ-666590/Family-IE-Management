package com.familyfinance.accounting;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties={"app.seed.enabled=true","app.multicurrency.enabled=true"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class FxTransferApiTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper mapper;
    MockHttpSession login() throws Exception{return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username","demo").param("password","demo1234"))
            .andExpect(status().isOk()).andReturn().getRequest().getSession(false);}
    long account(MockHttpSession s,String currency,String amount) throws Exception{
        var result=mvc.perform(post("/api/accounts").session(s).with(csrf()).contentType("application/json").content("""
            {"name":"%s","type":"BANK","currency":"%s","openingBalance":"%s","openingOn":"2026-01-01"}
            """.formatted(UUID.randomUUID(),currency,amount))).andExpect(status().isCreated()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }
    String payload(long from,long to,String key){return """
        {"fromAccountId":%d,"toAccountId":%d,"fromAmount":"7000.00","toAmount":"1000.00","fee":"10.00","occurredOn":"2026-01-02","idempotencyKey":"%s"}
        """.formatted(from,to,key);}
    @Test void deductsFeeAndPrincipalOnceAndReversesBothCurrenciesAtomically() throws Exception{
        var s=login();long cny=account(s,"CNY","7010.00"),usd=account(s,"USD","0.00");String body=payload(cny,usd,UUID.randomUUID().toString());
        var result=mvc.perform(post("/api/fx-transfers").session(s).with(csrf()).contentType("application/json").content(body)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.fromCurrency").value("CNY")).andExpect(jsonPath("$.data.toCurrency").value("USD")).andReturn();
        long id=mapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
        mvc.perform(post("/api/fx-transfers").session(s).with(csrf()).contentType("application/json").content(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(id));
        mvc.perform(get("/api/accounts/"+cny).session(s)).andExpect(jsonPath("$.data.balance").value("0.00"));
        mvc.perform(get("/api/accounts/"+usd).session(s)).andExpect(jsonPath("$.data.balance").value("1000.00"));
        assertThat(jdbc.queryForObject("select count(*) from ledger_journals where source_type='FX_TRANSFER' and source_id=?",Integer.class,id)).isEqualTo(1);
        mvc.perform(delete("/api/fx-transfers/"+id).param("expectedRevision","1").header("Idempotency-Key",UUID.randomUUID()).session(s).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reversed").value(true));
        mvc.perform(get("/api/accounts/"+cny).session(s)).andExpect(jsonPath("$.data.balance").value("7010.00"));
        mvc.perform(get("/api/accounts/"+usd).session(s)).andExpect(jsonPath("$.data.balance").value("0.00"));
    }
    @Test void shortageLeavesNoPartialTransfer() throws Exception{
        var s=login();long cny=account(s,"CNY","7005.00"),usd=account(s,"USD","0.00");
        mvc.perform(post("/api/fx-transfers").session(s).with(csrf()).contentType("application/json").content(payload(cny,usd,UUID.randomUUID().toString())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INSUFFICIENT_FUNDS"));
        mvc.perform(get("/api/accounts/"+cny).session(s)).andExpect(jsonPath("$.data.balance").value("7005.00"));
        mvc.perform(get("/api/accounts/"+usd).session(s)).andExpect(jsonPath("$.data.balance").value("0.00"));
        assertThat(jdbc.queryForObject("select count(*) from fx_transfers where from_account_id=?",Integer.class,cny)).isZero();
    }
    @Test void correctionPreservesHistoryAndRejectsStaleRevision() throws Exception {
        var s=login();long cny=account(s,"CNY","14020.00"),usd=account(s,"USD","0.00");
        var result=mvc.perform(post("/api/fx-transfers").session(s).with(csrf()).contentType("application/json").content(payload(cny,usd,UUID.randomUUID().toString()))).andExpect(status().isCreated()).andReturn();
        long id=mapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
        String changeKey=UUID.randomUUID().toString();
        String revised=payload(cny,usd,changeKey).replace("7000.00","3500.00").replace("1000.00","500.00").replace("10.00","5.00").replace("\"fromAccountId\"","\"expectedRevision\":1,\"fromAccountId\"");
        mvc.perform(patch("/api/fx-transfers/"+id).session(s).with(csrf()).contentType("application/json").content(revised))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(2));
        mvc.perform(get("/api/accounts/"+cny).session(s)).andExpect(jsonPath("$.data.balance").value("10515.00"));
        mvc.perform(get("/api/accounts/"+usd).session(s)).andExpect(jsonPath("$.data.balance").value("500.00"));
        assertThat(jdbc.queryForObject("select count(*) from fx_transfer_revisions where transfer_id=?",Integer.class,id)).isEqualTo(2);
        mvc.perform(patch("/api/fx-transfers/"+id).session(s).with(csrf()).contentType("application/json").content(revised.replace(changeKey,UUID.randomUUID().toString())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("FX_REVISION_CHANGED"));
    }
    @Test void cannotReverseAnExchangeAfterReceivedFundsAreSpent() throws Exception {
        var s=login();long cny=account(s,"CNY","7010.00"),usd=account(s,"USD","0.00"),other=account(s,"USD","0.00");
        var result=mvc.perform(post("/api/fx-transfers").session(s).with(csrf()).contentType("application/json").content(payload(cny,usd,UUID.randomUUID().toString()))).andExpect(status().isCreated()).andReturn();
        long id=mapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
        mvc.perform(post("/api/transfers").session(s).with(csrf()).contentType("application/json").content("""
            {"fromAccountId":%d,"toAccountId":%d,"amount":"100.00","occurredOn":"2026-01-03"}
            """.formatted(usd,other))).andExpect(status().isCreated());
        mvc.perform(delete("/api/fx-transfers/"+id).param("expectedRevision","1").header("Idempotency-Key",UUID.randomUUID()).session(s).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INSUFFICIENT_FUNDS"));
        mvc.perform(get("/api/accounts/"+cny).session(s)).andExpect(jsonPath("$.data.balance").value("0.00"));
        mvc.perform(get("/api/accounts/"+usd).session(s)).andExpect(jsonPath("$.data.balance").value("900.00"));
    }
    @Test void concurrentExchangesCannotSpendTheSameCashTwice() throws Exception {
        var s=login();long cny=account(s,"CNY","7010.00"),usd=account(s,"USD","0.00");
        var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Integer> command=()->{start.await();return mvc.perform(post("/api/fx-transfers").session(s).with(csrf()).contentType("application/json").content(payload(cny,usd,UUID.randomUUID().toString()))).andReturn().getResponse().getStatus();};
            var a=pool.submit(command);var b=pool.submit(command);start.countDown();
            assertThat(java.util.List.of(a.get(15,java.util.concurrent.TimeUnit.SECONDS),b.get(15,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
            mvc.perform(get("/api/accounts/"+cny).session(s)).andExpect(jsonPath("$.data.balance").value("0.00"));
            mvc.perform(get("/api/accounts/"+usd).session(s)).andExpect(jsonPath("$.data.balance").value("1000.00"));
        } finally {pool.shutdownNow();}
    }
    @Test void cannotUseAnAccountOwnedByAnotherHousehold() throws Exception {
        var s=login();long cny=account(s,"CNY","7010.00");String name=UUID.randomUUID().toString();
        jdbc.update("insert into households(name,created_at) values(?,current_timestamp)",name);
        long other=jdbc.queryForObject("select id from households where name=?",Long.class,name);
        jdbc.update("insert into financial_accounts(household_id,name,type,currency) values(?,'foreign','BANK','USD')",other);
        long target=jdbc.queryForObject("select id from financial_accounts where household_id=?",Long.class,other);
        mvc.perform(post("/api/fx-transfers").session(s).with(csrf()).contentType("application/json").content(payload(cny,target,UUID.randomUUID().toString())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/accounts/"+cny).session(s)).andExpect(jsonPath("$.data.balance").value("7010.00"));
    }
}
