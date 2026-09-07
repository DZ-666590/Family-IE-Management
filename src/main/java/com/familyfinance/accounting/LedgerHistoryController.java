package com.familyfinance.accounting;

import com.familyfinance.shared.ApiEnvelope;
import com.familyfinance.shared.CurrentHousehold;
import com.familyfinance.shared.Money;
import java.util.ArrayList;
import java.util.List;
import java.time.LocalDate;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

/** Source history is deliberately read-only; no arbitrary journal posting route exists. */
@RestController @RequestMapping("/api/accounting/history") @Transactional(readOnly=true)
public class LedgerHistoryController {
    private final CurrentHousehold household;
    private final JdbcTemplate jdbc;
    public LedgerHistoryController(CurrentHousehold household,JdbcTemplate jdbc){this.household=household;this.jdbc=jdbc;}
    @GetMapping
    ApiEnvelope<HistoryPage> history(Authentication auth,@RequestParam(required=false) String sourceType,
            @RequestParam(required=false) Long sourceId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        long h=household.id(auth); int p=Math.max(0,page),limit=Math.min(50,Math.max(1,size));
        String where=" where j.household_id=?";
        List<Object> args=new ArrayList<>();args.add(h);
        if(sourceType!=null){where+=" and j.source_type=?";args.add(sourceType);}
        if(sourceId!=null){where+=" and j.source_id=?";args.add(sourceId);}
        long total=jdbc.queryForObject("select count(*) from ledger_journals j"+where,Long.class,args.toArray());
        List<Object> paged=new ArrayList<>(args);paged.add(limit);paged.add((long)p*limit);
        var items=jdbc.query("select j.* from ledger_journals j"+where+" order by j.id desc limit ? offset ?",(rs,n)->{
            long id=rs.getLong("id");
            var legs=jdbc.query("select * from ledger_entries where household_id=? and journal_id=? order by line_no",(entry,i)->
                new Leg(entry.getString("account_code"),Money.formatCents(entry.getLong("debit_cents")),Money.formatCents(entry.getLong("credit_cents")),
                    entry.getObject("category_id",Long.class),entry.getObject("member_id",Long.class)),h,id);
            return new Journal(id,rs.getString("source_type"),rs.getLong("source_id"),rs.getLong("revision"),rs.getString("operation"),
                rs.getObject("effective_on",LocalDate.class),rs.getTimestamp("recorded_at").toInstant(),rs.getLong("actor_id"),rs.getObject("reverses_journal_id",Long.class),legs);
        },paged.toArray());
        return ApiEnvelope.data(new HistoryPage(items,p,limit,total,(int)((total+limit-1)/limit),(long)(p+1)*limit<total));
    }
    public record Leg(String accountCode,String debit,String credit,Long categoryId,Long memberId){}
    public record Journal(long journalId,String sourceType,long sourceId,long revision,String operation,LocalDate effectiveOn,Instant recordedAt,long actorId,Long reversesJournalId,List<Leg> legs){}
    public record HistoryPage(List<Journal> items,int page,int size,long totalElements,int totalPages,boolean hasNext){}
}
