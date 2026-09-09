package com.familyfinance.market;

import com.familyfinance.investment.Security;
import com.familyfinance.investment.SecurityRepository;
import com.familyfinance.shared.RequestValidationException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** External reads outside financial transactions. Shared provider cache lives in the sidecar. */
@Service
public class SpotQuoteService {
    private final SecurityRepository securities;
    private final MarketDataClient client;
    private final Clock clock;
    public SpotQuoteService(SecurityRepository securities,MarketDataClient client,Clock clock){this.securities=securities;this.client=client;this.clock=clock;}
    public record Quote(long securityId,String name,String tsCode,String market,String currency,String price,
                        Instant quotedAt,Instant fetchedAt,String source,Integer delayMinutes,String status,Long ageSeconds,String marketState){}
    public record Result(List<Quote> quotes,Map<Long,MarketPriceResponse> prices,int nextRefreshSeconds){}

    public Result fetch(List<Long> ids){
        if(ids==null||ids.size()>150||ids.stream().anyMatch(id->id==null||id<=0))throw invalid();
        ids=ids.stream().distinct().toList();
        var groups=new LinkedHashMap<String,List<Security>>();
        List<Quote> result=new ArrayList<>();Map<Long,MarketPriceResponse> prices=new LinkedHashMap<>();int interval=259200;
        for(Security security:securities.findAllById(ids)){
            String market=market(security);
            if(!security.isCatalogVerified()||!security.isActive()||market==null){result.add(unavailable(security,"UNSUPPORTED"));continue;}
            groups.computeIfAbsent(market,ignored->new ArrayList<>()).add(security);
        }
        for(var group:groups.entrySet())for(int offset=0;offset<group.getValue().size();offset+=50){
            var items=group.getValue().subList(offset,Math.min(offset+50,group.getValue().size()));
            var symbols=items.stream().map(SpotQuoteService::symbol).toList();
            SpotBatchResponse batch;
            try{batch=client.spot(group.getKey(),symbols);
                if(batch==null||batch.quotes()==null||batch.quotes().size()>50||!Set.of("TRADING_HOURS","CLOSED_HOURS").contains(batch.marketState()))throw invalid();
            }catch(RuntimeException failure){for(var item:items)result.add(unavailable(item,"UNAVAILABLE"));interval=Math.min(interval,60);continue;}
            interval=Math.min(interval,Math.max(60,Math.min(259200,batch.nextRefreshSeconds())));
            for(var security:items){
                var matches=batch.quotes().stream().filter(q->q!=null&&symbol(security).equals(q.symbol())).toList();
                SpotQuote quote=matches.size()==1?matches.get(0):null;
                if(!valid(security,quote)){result.add(unavailable(security,"UNAVAILABLE"));continue;}
                String amount=quote.price()==null?null:quote.price().stripTrailingZeros().toPlainString();
                result.add(new Quote(security.getId(),security.getName(),security.getTsCode(),security.getMarket(),security.getCurrency(),amount,quote.quotedAt(),quote.fetchedAt(),quote.source(),quote.delayMinutes(),quote.status(),quote.ageSeconds(),batch.marketState()));
                if(amount!=null&&!quote.status().equals("UNAVAILABLE")){
                    LocalDate date=quote.quotedAt().atZone(ZoneId.of(security.getTimezone())).toLocalDate();
                    prices.put(security.getId(),new MarketPriceResponse(security.getId(),security.getTsCode(),security.getName(),amount,QuoteSource.TENCENT,date,quote.fetchedAt(),!quote.status().equals("OK"),quote.status().equals("OK")?null:quote.status(),security.getCurrency()));
                }
            }
        }
        return new Result(List.copyOf(result),Map.copyOf(prices),groups.isEmpty()?3600:interval);
    }
    private boolean valid(Security security,SpotQuote quote){
        if(quote==null||!Objects.equals(market(security),quote.market())||!security.getCurrency().equals(quote.currency())||!"TENCENT_PUBLIC".equals(quote.source())||quote.status()==null||!Set.of("OK","DELAYED","STALE","UNAVAILABLE").contains(quote.status()))return false;
        if(quote.price()==null)return quote.status().equals("UNAVAILABLE");
        return quote.price().signum()>0&&quote.price().compareTo(new BigDecimal("1000000000000"))<=0&&quote.price().scale()<=6
                &&quote.quotedAt()!=null&&quote.fetchedAt()!=null&&!quote.quotedAt().isAfter(clock.instant().plusSeconds(120))
                &&!quote.fetchedAt().isAfter(clock.instant().plusSeconds(120))&&!quote.quotedAt().isBefore(clock.instant().minus(Duration.ofDays(7)));
    }
    private static String market(Security security){return Set.of("SH","SZ","BJ").contains(security.getMarket())?"CN":Set.of("HK","US").contains(security.getMarket())?security.getMarket():null;}
    private static String symbol(Security security){return "CN".equals(market(security))?security.getTsCode():security.getSymbol();}
    private static Quote unavailable(Security security,String status){return new Quote(security.getId(),security.getName(),security.getTsCode(),security.getMarket(),security.getCurrency(),null,null,null,"TENCENT_PUBLIC",null,status,null,"CLOSED_HOURS");}
    private static RequestValidationException invalid(){return new RequestValidationException(Map.of("securityIds","每次最多查询150个有效证券编号"));}
}
