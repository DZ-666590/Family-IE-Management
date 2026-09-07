package com.familyfinance.accounting;

import com.familyfinance.category.TransactionKind;
import com.familyfinance.shared.ResourceConflictException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.math.BigInteger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Snapshot-safe reporting of the corrected effective ledger, never a funding authorization. */
@Service @Transactional(readOnly=true)
public class LedgerReportingService {
    private final JdbcTemplate jdbc;
    public LedgerReportingService(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public void requireComplete(long h) {
        long missing=missingCount(h);
        if(missing>0)throw new ResourceConflictException("ACCOUNTING_NOT_INITIALIZED","存在 "+missing+" 项未确认的期初或旧资金记录；请先核对期初与来源账务，当前报表不完整");
    }
    public boolean isComplete(long h){return missingCount(h)==0;}
    private long missingCount(long h) {
        long missing=0;
        missing+=count("select count(*) from financial_accounts where household_id=? and opening_confirmed=false",h);
        missing+=count("select count(*) from assets where household_id=? and accounting_mode is null",h);
        missing+=count("select count(*) from loans where household_id=? and funding_mode is null",h);
        missing+=count("select count(*) from investment_trades where household_id=? and accounting_confirmed=false",h);
        missing+=count("""
            select count(*) from financial_transactions t where t.household_id=? and not exists (
            select 1 from ledger_sources s where s.household_id=t.household_id and s.current_journal_id is not null and
            ((t.source_type in ('MANUAL','RECURRING') and s.source_type='TRANSACTION' and s.source_id=t.id)
            or (t.source_type in ('LOAN_PAYMENT','LOAN_PREPAYMENT') and s.source_type=t.source_type and s.source_id=t.source_id)))
            """,h);
        return missing;
    }
    private long count(String sql,long h){return jdbc.queryForObject(sql,Long.class,h);}

    public Map<String,Long> balancesAsOf(long h,LocalDate day) {
        Map<String,Long> result=new LinkedHashMap<>();
        jdbc.query("""
            select e.account_code,a.kind,e.debit_cents,e.credit_cents from ledger_entries e
            join ledger_journals j on j.id=e.journal_id and j.household_id=e.household_id
            join ledger_sources s on s.current_journal_id=j.id and s.household_id=j.household_id
            join ledger_accounts a on a.household_id=e.household_id and a.account_code=e.account_code
            where e.household_id=? and j.effective_on<=? order by j.effective_on,j.id,e.line_no
            """,rs->{
                String kind=rs.getString(2);
                long delta=Math.subtractExact(rs.getLong(3),rs.getLong(4));
                if(kind.equals("LOAN")||kind.equals("INCOME")||kind.equals("EQUITY"))delta=Math.negateExact(delta);
                result.merge(rs.getString(1),delta,Math::addExact);
            },h,day);
        return Map.copyOf(result);
    }

    public List<LedgerActivity> activities(long h,LocalDate from,LocalDate toExclusive) {
        requireComplete(h);
        return jdbc.query("""
            select e.id,j.effective_on,a.kind,e.debit_cents,e.credit_cents,e.account_code,
              c.id category_id,c.name category_name,p.id parent_id,p.name parent_name,
              m.id member_id,m.name member_name,j.source_type,j.source_id,t.note
            from ledger_entries e
            join ledger_journals j on j.id=e.journal_id and j.household_id=e.household_id
            join ledger_sources s on s.current_journal_id=j.id and s.household_id=j.household_id
            join ledger_accounts a on a.household_id=e.household_id and a.account_code=e.account_code
            left join categories c on c.id=e.category_id and c.household_id=e.household_id
            left join categories p on p.id=c.parent_id and p.household_id=c.household_id
            left join family_members m on m.id=e.member_id and m.household_id=e.household_id
            left join financial_transactions t on t.household_id=j.household_id and
              ((j.source_type='TRANSACTION' and t.id=j.source_id) or
               (j.source_type in ('LOAN_PAYMENT','LOAN_PREPAYMENT') and t.source_type=j.source_type and t.source_id=j.source_id))
            where e.household_id=? and j.effective_on>=? and j.effective_on<?
              and a.kind in ('INCOME','EXPENSE')
              and e.account_code not in ('INCOME:VALUATION_GAIN','EXPENSE:VALUATION_LOSS')
            order by j.effective_on,e.id
            """,(rs,n)->{
                var kind=TransactionKind.valueOf(rs.getString("kind"));
                long amount=kind==TransactionKind.INCOME?rs.getLong("credit_cents")-rs.getLong("debit_cents"):rs.getLong("debit_cents")-rs.getLong("credit_cents");
                Long category=rs.getObject("category_id",Long.class), parent=rs.getObject("parent_id",Long.class),member=rs.getObject("member_id",Long.class);
                String code=rs.getString("account_code");
                String label=code.equals("EXPENSE:INVESTMENT_FEE")?"投资费用":kind==TransactionKind.INCOME?"已实现收益":"已实现损失";
                boolean assetDisposal=rs.getString("source_type").equals("ASSET_DISPOSAL");
                if(assetDisposal)label=kind==TransactionKind.INCOME?"资产处置账面收益":"资产处置账面损失";
                var cat=new LedgerActivity.Dimension(category==null?(assetDisposal?-3:code.equals("EXPENSE:INVESTMENT_FEE")?-2:-1):category,category==null?label:rs.getString("category_name"),
                    parent==null?null:new LedgerActivity.Dimension(parent,rs.getString("parent_name"),null));
                var mem=new LedgerActivity.Dimension(member==null?0:member,member==null?"家庭共同":rs.getString("member_name"),null);
                return new LedgerActivity(rs.getLong("id"),rs.getObject("effective_on",LocalDate.class),kind,amount,cat,mem,rs.getString("note"),rs.getString("source_type"),rs.getLong("source_id"));
            },h,from,toExclusive);
    }

    public String sumBudgetExpenseCents(long h,LocalDate from,LocalDate to,String scope,Long category,Long member,boolean rollup) {
        BigInteger result=BigInteger.ZERO;
        for(var item:activities(h,from,to)) {
            if(item.kind()!=TransactionKind.EXPENSE)continue;
            if(scope.equals("CATEGORY")&&!(category!=null&&(item.category().id()==category||(rollup&&item.category().parent()!=null&&item.category().parent().id()==category))))continue;
            if(scope.equals("MEMBER")&&!(member!=null&&item.member().id()==member))continue;
            result=result.add(BigInteger.valueOf(item.amountCents()));
        }
        return result.toString();
    }

    public CashFlow cashFlow(long h,LocalDate from,LocalDate to) {
        requireComplete(h);
        long[] totals=new long[5];
        jdbc.query("""
            select a.kind,e.debit_cents,e.credit_cents,e.account_code from ledger_entries e
            join ledger_accounts a on a.household_id=e.household_id and a.account_code=e.account_code
            join ledger_journals j on j.id=e.journal_id and j.household_id=e.household_id
            join ledger_sources s on s.current_journal_id=j.id and s.household_id=j.household_id
            where e.household_id=? and j.effective_on>=? and j.effective_on<?
              and j.source_type not in ('CASH_OPENING','CASH_TRANSFER','LOAN_OPENING')
            """,rs->{
                String kind=rs.getString(1),code=rs.getString(4);
                long debit=rs.getLong(2),credit=rs.getLong(3);
                if(kind.equals("CASH")){totals[0]=Math.addExact(totals[0],debit);totals[1]=Math.addExact(totals[1],credit);}
                if(kind.equals("LOAN")){totals[2]=Math.addExact(totals[2],debit);totals[3]=Math.addExact(totals[3],credit);}
                if(code.equals("INCOME:VALUATION_GAIN"))totals[4]=Math.addExact(totals[4],credit-debit);
                if(code.equals("EXPENSE:VALUATION_LOSS"))totals[4]=Math.subtractExact(totals[4],debit-credit);
            },h,from,to);
        return new CashFlow(totals[0],totals[1],totals[2],totals[3],totals[4]);
    }
    public record CashFlow(long cashIn,long cashOut,long principalPaid,long borrowed,long noncashValuationChange){}
}
