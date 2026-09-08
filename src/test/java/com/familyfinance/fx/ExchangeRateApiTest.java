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
    @MockitoBean ExchangeRateProvider provider;
    MockHttpSession login() throws Exception {return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf())
            .param("username","demo").param("password","demo1234")).andExpect(status().isOk()).andReturn().getRequest().getSession(false);}
    @Test void authenticationAndCsrfRequired() throws Exception {
        mvc.perform(get("/api/exchange-rates")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/exchange-rates").with(user("outsider"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/exchange-rates/refresh").session(login())).andExpect(status().isForbidden());
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
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].source").value("ECB"));
    }
}
