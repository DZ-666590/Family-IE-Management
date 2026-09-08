package com.familyfinance.fx;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ExchangeRateStore {
    private final JdbcTemplate jdbc;
    public ExchangeRateStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Snapshot(long id,String source,LocalDate effectiveOn,Instant fetchedAt,Map<String,BigDecimal> rates) {}

    @Transactional
    public long save(ExchangeRateBatch batch,Instant fetchedAt) {
        if(batch.effectiveOn().isAfter(fetchedAt.atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate()))
            throw new IllegalArgumentException("Future exchange rate");
        // Serialize ingestion only, never network access or the family's ledger.
        jdbc.queryForObject("select id from fx_sync_state where id=1 for update",Long.class);
        String digest=digest(batch);
        var previous=jdbc.query("select id,revision,payload_hash from fx_rate_batches where source=? and effective_on=? order by revision desc limit 1",
                (rs,n)->new Previous(rs.getLong(1),rs.getInt(2),rs.getString(3)),batch.source(),batch.effectiveOn());
        if(!previous.isEmpty()&&previous.get(0).hash().equals(digest))return previous.get(0).id();
        int revision=previous.isEmpty()?1:Math.addExact(previous.get(0).revision(),1);
        var keys=new GeneratedKeyHolder();
        jdbc.update(connection->{
            var ps=connection.prepareStatement("insert into fx_rate_batches(source,effective_on,fetched_at,revision,payload_hash) values (?,?,?,?,?)",java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1,batch.source());ps.setObject(2,batch.effectiveOn());ps.setTimestamp(3,java.sql.Timestamp.from(fetchedAt));
            ps.setInt(4,revision);ps.setString(5,digest);return ps;
        },keys);
        long id=keys.getKey().longValue();
        batch.cnyPerUnit().forEach((currency,rate)->jdbc.update("insert into fx_rates(batch_id,currency,cny_per_unit) values(?,?,?)",id,currency,rate));
        return id;
    }
    private record Previous(long id,int revision,String hash){}

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Optional<Snapshot> latest(LocalDate asOf) {
        var rows=jdbc.query("select id,source,effective_on,fetched_at from fx_rate_batches where effective_on<=? and source='ECB' order by effective_on desc,revision desc limit 1",
                (rs,n)->snapshot(rs.getLong(1),rs.getString(2),rs.getObject(3,LocalDate.class),rs.getTimestamp(4).toInstant()),asOf);
        return rows.stream().findFirst();
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<Snapshot> history(LocalDate from,LocalDate to) {
        return jdbc.query("""
            select b.id,b.source,b.effective_on,b.fetched_at from fx_rate_batches b
            where b.effective_on>=? and b.effective_on<=? and b.source='ECB'
              and b.revision=(select max(x.revision) from fx_rate_batches x where x.source=b.source and x.effective_on=b.effective_on)
            order by b.effective_on desc
            """,(rs,n)->snapshot(rs.getLong(1),rs.getString(2),rs.getObject(3,LocalDate.class),rs.getTimestamp(4).toInstant()),from,to);
    }
    private Snapshot snapshot(long id,String source,LocalDate date,Instant fetchedAt) {
        Map<String,BigDecimal> rates=new HashMap<>();
        jdbc.query("select currency,cny_per_unit from fx_rates where batch_id=?",rs->{rates.put(rs.getString(1),rs.getBigDecimal(2));},id);
        if(!rates.keySet().equals(Set.of("HKD","USD")))throw new IllegalStateException("Incomplete persisted FX batch");
        return new Snapshot(id,source,date,fetchedAt,Map.copyOf(rates));
    }
    private static String digest(ExchangeRateBatch batch) {
        String canonical=batch.source()+"|"+batch.effectiveOn()+"|HKD:"+batch.cnyPerUnit().get("HKD").toPlainString()+"|USD:"+batch.cnyPerUnit().get("USD").toPlainString();
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
