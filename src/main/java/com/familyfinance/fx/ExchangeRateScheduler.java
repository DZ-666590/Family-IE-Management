package com.familyfinance.fx;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.fx.scheduled",havingValue="true")
class ExchangeRateScheduler {
    private final ExchangeRateService rates;
    ExchangeRateScheduler(ExchangeRateService rates){this.rates=rates;}
    // Retry once on the next hour; service-level cooldown/concurrency still applies.
    @Scheduled(cron="0 10 23,0 * * *",zone="Asia/Shanghai")
    void refresh(){rates.refresh(null);}
}
