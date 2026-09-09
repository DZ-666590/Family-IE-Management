package com.familyfinance.fx;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest @ActiveProfiles("test")
class ExchangeRateHistoryTest {
    @Autowired ExchangeRateStore store;@Autowired FxJournalRates journals;@Autowired JdbcTemplate jdbc;
    final Clock clock=Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"),ZoneOffset.UTC);
    ExchangeRateBatch batch(LocalDate day){return new ExchangeRateBatch("ECB",day,Map.of("USD",new BigDecimal("7"),"HKD",new BigDecimal("0.9")));}
    @Test void reportOnlySundayResolvesAuthoritativelyAfterPublicationRangeIsAlreadyReady() throws Exception {
        var friday=LocalDate.of(2020,8,7);var sunday=LocalDate.of(2020,8,9);var monday=LocalDate.of(2020,8,10);
        store.saveRange(friday,monday,List.of(batch(friday),batch(monday)),clock.instant());
        assertThat(store.covers(friday,monday,Instant.parse("1999-01-01T00:00:00Z"))).isTrue();
        assertThat(journals.reference("USD",sunday)).isNull();
        var entries=jdbc.queryForList("select id,debit_amount,credit_amount,currency from ledger_entries order by id");
        var refs=jdbc.queryForList("select * from fx_journal_rates order by journal_id,currency");
        var calls=new AtomicInteger();
        ExchangeRateProvider provider=day->{
            assertThat(day).isEqualTo(sunday);assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls.incrementAndGet();return batch(friday);
        };
        try(var history=new ExchangeRateHistory(provider,store,journals,clock)){
            assertThat(history.requestValuationDate(sunday).state()).isEqualTo("UPDATING");
            awaitDateState(history,sunday,"READY");
            assertThat(journals.reference("USD",sunday).effectiveOn()).isEqualTo(friday);
            assertThat(calls.get()).isEqualTo(1);
            assertThat(history.requestValuationDate(sunday).state()).isEqualTo("READY");
        }
        assertThat(jdbc.queryForList("select id,debit_amount,credit_amount,currency from ledger_entries order by id")).isEqualTo(entries);
        assertThat(jdbc.queryForList("select * from fx_journal_rates order by journal_id,currency")).isEqualTo(refs);
    }
    @Test void requestedReportDateHasSingleflightBackoffAndKeepsStrictReferenceMissingOnFailure() throws Exception {
        var sunday=LocalDate.of(2020,7,5);var calls=new AtomicInteger();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        ExchangeRateProvider provider=day->{
            calls.incrementAndGet();entered.countDown();
            try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("timeout");}catch(InterruptedException e){throw new IllegalStateException(e);}
            throw new IllegalArgumentException("unavailable");
        };
        try(var history=new ExchangeRateHistory(provider,store,journals,clock)){
            history.requestValuationDate(sunday);assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            for(int i=0;i<10;i++)assertThat(history.requestValuationDate(sunday).state()).isEqualTo("UPDATING");
            assertThat(history.requestValuationDate(sunday.plusDays(1)).state()).isEqualTo("BUSY");
            release.countDown();awaitDateState(history,sunday,"FAILED");
            assertThat(history.requestValuationDate(sunday).retryAfter()).isEqualTo(clock.instant().plusSeconds(60));
            assertThat(journals.reference("USD",sunday)).isNull();assertThat(calls.get()).isEqualTo(1);
            assertThatThrownBy(()->history.requestValuationDate(LocalDate.of(2026,9,9))).isInstanceOf(IllegalArgumentException.class);
        }finally{release.countDown();}
    }
    @Test void historyFetchesMissingAugustDespiteExistingSeptemberAndReusesCoverage() throws Exception {
        var from=LocalDate.of(2026,8,1);var to=LocalDate.of(2026,8,30);var calls=new AtomicInteger();
        store.save(batch(LocalDate.of(2026,9,8)),clock.instant());
        ExchangeRateProvider provider=new ExchangeRateProvider(){
            public ExchangeRateBatch fetch(LocalDate day){throw new AssertionError("Range acquisition must not fetch every day");}
            public List<ExchangeRateBatch> fetchRange(LocalDate start,LocalDate end){
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(start).isEqualTo(from);assertThat(end).isEqualTo(to);calls.incrementAndGet();
                return start.datesUntil(end.plusDays(1)).filter(d->d.getDayOfWeek().getValue()<6).map(ExchangeRateHistoryTest.this::batch).toList();
            }
        };
        try(var history=new ExchangeRateHistory(provider,store,journals,clock)){
            assertThat(history.acquire(from,to).state()).isEqualTo("UPDATING");
            awaitState(history,from,to,"READY");
            assertThat(store.history(from,to)).hasSize(20);
            assertThat(history.acquire(from,to).state()).isEqualTo("READY");
            assertThat(calls.get()).isEqualTo(1);
        }
        try(var history=new ExchangeRateHistory(provider,store,journals,clock)){
            assertThat(history.acquire(from.plusDays(7),to).state()).isEqualTo("READY");
            assertThat(calls.get()).isEqualTo(1);
        }
    }
    @Test void failedRangeRetainsCacheAndConcurrentRequestsShareOneFetch() throws Exception {
        var from=LocalDate.of(2026,6,1);var to=from.plusDays(89);var calls=new AtomicInteger();
        store.save(batch(from),clock.instant());
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        ExchangeRateProvider provider=new ExchangeRateProvider(){
            public ExchangeRateBatch fetch(LocalDate day){throw new AssertionError();}
            public List<ExchangeRateBatch> fetchRange(LocalDate a,LocalDate b){
                calls.incrementAndGet();entered.countDown();
                try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("timeout");}catch(InterruptedException e){throw new IllegalStateException(e);}
                throw new IllegalArgumentException("upstream unavailable");
            }
        };
        try(var history=new ExchangeRateHistory(provider,store,journals,clock)){
            history.acquire(from,to);assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            for(int i=0;i<10;i++)assertThat(history.acquire(from,to).state()).isEqualTo("UPDATING");
            release.countDown();awaitState(history,from,to,"FAILED");
            assertThat(store.history(from,from)).hasSize(1);
            assertThat(history.acquire(from,to).retryAfter()).isEqualTo(clock.instant().plusSeconds(60));
            assertThat(calls.get()).isEqualTo(1);
            assertThatThrownBy(()->history.acquire(from,to.plusDays(1))).isInstanceOf(IllegalArgumentException.class);
        }finally{release.countDown();}
    }
    @Test void refreshRecordsWeekendResolutionInsteadOfGuessingFromOldCache(){
        var sunday=LocalDate.of(2026,9,6);var friday=LocalDate.of(2026,9,4);
        var rates=new ExchangeRateService(day->batch(friday),store,clock);
        assertThat(rates.refresh(sunday).refreshState()).isEqualTo("SUCCESS");
        assertThat(journals.reference("USD",sunday)).isNotNull();
        assertThat(journals.reference("USD",sunday).effectiveOn()).isEqualTo(friday);
        assertThat(journals.reference("USD",LocalDate.of(2026,9,7))).isNull();
    }
    @Test void readyResponseIncludesRowsCommittedWhileAcquisitionStatusWasChecked(){
        var day=LocalDate.of(2024,3,4);
        var progress=org.mockito.Mockito.mock(ExchangeRateHistory.class);
        org.mockito.Mockito.when(progress.acquire(day,day)).thenAnswer(invocation->{
            store.save(batch(day),clock.instant());return new ExchangeRateHistory.Progress("READY",null,null);
        });
        var service=new ExchangeRateService(this::batch,store,clock);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"historicalRates",progress);
        assertThat(service.historyView(day,day).rows()).hasSize(2);
    }
    @Test void partialUnsupportedRangeNeverRecordsCoverageOrPersistsPartOfResponse(){
        var from=LocalDate.of(2000,1,1);var to=from.plusDays(89);
        assertThatThrownBy(()->store.saveRange(from,to,List.of(batch(to)),clock.instant())).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThat(store.history(from,to)).isEmpty();
        assertThat(store.covers(from,to,Instant.parse("1999-01-01T00:00:00Z"))).isFalse();
    }
    @Test void fullNinetyDayWindowIsStoredWithBothRatesPerPublication(){
        var from=LocalDate.of(2023,1,1);var to=LocalDate.of(2023,3,31);
        var batches=from.datesUntil(to.plusDays(1)).filter(d->d.getDayOfWeek().getValue()<6).map(this::batch).toList();
        store.saveRange(from,to,batches,clock.instant());
        assertThat(store.history(from,to)).hasSize(65).allSatisfy(row->assertThat(row.rates()).containsOnlyKeys("USD","HKD"));
        assertThat(store.covers(from,to,clock.instant().minusSeconds(1))).isTrue();
    }
    @Test void storeFailureRollsBackAllBatchesAndCoverage(){
        var from=LocalDate.of(2024,5,1);var to=from.plusDays(1);
        assertThatThrownBy(()->store.saveRange(from,to,List.of(batch(from),batch(to)),Instant.parse("2024-05-01T12:00:00Z")))
            .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThat(store.history(from,to)).isEmpty();
        assertThat(store.covers(from,to,Instant.parse("1999-01-01T00:00:00Z"))).isFalse();
    }
    @Test void pendingPassFetchesAtMostEightDistinctDates() throws Exception {
        var from=LocalDate.of(2022,1,1);var pending=org.mockito.Mockito.mock(FxJournalRates.class);
        org.mockito.Mockito.when(pending.unresolvedDates(org.mockito.ArgumentMatchers.any())).thenReturn(from.datesUntil(from.plusDays(10)).toList());
        var completed=new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation->{completed.countDown();return null;}).when(pending).backfill();
        var calls=new AtomicInteger();
        ExchangeRateProvider provider=day->{calls.incrementAndGet();return batch(day);};
        try(var history=new ExchangeRateHistory(provider,store,pending,clock)){
            history.acquirePending();assertThat(completed.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(calls.get()).isEqualTo(8);
            assertThat(store.history(from,from.plusDays(9))).hasSize(8);
            assertThat(store.history(from.plusDays(8),from.plusDays(9))).isEmpty();
        }
    }
    void awaitState(ExchangeRateHistory history,LocalDate from,LocalDate to,String expected) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<end){if(history.acquire(from,to).state().equals(expected))return;Thread.sleep(10);}
        assertThat(history.acquire(from,to).state()).isEqualTo(expected);
    }
    void awaitDateState(ExchangeRateHistory history,LocalDate day,String expected) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<end){if(history.requestValuationDate(day).state().equals(expected))return;Thread.sleep(10);}
        assertThat(history.requestValuationDate(day).state()).isEqualTo(expected);
    }
}
