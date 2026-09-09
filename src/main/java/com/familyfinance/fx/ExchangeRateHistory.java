package com.familyfinance.fx;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

@Service
public class ExchangeRateHistory implements AutoCloseable {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ExchangeRateHistory.class);
    public record Progress(String state,Instant retryAfter,String detail){}
    private record Range(LocalDate from,LocalDate to){}
    private final ExchangeRateProvider provider;
    private final ExchangeRateStore store;
    private final FxJournalRates journals;
    private final Clock clock;
    private final AtomicBoolean busy=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var thread=new Thread(r,"fx-history");thread.setDaemon(true);return thread;});
    private final Map<Range,Progress> attempts=new LinkedHashMap<>();
    private final Map<LocalDate,Progress> valuationAttempts=new LinkedHashMap<>();
    private final Map<LocalDate,Instant> dateRetries=new LinkedHashMap<>();
    private LocalDate pendingCursor=LocalDate.of(1998,12,31);
    private volatile boolean closed;
    public ExchangeRateHistory(ExchangeRateProvider provider,ExchangeRateStore store,FxJournalRates journals,Clock clock){
        this.provider=provider;this.store=store;this.journals=journals;this.clock=clock;
    }
    /** Resolve a report date even when no journal exists on that day; never bind or alter the ledger. */
    public synchronized Progress requestValuationDate(LocalDate day){
        var today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
        if(day==null||day.getYear()<1999||day.isAfter(today))throw new IllegalArgumentException("Unsupported FX report date");
        if(journals.reference("USD",day)!=null&&journals.reference("HKD",day)!=null)
            return new Progress("READY",null,"该日期的权威参考汇率已就绪。");
        if(closed)return new Progress("UNAVAILABLE",null,"历史采集暂不可用。");
        var previous=valuationAttempts.get(day);
        if(previous!=null&&(previous.state().equals("UPDATING")||previous.retryAfter()!=null&&clock.instant().isBefore(previous.retryAfter())))return previous;
        if(!busy.compareAndSet(false,true))return new Progress("BUSY",clock.instant().plusSeconds(5),"其他汇率采集正在进行，请稍后重试此报表日期。");
        var updating=new Progress("UPDATING",null,"正在获取该报表日期的权威参考汇率。");
        valuationAttempts.put(day,updating);trim(valuationAttempts);
        worker.execute(()->{
            Progress result;
            try{
                // The date endpoint alone establishes Sunday -> Friday, never a guessed cached predecessor.
                var batch=provider.fetch(day);
                store.saveResolved(day,batch,clock.instant());
                result=new Progress("READY",null,null);
            }catch(RuntimeException failure){
                result=new Progress("FAILED",clock.instant().plusSeconds(60),"该报表日期汇率获取失败，尚未建立参考引用；一分钟后可重试。");
            }
            synchronized(this){valuationAttempts.put(day,result);busy.set(false);}
        });
        return updating;
    }
    /** Returns immediately with cached data; one worker and no queued requests bound upstream load. */
    public synchronized Progress acquire(LocalDate from,LocalDate to){
        var today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
        if(from==null||to==null||from.getYear()<1999||from.isAfter(to)||to.isAfter(today)||ChronoUnit.DAYS.between(from,to)>89)
            throw new IllegalArgumentException("FX history range must contain 1 to 90 supported days");
        Instant freshAfter=to.isBefore(today.minusDays(2))?Instant.parse("1999-01-01T00:00:00Z"):clock.instant().minusSeconds(3600);
        if(store.covers(from,to,freshAfter))return new Progress("READY",null,"已获取该范围内的发布日汇率；周末和休市日不生成虚构记录。");
        var range=new Range(from,to);var previous=attempts.get(range);
        if(previous!=null&&previous.retryAfter()!=null&&clock.instant().isBefore(previous.retryAfter()))return previous;
        if(closed)return new Progress("UNAVAILABLE",null,"历史采集暂不可用。");
        var updating=new Progress("UPDATING",null,"正在补齐历史汇率，已有记录仍可查看。");
        if(!busy.compareAndSet(false,true))return updating;
        attempts.put(range,updating);
        trim(attempts);
        worker.execute(()->{
            Progress result;
            try{
                // No surrounding transaction: all HTTP completes before atomic store persistence.
                var batches=provider.fetchRange(from,to);
                store.saveRange(from,to,batches,clock.instant());
                journals.backfill();
                result=new Progress("READY",null,null);
            }catch(RuntimeException failure){
                result=new Progress("FAILED",clock.instant().plusSeconds(60),"历史汇率获取失败或该范围不受支持，已保留已有记录；一分钟后可重试。");
            }
            synchronized(this){attempts.put(range,result);busy.set(false);}
        });
        return updating;
    }
    /** Each scheduled pass resolves at most 8 actual journal dates, separately from ledger locks. */
    public synchronized void acquirePending(){
        if(closed||!busy.compareAndSet(false,true))return;
        worker.execute(()->{
            try{
                var today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
                int fetched=0;
                int failed=0;
                var dates=journals.unresolvedDates(pendingCursor);
                if(dates.isEmpty()){pendingCursor=LocalDate.of(1998,12,31);dates=journals.unresolvedDates(pendingCursor);}
                for(var day:dates){
                    if(fetched>=8)break;
                    pendingCursor=day;
                    if(day.getYear()<1999||day.isAfter(today))continue;
                    var retry=dateRetries.get(day);if(retry!=null&&retry.isAfter(clock.instant()))continue;
                    fetched++;
                    try{store.saveResolved(day,provider.fetch(day),clock.instant());dateRetries.remove(day);}
                    catch(RuntimeException failure){failed++;dateRetries.put(day,clock.instant().plusSeconds(3600));trim(dateRetries);}
                }
                journals.backfill();
                if(failed>0)log.warn("Historical FX reference acquisition incomplete; existing references and native amounts preserved, retry scheduled");
            }finally{busy.set(false);}
        });
    }
    private static void trim(Map<?,?> map){while(map.size()>64)map.remove(map.keySet().iterator().next());}
    @PreDestroy public synchronized void close(){closed=true;worker.shutdownNow();}
}
