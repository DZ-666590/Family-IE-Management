package com.familyfinance.fx;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.fx.scheduled",havingValue="true")
class ExchangeRateScheduler {
    private final ExchangeRateService rates;
    private final FxJournalRates journals;
    private final ExchangeRateHistory history;
    ExchangeRateScheduler(ExchangeRateService rates,FxJournalRates journals,ExchangeRateHistory history){this.rates=rates;this.journals=journals;this.history=history;}
    // Retry once on the next hour; service-level cooldown/concurrency still applies.
    @Scheduled(cron="0 10 23,0 * * *",zone="Asia/Shanghai")
    void refresh(){rates.refresh(null);history.acquire(rates.today().minusDays(89),rates.today());}
    @Scheduled(fixedDelay=60000)
    void backfill(){journals.backfill();history.acquirePending();}
    @Scheduled(initialDelay=5000,fixedDelay=3600000)
    void initializeMissing(){
        if(rates.table(null).rows().stream().anyMatch(row->row.cnyPerUnit()==null))rates.refresh(null);
        history.acquire(rates.today().minusDays(89),rates.today());
    }
}
