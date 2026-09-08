package com.familyfinance.ledger;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties={"app.seed.enabled=true","app.multicurrency.enabled=true"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class MultiCurrencyAccountApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    MockHttpSession login() throws Exception{return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf())
            .param("username","demo").param("password","demo1234")).andExpect(status().isOk()).andReturn().getRequest().getSession(false);}
    long account(MockHttpSession session,String currency,String balance) throws Exception {
        var result=mvc.perform(post("/api/accounts").session(session).with(csrf()).contentType("application/json").content("""
            {"name":"%s","type":"BANK","currency":"%s","openingBalance":"%s","openingOn":"2026-01-01"}
            """.formatted(UUID.randomUUID(),currency,balance))).andExpect(status().isCreated()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }
    @Test void foreignOpeningAndSameCurrencyTransferKeepNativeBalances() throws Exception {
        var session=login();long a=account(session,"USD","100.00"),b=account(session,"USD","0.00");
        mvc.perform(post("/api/transfers").session(session).with(csrf()).contentType("application/json").content("""
            {"fromAccountId":%d,"toAccountId":%d,"amount":"25.50","occurredOn":"2026-01-02","idempotencyKey":"%s"}
            """.formatted(a,b,UUID.randomUUID()))).andExpect(status().isCreated());
        mvc.perform(get("/api/accounts/"+a).session(session)).andExpect(jsonPath("$.data.balance").value("74.50")).andExpect(jsonPath("$.data.currency").value("USD"));
        mvc.perform(get("/api/accounts/"+b).session(session)).andExpect(jsonPath("$.data.balance").value("25.50"));
    }
    @Test void currencyCannotBeChangedAndMixedCurrencyTransferIsRejected() throws Exception {
        var session=login();long usd=account(session,"USD","10.00"),hkd=account(session,"HKD","0.00");
        mvc.perform(patch("/api/accounts/"+usd).session(session).with(csrf()).contentType("application/json").content("{\"currency\":\"HKD\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/transfers").session(session).with(csrf()).contentType("application/json").content("""
            {"fromAccountId":%d,"toAccountId":%d,"amount":"1.00","occurredOn":"2026-01-02"}
            """.formatted(usd,hkd))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/accounts/"+usd).session(session)).andExpect(jsonPath("$.data.balance").value("10.00"));
    }
    @Test void walletBrandDoesNotPermitUnsupportedForeignBalance() throws Exception {
        mvc.perform(post("/api/accounts").session(login()).with(csrf()).contentType("application/json").content("""
            {"name":"foreign-alipay","type":"WALLET","walletProvider":"ALIPAY","currency":"USD","openingBalance":"0.00","openingOn":"2026-01-01"}
            """)).andExpect(status().isBadRequest());
    }
    @Test void investmentAccountCannotFundADifferentCurrencySecurity() throws Exception {
        var s=login();long usd=account(s,"USD","100.00"),cny=account(s,"CNY","0.00");
        String name=UUID.randomUUID().toString();
        String body="""
            {"name":"%s","brokerName":"test","currency":"USD","fundingAccountId":%d}
            """.formatted(name,cny);
        mvc.perform(post("/api/investment-accounts").session(s).with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        var result=mvc.perform(post("/api/investment-accounts").session(s).with(csrf()).contentType("application/json").content(body.replace(":%d}".formatted(cny),":%d}".formatted(usd))))
                .andExpect(status().isCreated()).andReturn();
        long investment=mapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
        jdbc.update("insert into securities(market,ts_code,name,security_type,active,catalog_verified) values('SH','997001.SH','Currency test','STOCK',true,true)");
        long security=jdbc.queryForObject("select id from securities where ts_code='997001.SH'",Long.class);
        mvc.perform(post("/api/investment-trades").session(s).with(csrf()).contentType("application/json").content("""
            {"accountId":%d,"securityId":%d,"type":"BUY","quantity":"1","price":"1.00","fee":"0","tradedOn":"2026-01-02"}
            """.formatted(investment,security))).andExpect(status().isUnprocessableEntity());
    }
}
