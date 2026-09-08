package com.familyfinance.investment;

import com.familyfinance.accounting.*;
import com.familyfinance.ledger.FinancialAccountRepository;
import com.familyfinance.shared.ResourceConflictException;
import com.familyfinance.shared.ResourceNotFoundException;
import java.util.ArrayList;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.familyfinance.accounting.LedgerAccountKind.*;

/** Posting adapter; position chronology is validated by the owning trade transaction. */
@Service @Transactional
public class InvestmentAccountingService {
    private final LedgerPostingService posting;
    private final CashAccountingService cash;
    private final FinancialAccountRepository accounts;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public InvestmentAccountingService(LedgerPostingService posting,CashAccountingService cash,FinancialAccountRepository accounts,org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.posting=posting;this.cash=cash;this.accounts=accounts;this.jdbc=jdbc;
    }
    public void requireBalance(long h,long account,long security,long expected) {
        var values=jdbc.queryForList("select balance_cents from ledger_accounts where household_id=? and account_code=? for update",Long.class,h,"POSITION:"+account+":"+security);
        long actual=values.isEmpty()?0:values.get(0);
        if(actual!=expected)throw new ResourceConflictException("ACCOUNTING_BALANCE_MISMATCH","持仓成本与账务余额不一致，请先核对账务");
    }
    public void post(InvestmentTrade trade,InvestmentPosition before,InvestmentPosition after,long actor,String key,boolean replace) {
        long h=trade.getHousehold().getId();
        String currency=trade.getAccount().getCurrency();
        if(replace)cash.requireEditableSourceCash(h,"INVESTMENT_TRADE",trade.getId());
        Long cashId=trade.getType()==InvestmentTradeType.OPENING?null:trade.getCashAccountId();
        if(trade.getType()!=InvestmentTradeType.OPENING) {
            if(cashId==null)throw new ResourceConflictException("INVESTMENT_FUNDING_REQUIRED","请先为投资账户选择已确认期初的资金账户");
            var account=accounts.findLockedByIdAndHouseholdId(cashId,h).orElseThrow(()->new ResourceNotFoundException("资金账户不存在"));
            cash.requireConfirmed(account,trade.getTradedOn());
            if(!currency.equals(account.getCurrency()))throw new ResourceConflictException("CURRENCY_MISMATCH","资金账户与投资账户币种不一致");
        }
        var entries=new ArrayList<LedgerEntryInput>();
        long costDelta=Math.subtractExact(after.costCents(),before.costCents());
        long cashDelta=Math.subtractExact(after.cashImpactCents(),before.cashImpactCents());
        long profit=Math.subtractExact(after.realizedProfitCents(),before.realizedProfitCents());
        add(entries,"POSITION:"+trade.getAccount().getId()+":"+trade.getSecurity().getId(),ASSET,costDelta,currency);
        if(cashId!=null)add(entries,"CASH:"+cashId,CASH,cashDelta,currency);
        if(trade.getType()==InvestmentTradeType.OPENING)add(entries,LedgerCodes.inCurrency("EQUITY:OPENING",currency),EQUITY,-costDelta,currency);
        else if(profit>0)add(entries,LedgerCodes.inCurrency("INCOME:INVESTMENT_GAIN",currency),INCOME,-profit,currency);
        else if(profit<0)add(entries,LedgerCodes.inCurrency(trade.getType()==InvestmentTradeType.FEE?"EXPENSE:INVESTMENT_FEE":"EXPENSE:INVESTMENT_LOSS",currency),EXPENSE,-profit,currency);
        var command=new LedgerPostingCommand(h,"INVESTMENT_TRADE",trade.getId(),key,trade.getTradedOn(),actor,entries);
        if(replace)posting.replace(command);else posting.post(command);
    }
    public void reverse(InvestmentTrade trade,long actor,String key) {
        if(!trade.isAccountingConfirmed())throw new ResourceConflictException("ACCOUNTING_NOT_INITIALIZED","旧投资交易尚未确认账务，不能改写或冲销");
        cash.requireEditableSourceCash(trade.getHousehold().getId(),"INVESTMENT_TRADE",trade.getId());
        posting.reverse(trade.getHousehold().getId(),"INVESTMENT_TRADE",trade.getId(),key,actor);
    }
    private static void add(java.util.List<LedgerEntryInput> entries,String code,LedgerAccountKind kind,long debit,String currency) {
        if(debit!=0)entries.add(new LedgerEntryInput(code,kind,debit>0?debit:0,debit<0?Math.negateExact(debit):0,null,null,currency));
    }
}
