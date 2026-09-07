package com.familyfinance.ledger;

import com.familyfinance.family.CurrentMembership;
import com.familyfinance.family.FamilyMutationAuthorization;
import com.familyfinance.shared.RequestValidationException;
import com.familyfinance.shared.ResourceConflictException;
import com.familyfinance.shared.ResourceNotFoundException;
import java.math.BigInteger;
import java.time.Clock;
import java.time.LocalDate;
import com.familyfinance.accounting.AccountingRequests;
import com.familyfinance.accounting.CashAccountingService;
import com.familyfinance.accounting.LedgerReadService;
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
public class AccountService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final BigInteger MAX_OPENING_BALANCE_CENTS = BigInteger.valueOf(99_999_999_999L);

    private final FinancialAccountRepository accounts;
    private final CurrentMembership currentMembership;
    private final FamilyMutationAuthorization mutationAuthorization;
    private final Clock clock;
    private final CashAccountingService cash;
    private final LedgerReadService ledger;
    private final AccountingRequests requests;

    public AccountService(
            FinancialAccountRepository accounts,
            CurrentMembership currentMembership,
            FamilyMutationAuthorization mutationAuthorization,
            Clock clock, CashAccountingService cash, LedgerReadService ledger, AccountingRequests requests) {
        this.accounts = accounts;
        this.currentMembership = currentMembership;
        this.mutationAuthorization = mutationAuthorization;
        this.clock = clock;
        this.cash=cash; this.ledger=ledger; this.requests=requests;
    }

    public AccountPage list(Authentication authentication, int page, int size) {
        long householdId = currentMembership.require(authentication).householdId();
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        var result = accounts.findByHouseholdIdAndArchivedAtIsNull(
                householdId,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "id")));
        return new AccountPage(
                result.getContent().stream().map(this::response).toList(),
                safePage,
                safeSize,
                result.getTotalElements(),
                result.getTotalPages(),
                result.hasNext());
    }

    public AccountResponse get(Authentication authentication, long accountId) {
        long householdId = currentMembership.require(authentication).householdId();
        return response(findOne(householdId, accountId));
    }

    @Transactional
    public AccountResponse create(Authentication authentication, AccountCreateRequest request) {
        return create(authentication,request,AccountingRequests.key(null));
    }

    @Transactional
    public AccountResponse create(Authentication authentication, AccountCreateRequest request, String key) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        key=AccountingRequests.key(key);
        String digest=requests.digest("ACCOUNT_CREATE",access.context().userId(),request);
        Long previous=requests.replay(access.context().householdId(),key,digest);
        if(previous!=null) return mutationResponse(accounts.findLockedByIdAndHouseholdId(previous,access.context().householdId())
            .orElseThrow(()->new ResourceNotFoundException("账户不存在")));
        Map<String, String> fields = new LinkedHashMap<>();
        String name = normalizeRequiredName(request == null ? null : request.name(), fields);
        AccountType type = requireType(request == null ? null : request.type(), fields);
        String currency = requireCurrency(request == null ? null : request.currency(), fields);
        Long openingBalance = parseOpeningBalance(request == null ? null : request.openingBalance(), fields);
        throwIfInvalid(fields);
        LocalDate openingOn=cash.date(request.openingOn(),"openingOn");
        validateUnique(access.context().householdId(), name, null);
        try {
            FinancialAccount account = accounts.saveAndFlush(new FinancialAccount(
                    access.household(), name, type, currency, openingBalance));
            cash.opening(account,openingBalance,openingOn,access.context().userId(),key);
            accounts.flush();
            requests.record(access.context().householdId(),key,digest,account.getId());
            return mutationResponse(account);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateName();
        }
    }

    @Transactional
    public AccountResponse update(Authentication authentication, long accountId, AccountPatchRequest request) {
        return update(authentication,accountId,request,AccountingRequests.key(null));
    }

    @Transactional
    public AccountResponse update(Authentication authentication, long accountId, AccountPatchRequest request,String key) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        FinancialAccount account = accounts.findLockedByIdAndHouseholdId(accountId,householdId)
                .orElseThrow(()->new ResourceNotFoundException("账户不存在"));
        key=AccountingRequests.key(key);
        String digest=requests.digest("ACCOUNT_UPDATE:"+accountId,access.context().userId(),request);
        if(requests.replay(householdId,key,digest)!=null) return mutationResponse(account);
        if (account.isArchived()) {
            throw new ResourceConflictException("ACCOUNT_ARCHIVED", "账户已归档");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        String name = request == null || request.name() == null
                ? account.getName()
                : normalizeRequiredName(request.name(), fields);
        AccountType type = request == null || request.type() == null ? account.getType() : request.type();
        String currency = request == null || request.currency() == null
                ? account.getCurrency()
                : requireCurrency(request.currency(), fields);
        Long openingBalance = request == null || request.openingBalance() == null
                ? account.getOpeningBalanceCents()
                : parseOpeningBalance(request.openingBalance(), fields);
        throwIfInvalid(fields);
        boolean openingChange=request!=null&&(request.openingBalance()!=null||request.openingOn()!=null);
        LocalDate openingOn=openingChange
            ?cash.date(request.openingOn()!=null?request.openingOn():account.getOpeningOn()==null?null:account.getOpeningOn().toString(),"openingOn")
            :account.getOpeningOn();
        validateUnique(householdId, name, accountId);
        try {
            account.update(name, type, currency, openingBalance);
            if(openingChange) cash.opening(account,openingBalance,openingOn,access.context().userId(),key);
            accounts.flush();
            requests.record(householdId,key,digest,accountId);
            return mutationResponse(account);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateName();
        }
    }

    @Transactional
    public void archive(Authentication authentication, long accountId) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        FinancialAccount account = accounts.findLockedByIdAndHouseholdId(accountId,householdId)
                .orElseThrow(()->new ResourceNotFoundException("账户不存在"));
        if (account.isArchived()) {
            return;
        }
        // An unknown opening cannot be hidden: reports require explicit initialization.
        cash.requireConfirmed(account);
        // Authorization holds the household write lock before this fresh balance read.
        if(cash.currentBalance(householdId,accountId)!=0)
            throw new ResourceConflictException("ACCOUNT_BALANCE_NOT_ZERO", "账户余额不为零，无法归档");
        if (accounts.countActiveRecurringReferences(householdId, accountId) > 0) {
            throw new ResourceConflictException("RESOURCE_IN_USE", "账户仍被有效周期规则使用，无法归档");
        }
        account.archive(clock.instant());
        accounts.flush();
    }

    private FinancialAccount findOne(long householdId, long accountId) {
        return accounts.findByIdAndHouseholdId(accountId, householdId)
                .orElseThrow(() -> new ResourceNotFoundException("账户不存在"));
    }

    private AccountResponse response(FinancialAccount account) {
        return AccountResponse.from(account,ledger.balance(account.getHousehold().getId(),"CASH:"+account.getId()));
    }
    private AccountResponse mutationResponse(FinancialAccount account) {
        return AccountResponse.from(account,cash.currentBalance(account.getHousehold().getId(),account.getId()));
    }

    private void validateUnique(long householdId, String name, Long accountId) {
        boolean duplicate = accountId == null
                ? accounts.existsByHouseholdIdAndName(householdId, name)
                : accounts.existsByHouseholdIdAndNameAndIdNot(householdId, name, accountId);
        if (duplicate) {
            throw duplicateName();
        }
    }

    private static String normalizeRequiredName(String rawName, Map<String, String> fields) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            fields.put("name", "账户名称不能为空");
        } else if (name.length() > 100) {
            fields.put("name", "账户名称长度不能超过 100 个字符");
        }
        return name;
    }

    private static AccountType requireType(AccountType type, Map<String, String> fields) {
        if (type == null) {
            fields.put("type", "账户类型不能为空");
        }
        return type;
    }

    private static String requireCurrency(String rawCurrency, Map<String, String> fields) {
        String currency = rawCurrency == null ? "" : rawCurrency.trim().toUpperCase(java.util.Locale.ROOT);
        if (!FinancialAccount.STAGE_TWO_CURRENCY.equals(currency)) {
            fields.put("currency", "第二阶段账户币种只能是 CNY");
        }
        return currency;
    }

    private static Long parseOpeningBalance(String rawAmount, Map<String, String> fields) {
        String amount = rawAmount == null ? "" : rawAmount.trim();
        if (!amount.matches("^-?\\d+(?:\\.\\d{1,2})?$")) {
            fields.put("openingBalance", "金额格式必须是最多两位小数的数字");
            return null;
        }
        boolean negative = amount.startsWith("-");
        if(negative) { fields.put("openingBalance","期初余额不能为负"); return null; }
        String unsigned = negative ? amount.substring(1) : amount;
        String[] parts = unsigned.split("\\.", -1);
        BigInteger cents = new BigInteger(parts[0]).multiply(BigInteger.valueOf(100));
        if (parts.length == 2) {
            cents = cents.add(BigInteger.valueOf(Long.parseLong(
                    parts[1].length() == 1 ? parts[1] + "0" : parts[1])));
        }
        if (cents.compareTo(MAX_OPENING_BALANCE_CENTS) > 0) {
            fields.put("openingBalance", "金额不能超过 999,999,999.99");
            return null;
        }
        return negative ? cents.negate().longValueExact() : cents.longValueExact();
    }

    private static void throwIfInvalid(Map<String, String> fields) {
        if (!fields.isEmpty()) {
            throw new RequestValidationException(fields);
        }
    }

    private static ResourceConflictException duplicateName() {
        return new ResourceConflictException("RESOURCE_CONFLICT", "同一家庭的账户名称不能重复");
    }
}
