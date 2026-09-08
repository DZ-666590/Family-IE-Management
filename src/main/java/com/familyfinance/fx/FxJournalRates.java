package com.familyfinance.fx;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reference translation is separate from native cash and is immutable once attached. */
@Service
public class FxJournalRates {
 private final JdbcTemplate jdbc;
 public FxJournalRates(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public record Rate(long batchId,LocalDate effectiveOn,BigDecimal value){}
 public Rate reference(String currency,LocalDate day){
  if(currency.equals("CNY"))return new Rate(0,day,BigDecimal.ONE);
  return jdbc.query("""
    select b.id,b.effective_on,r.cny_per_unit from fx_rate_batches b join fx_rates r on r.batch_id=b.id
    where r.currency=? and b.source='ECB' and b.effective_on<=? order by b.effective_on desc,b.revision desc limit 1
    """,(rs,n)->new Rate(rs.getLong(1),rs.getObject(2,LocalDate.class),rs.getBigDecimal(3)),currency,day).stream().findFirst().orElse(null);
 }
 public BigDecimal convert(String currency,BigDecimal amount,LocalDate day){
  if(amount.signum()==0)return BigDecimal.ZERO.setScale(2);var rate=reference(currency,day);
  return rate==null?null:amount.multiply(rate.value()).setScale(2,RoundingMode.HALF_UP);
 }
 public BigDecimal historical(long journal,String currency,BigDecimal amount){
  if(currency.equals("CNY")||amount.signum()==0)return amount.setScale(2,RoundingMode.HALF_UP);
  var rates=jdbc.queryForList("select cny_per_unit from fx_journal_rates where journal_id=? and currency=?",BigDecimal.class,journal,currency);
  return rates.isEmpty()?null:amount.multiply(rates.get(0)).setScale(2,RoundingMode.HALF_UP);
 }
 public BigDecimal sourceRate(long household,String type,long source,String currency){
  if(currency.equals("CNY"))return BigDecimal.ONE;
  return jdbc.queryForList("""
    select r.cny_per_unit from ledger_sources s join fx_journal_rates r on r.journal_id=s.current_journal_id
    where s.household_id=? and s.source_type=? and s.source_id=? and r.currency=?
    """,BigDecimal.class,household,type,source,currency).stream().findFirst().orElse(null);
 }
 public Rate sourceReference(long household,String type,long source,String currency){
  if(currency.equals("CNY"))return new Rate(0,null,BigDecimal.ONE);
  return jdbc.query("select b.id,b.effective_on,r.cny_per_unit from ledger_sources s join fx_journal_rates r on r.journal_id=s.current_journal_id join fx_rate_batches b on b.id=r.batch_id where s.household_id=? and s.source_type=? and s.source_id=? and r.currency=?",
     (rs,n)->new Rate(rs.getLong(1),rs.getObject(2,LocalDate.class),rs.getBigDecimal(3)),household,type,source,currency).stream().findFirst().orElse(null);
 }
 @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
 public void bind(long journal,LocalDate day,Collection<String> currencies,Long reverses){
  for(String currency:new HashSet<>(currencies)){
   if(currency.equals("CNY"))continue;
   Rate rate;
   if(reverses!=null)rate=jdbc.query("select r.batch_id,b.effective_on,r.cny_per_unit from fx_journal_rates r join fx_rate_batches b on b.id=r.batch_id where r.journal_id=? and r.currency=?",
      (rs,n)->new Rate(rs.getLong(1),rs.getObject(2,LocalDate.class),rs.getBigDecimal(3)),reverses,currency).stream().findFirst().orElse(null);
   else rate=reference(currency,day);
   if(rate==null)continue;
   jdbc.update("insert into fx_journal_rates(journal_id,currency,batch_id,cny_per_unit) select ?,?,?,? where not exists(select 1 from fx_journal_rates where journal_id=? and currency=?)",
     journal,currency,rate.batchId(),rate.value(),journal,currency);
  }
 }
 @Transactional
 public void backfill(){
  var pending=jdbc.query("""
   select distinct j.id,j.effective_on,j.reverses_journal_id,e.currency from ledger_journals j
   join ledger_entries e on e.journal_id=j.id where e.currency<>'CNY'
   and not exists(select 1 from fx_journal_rates r where r.journal_id=j.id and r.currency=e.currency)
   and ((j.reverses_journal_id is not null and exists(select 1 from fx_journal_rates original where original.journal_id=j.reverses_journal_id and original.currency=e.currency))
     or (j.reverses_journal_id is null and exists(select 1 from fx_rate_batches b join fx_rates r on r.batch_id=b.id where b.source='ECB' and b.effective_on<=j.effective_on and r.currency=e.currency)))
   order by j.id limit 500
   """,(rs,n)->new Pending(rs.getLong(1),rs.getObject(2,LocalDate.class),rs.getObject(3,Long.class),rs.getString(4)));
  for(var row:pending){jdbc.queryForObject("select id from ledger_journals where id=? for update",Long.class,row.id());bind(row.id(),row.day(),List.of(row.currency()),row.reverses());}
 }
 private record Pending(long id,LocalDate day,Long reverses,String currency){}
}
