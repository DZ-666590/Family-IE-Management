package com.familyfinance.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.familyfinance.accounting.LedgerReadService;
import com.familyfinance.accounting.LedgerReportingService;
import com.familyfinance.accounting.AccountingRequests;
import com.familyfinance.shared.ResourceConflictException;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.*;

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class LoanPurchasedAssetApiTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    @Autowired LedgerReadService ledger; @Autowired LedgerReportingService reporting;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @MockitoSpyBean AccountingRequests requests;
    MockHttpSession session; long household, account, category;

    @BeforeEach void setup() throws Exception {
        String email=UUID.randomUUID()+"@purchase.test";
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"displayName\":\"Buyer\",\"password\":\"purchase-test-password\",\"mode\":\"CREATE\",\"householdName\":\"Purchase test\"}")).andExpect(status().isCreated());
        session=(MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",email).param("password","purchase-test-password")).andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        household=jdbc.queryForObject("select household_id from app_users where email=?",Long.class,email);
        account=jdbc.queryForObject("select id from financial_accounts where household_id=?",Long.class,household);
        category=jdbc.queryForObject("select min(id) from categories where household_id=? and kind='EXPENSE'",Long.class,household);
        mvc.perform(patch("/api/accounts/"+account).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"openingBalance\":\"0.00\",\"openingOn\":\"2026-01-01\"}")).andExpect(status().isOk());
    }

    @ParameterizedTest @CsvSource({"MORTGAGE,PROPERTY,房产1,true", "CAR,VEHICLE,车辆1,true", "OTHER,OTHER,其他资产1,false"})
    void pairedPurchaseCreatesOneJournalAndNoCashOrFakeMetadata(String loanType,String assetType,String name,boolean pending) throws Exception {
        long before=count("ledger_journals");
        long borrower=jdbc.queryForObject("select min(id) from family_members where household_id=?",Long.class,household);
        String request=body(loanType).replace("\"createPurchasedAsset\":true","\"createPurchasedAsset\":true,\"memberId\":"+borrower);
        JsonNode loan=data(create(request,"purchase").andExpect(status().isCreated()).andReturn());
        long id=loan.path("id").asLong(), asset=loan.path("purchasedAssetId").asLong();
        assertThat(asset).isPositive(); assertThat(loan.path("linkedAssetId").asLong()).isEqualTo(asset);
        mvc.perform(get("/api/assets/"+asset).session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value(name)).andExpect(jsonPath("$.data.type").value(assetType))
            .andExpect(jsonPath("$.data.purchaseValue").value("1000.00")).andExpect(jsonPath("$.data.currentValue").value("1000.00"))
            .andExpect(jsonPath("$.data.initialValue").value("1000.00")).andExpect(jsonPath("$.data.acquiredOn").value("2026-01-01"))
            .andExpect(jsonPath("$.data.ownerMemberId").isEmpty())
            .andExpect(jsonPath("$.data.detailsPending").value(pending)).andExpect(jsonPath("$.data.property").isEmpty()).andExpect(jsonPath("$.data.vehicle").isEmpty())
            .andExpect(jsonPath("$.data.acquisitionSourceType").value("LOAN_FINANCED_PURCHASE")).andExpect(jsonPath("$.data.acquisitionSourceId").value(id));
        assertThat(ledger.balance(household,"CASH:"+account)).isZero();
        assertThat(ledger.balance(household,"ASSET:"+asset)).isEqualTo(100000);
        assertThat(ledger.balance(household,"LOAN:"+id)).isEqualTo(100000);
        assertThat(count("ledger_journals")).isEqualTo(before+1);
        assertThat(jdbc.queryForObject("select count(*) from ledger_sources where household_id=? and source_type='ASSET_ACQUISITION'",Long.class,household)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from asset_valuations where household_id=? and asset_id=?",Long.class,household,asset)).isEqualTo(1);
        var cashFlow=reporting.cashFlow(household,LocalDate.of(2026,1,1),LocalDate.of(2026,2,1));
        assertThat(cashFlow.borrowed()).isEqualTo(100000);assertThat(cashFlow.cashIn()).isZero();assertThat(cashFlow.cashOut()).isZero();
        create(request,"purchase").andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(id)).andExpect(jsonPath("$.data.purchasedAssetId").value(asset));
        create(request.replace("购买贷款","其他名称"),"purchase").andExpect(status().isConflict());
        assertThat(count("assets")).isEqualTo(1); assertThat(count("loans")).isEqualTo(1); assertThat(count("ledger_journals")).isEqualTo(before+1);
        assertThat(ledger.balances(household)).isEqualTo(ledger.reconstructedBalances(household));
    }

    @Test void validMetadataCompletionDoesNotRebookAndManualPropertyStillRequiresDetails() throws Exception {
        var loan=data(create(body("MORTGAGE"),"metadata").andExpect(status().isCreated()).andReturn());long asset=loan.path("purchasedAssetId").asLong();long journals=count("ledger_journals");
        patchAsset(asset,"{\"property\":{\"address\":\"真实地址\",\"areaSqm\":\"0\",\"usageType\":\"自住\"}}").andExpect(status().isUnprocessableEntity());
        patchAsset(asset,"{\"name\":\"我的家\",\"property\":{\"address\":\"真实地址\",\"areaSqm\":\"80.50\",\"usageType\":\"自住\"}}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.detailsPending").value(false)).andExpect(jsonPath("$.data.property.areaSqm").value(80.50));
        assertThat(count("ledger_journals")).isEqualTo(journals);assertThat(ledger.balance(household,"ASSET:"+asset)).isEqualTo(100000);
        mvc.perform(post("/api/assets").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"手工房产\",\"type\":\"PROPERTY\",\"currentValue\":\"1000.00\",\"accountingMode\":\"OPENING\",\"accountingOn\":\"2026-01-01\"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/assets").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"伪造购买物\",\"type\":\"OTHER\",\"currentValue\":\"1000.00\",\"accountingMode\":\"FINANCED_PURCHASE\",\"accountingOn\":\"2026-01-01\"}"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.fields.accountingMode").exists());
        assertThat(count("assets")).isEqualTo(1);assertThat(count("ledger_journals")).isEqualTo(journals);
    }

    @Test void immutablePairRejectsFinancialAndLinkChangesButAllowsRateTermWithoutReposting() throws Exception {
        var loan=data(create(body("OTHER"),"immutable").andExpect(status().isCreated()).andReturn());long id=loan.path("id").asLong(),asset=loan.path("purchasedAssetId").asLong();long journals=count("ledger_journals");
        for(String patch:new String[]{"{\"principal\":\"900.00\"}","{\"accountingOn\":\"2026-01-02\"}","{\"startOn\":\"2026-01-02\"}","{\"disbursementAccountId\":"+account+"}","{\"linkedAssetId\":999999}"})
            patchLoan(id,patch).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOAN_PURCHASE_IMMUTABLE"));
        patchLoan(id,"{\"annualRate\":0.05,\"termMonths\":3}").andExpect(status().isOk()).andExpect(jsonPath("$.data.termMonths").value(3)).andExpect(jsonPath("$.data.purchasedAssetId").value(asset));
        assertThat(count("ledger_journals")).isEqualTo(journals);assertThat(ledger.balance(household,"ASSET:"+asset)).isEqualTo(100000);
    }

    @Test void validationAndReplayKeyConflictRejectBeforePairedInserts() throws Exception {
        long journals=count("ledger_journals");
        for(String b:new String[]{body("CAR").replace("true","false"),body("CAR").replace("FINANCED_PURCHASE","OPENING"),body("CAR").replace("\"createPurchasedAsset\":true","\"createPurchasedAsset\":true,\"linkedAssetId\":999999"),body("CAR").replace("\"createPurchasedAsset\":true","\"createPurchasedAsset\":true,\"disbursementAccountId\":"+account),body("CAR").replace("2026-01-01","9999-01-01")})
            create(b,UUID.randomUUID().toString()).andExpect(status().isBadRequest());
        // AccountingRequests.replay rejects this pre-existing journal key before either paired insert.
        jdbc.update("insert into ledger_journals(household_id,source_type,source_id,revision,request_key,request_digest,operation,effective_on,actor_id,recorded_at) values (?,'TEST',1,1,'posting-conflict','different-digest','POST','2026-01-01',(select min(id) from app_users where household_id=?),CURRENT_TIMESTAMP)",household,household);
        create(body("CAR"),"posting-conflict").andExpect(status().isConflict());
        assertThat(count("loans")).isZero();assertThat(count("assets")).isZero();assertThat(count("asset_valuations")).isZero();assertThat(count("ledger_journals")).isEqualTo(journals+1);
    }

    @Test void failureAfterRealOriginationAndReceiptRollsBackTheInsertedPairAndEveryPosting() throws Exception {
        var before=new java.util.LinkedHashMap<String,java.util.List<java.util.Map<String,Object>>>();
        for(String table:java.util.List.of("loans","assets","loan_installments","asset_valuations","ledger_journals","ledger_entries","ledger_accounts","ledger_sources","accounting_commands","financial_transactions"))
            before.put(table,jdbc.queryForList("select * from "+table+" where household_id=? order by 1,2,3",household));
        long[] observedIds=new long[3];
        doAnswer(invocation->{
            invocation.callRealMethod(); // Keep the genuine receipt INSERT as well as the preceding origination.
            long loan=invocation.getArgument(3);
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from loans where household_id=? and id=?",Long.class,household,loan)).isEqualTo(1);
            long asset=jdbc.queryForObject("select purchased_asset_id from loans where household_id=? and id=?",Long.class,household,loan);
            assertThat(jdbc.queryForObject("select count(*) from assets where household_id=? and id=? and purchase_loan_id=?",Long.class,household,asset,loan)).isEqualTo(1);
            long journal=jdbc.queryForObject("select id from ledger_journals where household_id=? and source_type='LOAN_FINANCED_PURCHASE' and source_id=?",Long.class,household,loan);
            assertThat(jdbc.queryForObject("select count(*) from ledger_entries where household_id=? and journal_id=?",Long.class,household,journal)).isEqualTo(2);
            assertThat(jdbc.queryForObject("select count(*) from asset_valuations where household_id=? and asset_id=?",Long.class,household,asset)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from accounting_commands where household_id=? and request_key='after-real-posting' and source_id=?",Long.class,household,loan)).isEqualTo(1);
            assertThat(ledger.balance(household,"ASSET:"+asset)).isEqualTo(100000);
            assertThat(ledger.balance(household,"LOAN:"+loan)).isEqualTo(100000);
            observedIds[0]=loan;observedIds[1]=asset;observedIds[2]=journal;
            throw new ResourceConflictException("TEST_POST_ORIGINATION_FAILURE","Test-only failure after real posting");
        }).when(requests).record(eq(household),eq("after-real-posting"),anyString(),anyLong());
        create(body("CAR"),"after-real-posting").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TEST_POST_ORIGINATION_FAILURE"));
        for(long id:observedIds)assertThat(id).isPositive();
        for(var entry:before.entrySet())assertThat(jdbc.queryForList("select * from "+entry.getKey()+" where household_id=? order by 1,2,3",household)).as(entry.getKey()).isEqualTo(entry.getValue());
        assertThat(ledger.balance(household,"CASH:"+account)).isZero();
        assertThat(ledger.balances(household)).isEqualTo(ledger.reconstructedBalances(household));
    }

    @Test void generatedNameSkipsExistingHouseholdName() throws Exception {
        var first=data(create(body("CAR"),"first").andExpect(status().isCreated()).andReturn());
        patchAsset(first.path("purchasedAssetId").asLong(),"{\"name\":\"车辆2\"}").andExpect(status().isOk());
        var second=data(create(body("CAR"),"second").andExpect(status().isCreated()).andReturn());
        var third=data(create(body("CAR"),"third").andExpect(status().isCreated()).andReturn());
        mvc.perform(get("/api/assets/"+second.path("purchasedAssetId").asLong()).session(session)).andExpect(jsonPath("$.data.name").value("车辆1"));
        mvc.perform(get("/api/assets/"+third.path("purchasedAssetId").asLong()).session(session)).andExpect(jsonPath("$.data.name").value("车辆3"));
    }
    @Test void earlierSnapshotCannotDuplicateNameOrLosePairedCreateReplay() throws Exception {
        withEarlierSnapshot(()->create(body("CAR"),"current-first").andExpect(status().isCreated()),()->{
            create(body("CAR"),"current-first").andExpect(status().isCreated()).andExpect(jsonPath("$.data.purchasedAssetId").isNumber());
            var second=data(create(body("CAR"),"current-second").andExpect(status().isCreated()).andReturn());
            assertThat(jdbc.queryForObject("select name from assets where id=? for update",String.class,second.path("purchasedAssetId").asLong())).isEqualTo("车辆2");
        });
        assertThat(count("assets")).isEqualTo(2);assertThat(count("loans")).isEqualTo(2);
    }
    @Test void earlierSnapshotMetadataCompletionReadsCurrentSubtype() throws Exception {
        var loan=data(create(body("CAR"),"current-metadata").andExpect(status().isCreated()).andReturn());long asset=loan.path("purchasedAssetId").asLong();
        long journals=count("ledger_journals");
        withEarlierSnapshot(()->patchAsset(asset,"{\"vehicle\":{\"brandModel\":\"真实车型\",\"plateHint\":\"真实车牌\",\"purchaseYear\":2026}}").andExpect(status().isOk()),()->{
            patchAsset(asset,"{\"name\":\"我的车\"}").andExpect(status().isOk()).andExpect(jsonPath("$.data.vehicle.brandModel").value("真实车型"))
                .andExpect(jsonPath("$.data.vehicle.plateHint").value("真实车牌")).andExpect(jsonPath("$.data.vehicle.purchaseYear").value(2026));
            patchAsset(asset,"{\"vehicle\":{\"brandModel\":\"更正车型\",\"plateHint\":\"真实车牌\",\"purchaseYear\":2026}}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vehicle.brandModel").value("更正车型")).andExpect(jsonPath("$.data.detailsPending").value(false));
        });
        assertThat(jdbc.queryForObject("select count(*) from vehicle_assets where asset_id=?",Long.class,asset)).isEqualTo(1);
        assertThat(count("ledger_journals")).isEqualTo(journals);
    }
    private void withEarlierSnapshot(Checked outside,Checked inside)throws Exception {
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        boolean mysql=Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c->"MySQL".equals(c.getMetaData().getDatabaseProductName())));
        if(mysql)tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try{tx.executeWithoutResult(ignored->{
            jdbc.queryForObject("select count(*) from assets where household_id=?",Long.class,household);
            jdbc.queryForObject("select count(*) from vehicle_assets where household_id=?",Long.class,household);
            try{pool.submit(()->{outside.run();return null;}).get(10,java.util.concurrent.TimeUnit.SECONDS);inside.run();}catch(Exception e){throw new RuntimeException(e);}
        });}finally{pool.shutdownNow();assertThat(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
    }
    @FunctionalInterface private interface Checked {void run()throws Exception;}
    private ResultActions patchAsset(long id,String body)throws Exception{return mvc.perform(patch("/api/assets/"+id).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));}
    private ResultActions patchLoan(long id,String body)throws Exception{return mvc.perform(patch("/api/loans/"+id).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));}
    private ResultActions create(String body,String key)throws Exception{return mvc.perform(post("/api/loans").session(session).with(csrf()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body));}
    private String body(String type){return "{\"name\":\"购买贷款\",\"type\":\""+type+"\",\"paymentAccountId\":"+account+",\"paymentCategoryId\":"+category+",\"principal\":\"1000.00\",\"annualRate\":0.06,\"termMonths\":2,\"repaymentMethod\":\"EQUAL_PAYMENT\",\"startOn\":\"2026-01-01\",\"accountingOn\":\"2026-01-01\",\"fundingMode\":\"FINANCED_PURCHASE\",\"createPurchasedAsset\":true}";}
    private long count(String table){return jdbc.queryForObject("select count(*) from "+table+" where household_id=?",Long.class,household);}
    private JsonNode data(MvcResult r)throws Exception{return json.readTree(r.getResponse().getContentAsString()).path("data");}
}
