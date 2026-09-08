package com.familyfinance.investment;

import com.familyfinance.family.CurrentMembership;
import com.familyfinance.family.FamilyMutationAuthorization;
import com.familyfinance.shared.RequestValidationException;
import com.familyfinance.shared.ResourceConflictException;
import com.familyfinance.shared.ResourceNotFoundException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class InvestmentAccountService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final Sort SORT = Sort.by(Sort.Direction.DESC, "id");

    private final InvestmentAccountRepository accounts;
    private final CurrentMembership currentMembership;
    private final FamilyMutationAuthorization mutationAuthorization;
    private final Clock clock;
    private final com.familyfinance.accounting.AccountingRequests requests;
    private final com.familyfinance.ledger.FinancialAccountRepository cashAccounts;
    private final com.familyfinance.accounting.CashAccountingService cash;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final jakarta.persistence.EntityManager entities;
    private final com.familyfinance.accounting.MultiCurrencyPolicy currencyPolicy;

    public InvestmentAccountService(
            InvestmentAccountRepository accounts,
            CurrentMembership currentMembership,
            FamilyMutationAuthorization mutationAuthorization,
            Clock clock, com.familyfinance.accounting.AccountingRequests requests,
            com.familyfinance.ledger.FinancialAccountRepository cashAccounts,
            com.familyfinance.accounting.CashAccountingService cash, org.springframework.jdbc.core.JdbcTemplate jdbc,
            jakarta.persistence.EntityManager entities,com.familyfinance.accounting.MultiCurrencyPolicy currencyPolicy) {
        this.accounts = accounts;
        this.currentMembership = currentMembership;
        this.mutationAuthorization = mutationAuthorization;
        this.clock = clock;
        this.requests=requests; this.cashAccounts=cashAccounts; this.cash=cash; this.jdbc=jdbc; this.entities=entities;
        this.currencyPolicy=currencyPolicy;
    }

    public InvestmentAccountPage list(
            Authentication authentication, InvestmentAccountStatus status, int page, int size) {
        long householdId = currentMembership.require(authentication).householdId();
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        var pageable = PageRequest.of(safePage, safeSize, SORT);
        var result = status == InvestmentAccountStatus.ARCHIVED
                ? accounts.findByHouseholdIdAndArchivedAtIsNotNull(householdId, pageable)
                : accounts.findByHouseholdIdAndArchivedAtIsNull(householdId, pageable);
        return new InvestmentAccountPage(
                result.getContent().stream().map(InvestmentAccountResponse::from).toList(),
                safePage, safeSize, result.getTotalElements(), result.getTotalPages(), result.hasNext());
    }

    public InvestmentAccountResponse get(Authentication authentication, long id) {
        long householdId = currentMembership.require(authentication).householdId();
        return InvestmentAccountResponse.from(findOne(householdId, id));
    }

    @Transactional
    public InvestmentAccountResponse create(Authentication authentication, InvestmentAccountCreateRequest request) {
        return create(authentication,request,com.familyfinance.accounting.AccountingRequests.key(null));
    }
    @Transactional
    public InvestmentAccountResponse create(Authentication authentication, InvestmentAccountCreateRequest request,String key) {
        var access = mutationAuthorization.requireAdmin(authentication);
        long h=access.context().householdId();
        String digest=requests.digest("INVESTMENT_ACCOUNT_CREATE",access.context().userId(),request);
        Long replay=requests.replay(h,key,digest);
        if(replay!=null)return InvestmentAccountResponse.from(findCurrent(h,replay));
        Map<String, String> fields = new LinkedHashMap<>();
        String name = required(request == null ? null : request.name(), 100, "name", "投资账户名称", fields);
        String broker = required(
                request == null ? null : request.brokerName(), 100, "brokerName", "券商名称", fields);
        String currency = request == null || request.currency() == null
                ? ""
                : request.currency().trim().toUpperCase(java.util.Locale.ROOT);
        currencyPolicy.validate(currency,fields);
        throwIfInvalid(fields);
        ensureUnique(access.context().householdId(), name, null);
        try {
            InvestmentAccount account=new InvestmentAccount(access.household(), name, broker, access.membership().getUser(),currency);
            if(request.fundingAccountId()!=null) { requireFunding(h,request.fundingAccountId(),currency); account.fundingAccount(request.fundingAccountId()); }
            accounts.saveAndFlush(account);
            requests.record(h,key,digest,account.getId());
            return InvestmentAccountResponse.from(account);
        } catch (DataIntegrityViolationException exception) {
            throw duplicate();
        }
    }

    @Transactional
    public InvestmentAccountResponse update(
            Authentication authentication, long id, InvestmentAccountPatchRequest request) {
        return update(authentication,id,request,com.familyfinance.accounting.AccountingRequests.key(null));
    }
    @Transactional
    public InvestmentAccountResponse update(Authentication authentication,long id,InvestmentAccountPatchRequest request,String key) {
        var access = mutationAuthorization.requireAdmin(authentication);
        long h=access.context().householdId();
        String digest=requests.digest("INVESTMENT_ACCOUNT_UPDATE:"+id,access.context().userId(),request);
        Long replay=requests.replay(h,key,digest);
        InvestmentAccount account = findCurrent(h, id);
        if(replay!=null)return InvestmentAccountResponse.from(account);
        if (account.isArchived()) throw archived();
        Map<String, String> fields = new LinkedHashMap<>();
        if (request != null && request.currency() != null) fields.put("currency", "账户币种创建后不可修改");
        if (request != null && request.createdBy() != null) fields.put("createdBy", "创建者不可修改");
        if (request != null && request.archivedAt() != null) fields.put("archivedAt", "归档状态只能通过删除操作修改");
        String name = request == null || request.name() == null
                ? account.getName()
                : required(request.name(), 100, "name", "投资账户名称", fields);
        String broker = request == null || request.brokerName() == null
                ? account.getBrokerName()
                : required(request.brokerName(), 100, "brokerName", "券商名称", fields);
        throwIfInvalid(fields);
        ensureUnique(access.context().householdId(), name, id);
        try {
            account.update(name, broker);
            if(request!=null&&request.fundingAccountId()!=null) {requireFunding(h,request.fundingAccountId(),account.getCurrency());account.fundingAccount(request.fundingAccountId());}
            accounts.flush();
            requests.record(h,key,digest,id);
            return InvestmentAccountResponse.from(account);
        } catch (DataIntegrityViolationException exception) {
            throw duplicate();
        }
    }

    @Transactional
    public void archive(Authentication authentication, long id) {
        archive(authentication,id,com.familyfinance.accounting.AccountingRequests.key(null));
    }
    @Transactional
    public void archive(Authentication authentication,long id,String key) {
        var access = mutationAuthorization.requireAdmin(authentication);
        long h=access.context().householdId();
        String digest=requests.digest("INVESTMENT_ACCOUNT_ARCHIVE:"+id,access.context().userId(),null);
        if(requests.replay(h,key,digest)!=null)return;
        InvestmentAccount account = findCurrent(h, id);
        var rows=jdbc.queryForList("select trade_type,quantity,accounting_confirmed from investment_trades where household_id=? and account_id=? order by traded_on,id for update",h,id);
        java.math.BigDecimal quantity=java.math.BigDecimal.ZERO;
        for(var row:rows) {
            if(!Boolean.TRUE.equals(row.get("accounting_confirmed")))throw new ResourceConflictException("ACCOUNTING_NOT_INITIALIZED","旧投资交易尚未确认账务，不能归档");
            String type=(String)row.get("trade_type");
            if(type.equals("OPENING")||type.equals("BUY"))quantity=quantity.add((java.math.BigDecimal)row.get("quantity"));
            if(type.equals("SELL"))quantity=quantity.subtract((java.math.BigDecimal)row.get("quantity"));
        }
        if(quantity.signum()!=0)throw new ResourceConflictException("INVESTMENT_HOLDING_NOT_ZERO","请先卖出持仓，再归档投资账户");
        var costs=jdbc.queryForList("select balance_cents from ledger_accounts where household_id=? and account_code like ? for update",Long.class,h,"POSITION:"+id+":%");
        if(costs.stream().anyMatch(value->value!=0))throw new ResourceConflictException("ACCOUNTING_BALANCE_MISMATCH","清仓数量与账务持仓成本不一致，请先核对来源");
        account.archive(clock.instant());
        accounts.flush();
        requests.record(h,key,digest,id);
    }

    InvestmentAccount findCurrent(long h,long id) {
        var account=accounts.findCurrent(id,h).orElseThrow(()->new ResourceNotFoundException("投资账户不存在"));
        entities.detach(account);
        return accounts.findCurrent(id,h).orElseThrow();
    }
    private void requireFunding(long h,long id,String currency) {
        var account=cashAccounts.findLockedByIdAndHouseholdId(id,h).orElseThrow(()->new ResourceNotFoundException("资金账户不存在"));
        cash.requireConfirmed(account);
        if(!currency.equals(account.getCurrency()))throw new RequestValidationException(Map.of("fundingAccountId","投资账户与资金账户的币种必须一致"));
    }

    InvestmentAccount findOne(long householdId, long id) {
        return accounts.findByIdAndHouseholdId(id, householdId)
                .orElseThrow(() -> new ResourceNotFoundException("投资账户不存在"));
    }

    private void ensureUnique(long householdId, String name, Long id) {
        boolean exists = id == null
                ? accounts.existsByHouseholdIdAndName(householdId, name)
                : accounts.existsByHouseholdIdAndNameAndIdNot(householdId, name, id);
        if (exists) throw duplicate();
    }

    private static String required(
            String raw, int max, String field, String label, Map<String, String> fields) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) fields.put(field, label + "不能为空");
        else if (value.length() > max) fields.put(field, label + "长度不能超过 " + max + " 个字符");
        return value;
    }

    private static void throwIfInvalid(Map<String, String> fields) {
        if (!fields.isEmpty()) throw new RequestValidationException(fields);
    }

    private static ResourceConflictException duplicate() {
        return new ResourceConflictException("RESOURCE_CONFLICT", "同一家庭的投资账户名称不能重复");
    }

    private static ResourceConflictException archived() {
        return new ResourceConflictException("INVESTMENT_ACCOUNT_ARCHIVED", "投资账户已归档");
    }
}
