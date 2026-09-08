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
    private static final int MAX_BYTES=32768;
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final java.util.concurrent.ScheduledExecutorService deadline=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"fx-request-deadline"); t.setDaemon(true); return t;
    });
    @PreDestroy void close(){deadline.shutdownNow();}

    @Override public ExchangeRateBatch fetch(LocalDate asOf) {
        // Fixed endpoint: no user-supplied host, credentials or household data.
        var request=new HttpGet(URI.create("https://api.frankfurter.dev/v2/rates?base=CNY&quotes=USD,HKD&providers=ECB&date="+asOf));
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
                return parse(new String(bytes,StandardCharsets.UTF_8),asOf);
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
