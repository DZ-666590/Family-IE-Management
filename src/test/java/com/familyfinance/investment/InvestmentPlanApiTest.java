package com.familyfinance.investment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(properties="app.seed.enabled=true")
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class InvestmentPlanApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    MockHttpSession owner;
    long account, security, cash, user;
    String today=LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();

    @Test void creationAndDueGenerationOnlyRemindAndConfirmationPostsExactlyOnce() throws Exception {
        setup("1000.00");
        long before=count("ledger_journals");
        long plan=create(today,"MONTHLY");
        assertThat(count("investment_trades")).isZero();
        assertThat(count("ledger_journals")).isEqualTo(before);
        long occurrence=generate();
        generate();
        assertThat(count("investment_plan_occurrences")).isEqualTo(1);
        assertThat(count("investment_trades")).isZero();
        assertThat(count("ledger_journals")).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from notifications where reference_type='INVESTMENT_PLAN_OCCURRENCE'",Long.class)).isEqualTo(1);
        JsonNode first=confirm(occurrence,"first",200);
        assertThat(first.path("actualAmount").asText()).isEqualTo("101.00");
        assertThat(confirm(occurrence,"different-key",200).path("tradeId").asLong()).isEqualTo(first.path("tradeId").asLong());
        assertThat(count("investment_trades")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select balance_cents from ledger_accounts where account_code=?",Long.class,"CASH:"+cash)).isEqualTo(89900);
        mvc.perform(patch("/api/investment-trades/{id}",first.path("tradeId").asLong()).session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"price\":\"11.00\"}")).andExpect(status().isOk());
        JsonNode corrected=confirm(occurrence,"after-correction",200);
        assertThat(corrected.path("actualAmount").asText()).isEqualTo("101.00");
        assertThat(corrected.path("currentTrade").path("price").asText()).isEqualTo("11.00");
        mvc.perform(delete("/api/investment-trades/{id}",first.path("tradeId").asLong()).session(owner).with(csrf())).andExpect(status().isNoContent());
        assertThat(confirm(occurrence,"after-reversal",200).path("tradeReversed").asBoolean()).isTrue();
        assertThat(count("investment_trades")).isZero();
    }

    @Test void failedConfirmationRollsBackOccurrenceTradeAndLedger() throws Exception {
        setup("1.00");create(today,"WEEKLY");long id=generate();long before=count("ledger_journals");
        confirm(id,"no-money",409);
        assertThat(count("investment_trades")).isZero();
        assertThat(count("ledger_journals")).isEqualTo(before);
        assertThat(jdbc.queryForObject("select state from investment_plan_occurrences where id=?",String.class,id)).isEqualTo("PENDING");
    }

    @Test void monthEndAnchorAndEditPreserveExistingSnapshots() throws Exception {
        setup("1000.00");long plan=create("2026-01-31","MONTHLY");generate();
        var dates=jdbc.queryForList("select cast(due_on as varchar) from investment_plan_occurrences order by due_on",String.class);
        assertThat(dates).startsWith("2026-01-31","2026-02-28","2026-03-31");
        mvc.perform(patch("/api/investment-plans/{id}",plan).session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(planBody(today,"WEEKLY").replace("100.00","250.00"))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select min(amount) from investment_plan_occurrences",String.class)).startsWith("100");
        mvc.perform(post("/api/investment-plans/{id}/state",plan).session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"PAUSED\"}")).andExpect(status().isOk());
        long count=count("investment_plan_occurrences");generate();assertThat(count("investment_plan_occurrences")).isEqualTo(count);
        jdbc.update("update investment_plans set next_due_on='2020-01-01' where id=?",plan);
        mvc.perform(post("/api/investment-plans/{id}/state",plan).session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"ACTIVE\"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select next_due_on from investment_plans where id=?",LocalDate.class,plan)).isAfterOrEqualTo(LocalDate.parse(today));
    }

    @Test void snoozeReusesNotificationAndFundingRelinkRejectsConfirmation() throws Exception {
        setup("1000.00");create(today,"BIWEEKLY");long id=generate();
        mvc.perform(post("/api/investment-plans/occurrences/{id}/snooze",id).session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"option\":\"TWO_HOURS\"}")).andExpect(status().isOk());
        generate();
        assertThat(jdbc.queryForObject("select count(*) from notifications where reference_type='INVESTMENT_PLAN_OCCURRENCE'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select resolved_at is not null from notifications where reference_type='INVESTMENT_PLAN_OCCURRENCE'",Boolean.class)).isTrue();
        jdbc.update("update investment_plan_occurrences set remind_at='2020-01-01 00:00:00' where id=?",id);
        generate();generate();
        assertThat(jdbc.queryForObject("select count(*) from notifications where reference_type='INVESTMENT_PLAN_OCCURRENCE' and resolved_at is null and read_at is null",Long.class)).isEqualTo(1);
        jdbc.update("update investment_accounts set funding_account_id=null where id=?",account);
        confirm(id,"relinked",409);
        assertThat(count("investment_trades")).isZero();
        mvc.perform(post("/api/investment-plans/occurrences/{id}/skip",id).session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"本期不投\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("SKIPPED"));
        confirm(id,"skipped",409);
    }

    @Test void concurrentConfirmationAndDueGenerationRemainSinglePosting() throws Exception {
        setup("1000.00");long plan=create(today,"MONTHLY");long occurrence=generate();
        jdbc.update("update investment_plans set next_due_on=? where id=?",today,plan);
        ExecutorService pool=Executors.newFixedThreadPool(4);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<Long> a=pool.submit(()->{start.await();return confirm(occurrence,"race-a",200).path("tradeId").asLong();});
            Future<Long> b=pool.submit(()->{start.await();return confirm(occurrence,"race-b",200).path("tradeId").asLong();});
            Future<Long> c=pool.submit(()->{start.await();return generate();});
            Future<Long> d=pool.submit(()->{start.await();return generate();});
            start.countDown();assertThat(a.get(20,TimeUnit.SECONDS)).isEqualTo(b.get(20,TimeUnit.SECONDS));
            c.get(20,TimeUnit.SECONDS);d.get(20,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
        assertThat(count("investment_trades")).isEqualTo(1);
        assertThat(count("investment_plan_occurrences")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select balance_cents from ledger_accounts where account_code=?",Long.class,"CASH:"+cash)).isEqualTo(89900);
    }

    @Test void householdAndAdminPermissionsApplyToEveryMutation() throws Exception {
        setup("1000.00");long plan=create(today,"MONTHLY");long occurrence=generate();
        String invite=data(mvc.perform(post("/api/family/invites").session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"MEMBER\"}")).andExpect(status().isCreated()).andReturn()).path("token").asText();
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"plan-member@example.com\",\"displayName\":\"成员\",\"password\":\"family-pass-2026\",\"mode\":\"JOIN\",\"inviteToken\":\""+invite+"\"}")).andExpect(status().isCreated());
        MockHttpSession member=login("plan-member@example.com");
        mvc.perform(get("/api/investment-plans").session(member)).andExpect(status().isOk()).andExpect(jsonPath("$.data.pendingCount").value(1));
        mvc.perform(post("/api/investment-plans").session(member).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(planBody(today,"WEEKLY"))).andExpect(status().isForbidden());
        mvc.perform(patch("/api/investment-plans/{id}",plan).session(member).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(planBody(today,"WEEKLY"))).andExpect(status().isForbidden());
        for(String action:new String[]{"confirm","skip","snooze"})mvc.perform(post("/api/investment-plans/occurrences/{id}/"+action,occurrence).session(member).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/investment-plans/{id}/state",plan).session(member).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"ENDED\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/investment-plans/generate").session(member).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"plan-outsider@example.com\",\"displayName\":\"外部\",\"password\":\"family-pass-2026\",\"mode\":\"CREATE\",\"householdName\":\"外部家庭\"}")).andExpect(status().isCreated());
        MockHttpSession foreign=login("plan-outsider@example.com");
        mvc.perform(get("/api/investment-plans").session(foreign)).andExpect(status().isOk()).andExpect(jsonPath("$.data.plans").isEmpty());
        mvc.perform(patch("/api/investment-plans/{id}",plan).session(foreign).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(planBody(today,"WEEKLY"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/investment-plans/occurrences/{id}/confirm",occurrence).session(foreign).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
        assertThat(count("investment_trades")).isZero();
    }

    @Test void catchupIsBoundedAndCreateKeyCannotBeReusedForDifferentPayload() throws Exception {
        setup("1000.00");long plan=create("2020-01-01","WEEKLY");
        assertThat(count("investment_plan_occurrences")).isEqualTo(100);
        assertThat(create("2020-01-01","WEEKLY")).isEqualTo(plan);
        assertThat(count("investment_plans")).isEqualTo(1);
        mvc.perform(post("/api/investment-plans").session(owner).with(csrf()).header("Idempotency-Key","create-plan").contentType(MediaType.APPLICATION_JSON).content(planBody(today,"MONTHLY"))).andExpect(status().isConflict());
        generate();assertThat(count("investment_plan_occurrences")).isEqualTo(200);
        mvc.perform(get("/api/investment-plans?size=1&occurrencePage=1").session(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.pendingCount").value(200)).andExpect(jsonPath("$.data.hasMoreOccurrences").value(true)).andExpect(jsonPath("$.data.occurrences[0].dueOn").value("2020-01-08"));
        assertThat(count("investment_trades")).isZero();
    }

    @Test void endKeepsPendingActionableAndActualInputsAreValidated() throws Exception {
        setup("1000.00");long plan=create(today,"WEEKLY");long id=generate();
        mvc.perform(post("/api/investment-plans/{id}/state",plan).session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"ENDED\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/investment-plans/{id}/state",plan).session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"ACTIVE\"}")).andExpect(status().isConflict());
        for(String body:new String[]{"{\"quantity\":\"0\",\"price\":\"10\",\"tradedOn\":\""+today+"\"}","{\"quantity\":\"1\",\"price\":\"0\",\"tradedOn\":\""+today+"\"}","{\"quantity\":\"1\",\"price\":\"10\",\"tradedOn\":\"9999-01-01\"}"})
            mvc.perform(post("/api/investment-plans/occurrences/{id}/confirm",id).session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnprocessableEntity());
        assertThat(confirm(id,"after-ended",200).path("state").asText()).isEqualTo("CONFIRMED");
    }

    MockHttpSession login(String email)throws Exception{return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",email).param("password","family-pass-2026")).andExpect(status().isOk()).andReturn().getRequest().getSession(false);}

    @Test void futurePlanDoesNotNotifyOrPostAndSnoozeHasAnAuditActor() throws Exception {
        setup("1000.00");create(LocalDate.parse(today).plusMonths(1).toString(),"MONTHLY");
        assertThat(count("investment_plan_occurrences")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notifications where reference_type='INVESTMENT_PLAN_OCCURRENCE'",Long.class)).isZero();
        assertThat(count("investment_trades")).isZero();
        mvc.perform(post("/api/investment-plans").session(owner).with(csrf()).header("Idempotency-Key","today-plan").contentType(MediaType.APPLICATION_JSON).content(planBody(today,"MONTHLY"))).andExpect(status().isCreated());
        long id=generate();
        mvc.perform(post("/api/investment-plans/occurrences/{id}/snooze",id).session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"option\":\"TOMORROW\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.remindAt").value(LocalDate.parse(today).plusDays(1)+"T01:00:00Z"));
        assertThat(jdbc.queryForObject("select snoozed_by from investment_plan_occurrences where id=?",Long.class,id)).isEqualTo(user);
    }

    @Test void inactiveRecipientBacklogCannotStarveActiveMembersReminders() throws Exception {
        setup("1000.00");long ownerId=user;
        String token=data(mvc.perform(post("/api/family/invites").session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"MEMBER\"}")).andExpect(status().isCreated()).andReturn()).path("token").asText();
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"reminder-member@example.com\",\"displayName\":\"提醒成员\",\"password\":\"family-pass-2026\",\"mode\":\"JOIN\",\"inviteToken\":\""+token+"\"}")).andExpect(status().isCreated());
        user=jdbc.queryForObject("select id from app_users where email='reminder-member@example.com'",Long.class);
        long oldPlan=create("2020-01-01","WEEKLY");
        mvc.perform(post("/api/investment-plans/{id}/state",oldPlan).session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"PAUSED\"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from notifications where reference_type='INVESTMENT_PLAN_OCCURRENCE' and user_id=?",Long.class,user)).isEqualTo(100);
        jdbc.update("update household_memberships set status='SUSPENDED' where user_id=?",user);
        jdbc.update("update investment_plan_occurrences set notification_pending=true where plan_id=?",oldPlan);
        jdbc.update("update notifications set resolved_at=current_timestamp where user_id=?",user);
        user=ownerId;
        mvc.perform(post("/api/investment-plans").session(owner).with(csrf()).header("Idempotency-Key","active-owner-plan").contentType(MediaType.APPLICATION_JSON).content(planBody(today,"MONTHLY"))).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("select count(*) from notifications where reference_type='INVESTMENT_PLAN_OCCURRENCE' and user_id=?",Long.class,ownerId)).isEqualTo(1);
    }

    @Test void planReturnsRegisteredShenzhenAndBeijingIdentityWithoutCurrencyInference() throws Exception {
        setup("1000.00");
        for(String code:new String[]{"000001.SZ","920001.BJ"}) {
            String market=code.substring(code.length()-2);
            jdbc.update("insert into securities(market,ts_code,name,security_type,active,catalog_verified) values(?,?,?,'STOCK',true,true)",market,code,"目录股票");
            security=jdbc.queryForObject("select id from securities where ts_code=?",Long.class,code);
            mvc.perform(post("/api/investment-plans").session(owner).with(csrf()).header("Idempotency-Key",market+"-plan").contentType(MediaType.APPLICATION_JSON).content(planBody(today,"MONTHLY")))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.data.security.market").value(market)).andExpect(jsonPath("$.data.security.tsCode").value(code)).andExpect(jsonPath("$.data.security.exchange").value(market)).andExpect(jsonPath("$.data.security.timezone").value("Asia/Shanghai"));
        }
        mvc.perform(get("/api/investment-plans").session(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.plans[0].security.tsCode").value("000001.SZ")).andExpect(jsonPath("$.data.plans[1].security.tsCode").value("920001.BJ"));
    }

    void setup(String balance) throws Exception {
        owner=(MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username","demo").param("password","demo1234")).andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        user=jdbc.queryForObject("select user_id from household_memberships where household_id=1 and role='OWNER'",Long.class);
        cash=data(mvc.perform(post("/api/accounts").session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"plan cash\",\"type\":\"CASH\",\"currency\":\"CNY\",\"openingBalance\":\""+balance+"\",\"openingOn\":\"2020-01-01\"}")).andExpect(status().isCreated()).andReturn()).path("id").asLong();
        account=data(mvc.perform(post("/api/investment-accounts").session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"plan broker\",\"brokerName\":\"券商\",\"currency\":\"CNY\",\"fundingAccountId\":"+cash+"}")).andExpect(status().isCreated()).andReturn()).path("id").asLong();
        jdbc.update("insert into securities(market,ts_code,name,security_type,active,catalog_verified) values('SH','600000.SH','浦发银行','STOCK',true,true)");
        security=jdbc.queryForObject("select id from securities where ts_code='600000.SH'",Long.class);
    }
    long create(String date,String frequency) throws Exception {return data(mvc.perform(post("/api/investment-plans").session(owner).with(csrf()).header("Idempotency-Key","create-plan").contentType(MediaType.APPLICATION_JSON).content(planBody(date,frequency))).andExpect(status().isCreated()).andReturn()).path("id").asLong();}
    String planBody(String date,String frequency){return "{\"name\":\"月定投\",\"accountId\":"+account+",\"securityId\":"+security+",\"amount\":\"100.00\",\"frequency\":\""+frequency+"\",\"firstDueOn\":\""+date+"\",\"assignedUserId\":"+user+"}";}
    long generate() throws Exception {mvc.perform(post("/api/investment-plans/generate").session(owner).with(csrf())).andExpect(status().isOk());return data(mvc.perform(get("/api/investment-plans").session(owner)).andExpect(status().isOk()).andReturn()).path("occurrences").get(0).path("id").asLong();}
    JsonNode confirm(long id,String key,int status) throws Exception {MvcResult result=mvc.perform(post("/api/investment-plans/occurrences/{id}/confirm",id).session(owner).with(csrf()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":\"10\",\"price\":\"10.00\",\"fee\":\"1.00\",\"tradedOn\":\""+today+"\"}")).andExpect(status().is(status)).andReturn();return data(result);}
    JsonNode data(MvcResult result) throws Exception{return mapper.readTree(result.getResponse().getContentAsString()).path("data");}
    long count(String table){return jdbc.queryForObject("select count(*) from "+table,Long.class);}
}
