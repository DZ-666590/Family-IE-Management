package com.familyfinance.fx;

import com.familyfinance.shared.RequestValidationException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;

@Service
public class ExchangeRateService {
    private final ExchangeRateProvider provider;
    private final ExchangeRateStore store;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private FxJournalRates journalRates;
    private final ReentrantLock refreshLock=new ReentrantLock();
    private volatile Instant lastAttempt=Instant.MIN;
    private volatile String refreshState="IDLE";
    public ExchangeRateService(ExchangeRateProvider provider,ExchangeRateStore store,Clock clock){this.provider=provider;this.store=store;this.clock=clock;}
    public record Row(String currency,String cnyPerUnit,LocalDate effectiveOn,Instant fetchedAt,String source,String state,Long batchId){}
    public record Table(LocalDate asOf,List<Row> rows,String refreshState){}
    public LocalDate today(){return LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));}
    public Table table(LocalDate asOf){asOf=validDate(asOf);return table(asOf,refreshState);}
    private Table table(LocalDate day,String state) {
        var snapshot=store.latest(day).orElse(null);
        List<Row> rows=new ArrayList<>();
        rows.add(new Row("CNY","1.000000000000",day,null,"IDENTITY","READY",null));
        for(String currency:List.of("HKD","USD"))rows.add(snapshot==null
                ?new Row(currency,null,null,null,null,"MISSING",null)
                :new Row(currency,snapshot.rates().get(currency).toPlainString(),snapshot.effectiveOn(),snapshot.fetchedAt(),snapshot.source(),
                        ChronoUnit.DAYS.between(snapshot.effectiveOn(),day)>4?"STALE":"READY",snapshot.id()));
        return new Table(day,List.copyOf(rows),state);
    }
    public List<Row> history(LocalDate from,LocalDate to) {
        from=validDate(from);to=validDate(to);
        if(from.isAfter(to)||ChronoUnit.DAYS.between(from,to)>89)throw invalid("历史查询范围最多90天");
        List<Row> rows=new ArrayList<>();
        for(var snap:store.history(from,to))for(String currency:List.of("HKD","USD"))
            rows.add(new Row(currency,snap.rates().get(currency).toPlainString(),snap.effectiveOn(),snap.fetchedAt(),snap.source(),"HISTORICAL",snap.id()));
        return List.copyOf(rows);
    }
    public Table refresh(LocalDate asOf) {
        LocalDate day=validDate(asOf);
        if(!refreshLock.tryLock())return table(day,"UPDATING");
        try {
            Instant now=clock.instant();
            if(lastAttempt.isAfter(now.minusSeconds(60)))return table(day,"THROTTLED");
            lastAttempt=now;refreshState="UPDATING";
            try {
                var batch=provider.fetch(day);
                if(batch.effectiveOn().isAfter(day))throw new IllegalArgumentException("Future provider date");
                store.save(batch,clock.instant());
                if(journalRates!=null)journalRates.backfill();
                refreshState="SUCCESS";
            }catch(RuntimeException failure){refreshState="FAILED";}
            return table(day,refreshState);
        }finally{refreshLock.unlock();}
    }
    private LocalDate validDate(LocalDate date){
        if(date==null)date=today();
        if(date.getYear()<1999||date.isAfter(today()))throw invalid("汇率日期必须在1999年至今天之间");
        return date;
    }
    private static RequestValidationException invalid(String message){return new RequestValidationException(Map.of("date",message));}
}
