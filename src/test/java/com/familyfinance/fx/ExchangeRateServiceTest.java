package com.familyfinance.fx;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ExchangeRateServiceTest {
    @Autowired ExchangeRateStore store;
    @Autowired JdbcTemplate jdbc;
    final Clock clock=Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"),ZoneOffset.UTC);
    ExchangeRateService service;
    @BeforeEach void setup() {
        jdbc.update("delete from fx_rates"); jdbc.update("delete from fx_rate_batches");
        service=new ExchangeRateService(day->batch(day,"7","0.9"),store,clock);
    }
    ExchangeRateBatch batch(LocalDate day,String usd,String hkd) {
        return new ExchangeRateBatch("ECB",day,Map.of("USD",new BigDecimal(usd),"HKD",new BigDecimal(hkd)));
    }
    @Test void noRatesNeverMasqueradeAsParityOrZero() {
        var table=service.table(LocalDate.of(2026,9,8));
        assertThat(table.rows().get(0).cnyPerUnit()).isEqualTo("1.000000000000");
        assertThat(table.rows().get(1).cnyPerUnit()).isNull();
        assertThat(table.rows().get(1).state()).isEqualTo("MISSING");
    }
    @Test void savesCompleteBatchAndConvertsInCorrectDirection() {
        service.refresh(LocalDate.of(2026,9,8));
        var row=service.table(LocalDate.of(2026,9,8)).rows().stream().filter(r->r.currency().equals("USD")).findFirst().orElseThrow();
        assertThat(new BigDecimal(row.cnyPerUnit()).multiply(new BigDecimal("100"))).isEqualByComparingTo("700");
        assertThat(row.source()).isEqualTo("ECB");
        assertThat(jdbc.queryForObject("select count(*) from fx_rates",Integer.class)).isEqualTo(2);
    }
    @Test void sameDayRevisionsAreImmutableAndIdenticalRefreshDoesNotDuplicate() {
        store.save(batch(LocalDate.of(2026,9,7),"7","0.9"),clock.instant());
        store.save(batch(LocalDate.of(2026,9,7),"7","0.9"),clock.instant().plusSeconds(1));
        store.save(batch(LocalDate.of(2026,9,7),"7.1","0.91"),clock.instant().plusSeconds(2));
        assertThat(jdbc.queryForObject("select count(*) from fx_rate_batches",Integer.class)).isEqualTo(2);
        assertThat(service.table(LocalDate.of(2026,9,8)).rows().stream().filter(r->r.currency().equals("USD")).findFirst().orElseThrow().cnyPerUnit())
                .isEqualTo("7.100000000000");
        // A source can revert a correction; that must become the newest revision,
        // not be dropped merely because the same value existed before.
        store.save(batch(LocalDate.of(2026,9,7),"7","0.9"),clock.instant().plusSeconds(3));
        assertThat(jdbc.queryForObject("select count(*) from fx_rate_batches",Integer.class)).isEqualTo(3);
        assertThat(service.table(LocalDate.of(2026,9,8)).rows().get(2).cnyPerUnit()).isEqualTo("7.000000000000");
    }
    @Test void historicalQueryNeverUsesFutureRateAndShowsOldRateExplicitly() {
        store.save(batch(LocalDate.of(2026,9,1),"7","0.9"),clock.instant());
        store.save(batch(LocalDate.of(2026,9,8),"8","1"),clock.instant());
        var row=service.table(LocalDate.of(2026,9,7)).rows().get(1);
        assertThat(row.effectiveOn()).isEqualTo(LocalDate.of(2026,9,1));
        assertThat(row.state()).isEqualTo("STALE");
    }
    @Test void partialOrNonpositiveBatchIsRejectedBeforeAnyWrite() {
        assertThatThrownBy(()->store.save(new ExchangeRateBatch("ECB",LocalDate.of(2026,9,7),Map.of("USD",BigDecimal.ONE)),clock.instant()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->store.save(batch(LocalDate.of(2026,9,7),"0","0.9"),clock.instant()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("select count(*) from fx_rate_batches",Integer.class)).isZero();
    }
    @Test void failureRetainsLastGoodBatchAndRefreshIsRateLimited() {
        store.save(batch(LocalDate.of(2026,9,7),"7","0.9"),clock.instant());
        service=new ExchangeRateService(day->{throw new IllegalStateException("upstream failure");},store,clock);
        assertThat(service.refresh(LocalDate.of(2026,9,8)).refreshState()).isEqualTo("FAILED");
        assertThat(service.table(LocalDate.of(2026,9,8)).rows().get(1).cnyPerUnit()).isNotNull();
        assertThat(service.refresh(LocalDate.of(2026,9,8)).refreshState()).isEqualTo("THROTTLED");
    }
    @Test void futureAndExcessiveHistoryRequestsAreRejected() {
        assertThatThrownBy(()->service.table(LocalDate.of(2026,9,9))).isInstanceOf(com.familyfinance.shared.RequestValidationException.class);
        assertThatThrownBy(()->service.history(LocalDate.of(2026,1,1),LocalDate.of(2026,9,8))).isInstanceOf(com.familyfinance.shared.RequestValidationException.class);
    }
}
