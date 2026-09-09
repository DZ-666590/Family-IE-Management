package com.familyfinance.fx;

import java.math.BigDecimal;
import java.math.MathContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
public class FrankfurterRateProvider implements ExchangeRateProvider {
    static java.util.List<ExchangeRateBatch> parseRange(String raw,LocalDate from,LocalDate to) {
        validateRange(from,to);
        try {
            if(raw.length()>MAX_BYTES)throw new IllegalArgumentException();
            var rows=JSON.readTree(raw);
            if(!rows.isArray()||rows.isEmpty()||rows.size()>180)throw new IllegalArgumentException();
            var grouped=new java.util.TreeMap<LocalDate,java.util.List<String>>();
            for(var row:rows) {
                var date=LocalDate.parse(row.path("date").asText());
                if(date.isBefore(from)||date.isAfter(to))throw new IllegalArgumentException();
                grouped.computeIfAbsent(date,ignored->new java.util.ArrayList<>()).add(row.toString());
            }
            var batches=new java.util.ArrayList<ExchangeRateBatch>();
            for(var entry:grouped.entrySet())batches.add(parse("["+String.join(",",entry.getValue())+"]",entry.getKey()));
            return java.util.List.copyOf(batches);
        }catch(RuntimeException failure){throw new IllegalArgumentException("Invalid or unsupported FX reference range");}
    }
    private static void validateRange(LocalDate from,LocalDate to) {
        if(from==null||to==null||from.getYear()<1999||from.isAfter(to)||java.time.temporal.ChronoUnit.DAYS.between(from,to)>89)
            throw new IllegalArgumentException("FX range must contain 1 to 90 days");
    }
    private static final int MAX_BYTES=32768;
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final java.util.concurrent.ScheduledExecutorService deadline=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"fx-request-deadline"); t.setDaemon(true); return t;
    });
    @PreDestroy void close(){deadline.shutdownNow();}

    @Override public ExchangeRateBatch fetch(LocalDate asOf) {
        return parse(request("date="+asOf),asOf);
    }
    @Override public java.util.List<ExchangeRateBatch> fetchRange(LocalDate from,LocalDate to) {
        validateRange(from,to);
        var batches=parseRange(request("from="+from+"&to="+to),from,to);
        // A short missing boundary may be a weekend, or an unsupported currency period.
        // Ask the date endpoint instead of inferring which from the range's first/last row.
        if(batches.get(0).effectiveOn().isAfter(from)) {
            var first=fetch(from);
            if(!first.effectiveOn().isBefore(from)||first.effectiveOn().isBefore(from.minusDays(7)))throw new IllegalArgumentException("Unsupported FX range start");
        }
        var last=batches.get(batches.size()-1);
        if(last.effectiveOn().isBefore(to)) {
            var resolved=fetch(to);
            if(!resolved.equals(last))throw new IllegalArgumentException("Incomplete FX range end");
        }
        return batches;
    }
    String request(String dates) {
        // Fixed endpoint: no user-supplied host, credentials or household data.
        var request=new HttpGet(URI.create("https://api.frankfurter.dev/v2/rates?base=CNY&quotes=USD,HKD&providers=ECB&"+dates));
        request.setHeader("Accept","application/json");
        var cancel=deadline.schedule(request::cancel,20,TimeUnit.SECONDS);
        var manager=PoolingHttpClientConnectionManagerBuilder.create().setMaxConnTotal(1).setMaxConnPerRoute(1)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(5))
                        .setSocketTimeout(Timeout.ofSeconds(10)).build()).build();
        try(var client=HttpClients.custom().setConnectionManager(manager).disableRedirectHandling().disableAutomaticRetries()
                .disableCookieManagement().disableContentCompression().setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofSeconds(2)).setResponseTimeout(Timeout.ofSeconds(10)).build()).build()) {
            return client.execute(request,response->{
                if(response.getCode()!=200 || response.getEntity()==null)throw new IllegalArgumentException("FX provider unavailable");
                byte[] bytes=response.getEntity().getContent().readNBytes(MAX_BYTES+1);
                if(bytes.length>MAX_BYTES){request.cancel();throw new IllegalArgumentException("FX response too large");}
                return new String(bytes,StandardCharsets.UTF_8);
            });
        }catch(Exception failure){throw new IllegalArgumentException("FX provider unavailable");}
        finally{cancel.cancel(false);}
    }

    static ExchangeRateBatch parse(String raw,LocalDate asOf) {
        try {
            if(raw.length()>MAX_BYTES)throw new IllegalArgumentException();
            var rows=JSON.readTree(raw);
            if(!rows.isArray()||rows.size()!=2)throw new IllegalArgumentException();
            LocalDate date=null;
            var rates=new HashMap<String,BigDecimal>();
            for(var row:rows) {
                if(!row.path("base").asText().equals("CNY")||!row.path("rate").isNumber())throw new IllegalArgumentException();
                var day=LocalDate.parse(row.path("date").asText());
                if(day.isAfter(asOf)||(date!=null&&!date.equals(day)))throw new IllegalArgumentException();
                date=day;
                String quote=row.path("quote").asText();
                BigDecimal rate=new BigDecimal(row.path("rate").asText());
                if(rate.signum()<=0 || rate.compareTo(new BigDecimal("1000000"))>0 || rate.scale()>18)throw new IllegalArgumentException();
                if(rates.putIfAbsent(quote,BigDecimal.ONE.divide(rate,MathContext.DECIMAL128))!=null)throw new IllegalArgumentException();
            }
            return new ExchangeRateBatch("ECB",date,rates);
        }catch(RuntimeException failure){throw new IllegalArgumentException("Invalid FX reference response");}
    }
}
