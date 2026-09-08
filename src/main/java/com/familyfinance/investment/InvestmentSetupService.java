package com.familyfinance.investment;

import com.familyfinance.family.CurrentMembership;
import com.familyfinance.family.FamilyMutationAuthorization;
import com.familyfinance.shared.ResourceConflictException;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class InvestmentSetupService {
    private final CurrentMembership currentMembership;
    private final FamilyMutationAuthorization authorization;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public InvestmentSetupService(
            CurrentMembership currentMembership, FamilyMutationAuthorization authorization,
            JdbcTemplate jdbc, Clock clock) {
        this.currentMembership = currentMembership;
        this.authorization = authorization;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public InvestmentSetupStatus status(Authentication authentication) {
        long householdId = currentMembership.require(authentication).householdId();
        return read(householdId);
    }

    @Transactional
    public InvestmentSetupStatus complete(Authentication authentication) {
        var access = authorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        InvestmentSetupStatus existing = read(householdId);
        if (existing.completed()) return existing;
        if (!hasFundedAccount(householdId)) {
            throw new ResourceConflictException(
                    "INVESTMENT_SETUP_ACCOUNT_REQUIRED", "请先创建或选择已关联有效资金账户的投资账户");
        }
        jdbc.update("""
                insert into investment_setup (household_id,completed_at,completed_by)
                select ?,?,? where not exists (
                    select 1 from investment_setup where household_id=?
                )
                """, householdId, Timestamp.from(clock.instant()), access.context().userId(), householdId);
        return read(householdId);
    }

    private InvestmentSetupStatus read(long householdId) {
        boolean hasAccounts = count("""
                select count(*) from investment_accounts
                where household_id=? and archived_at is null
                """, householdId) > 0;
        boolean hasTrades = count(
                "select count(*) from investment_trades where household_id=?", householdId) > 0;
        boolean completed = hasTrades || count(
                "select count(*) from investment_setup where household_id=?", householdId) > 0;
        return new InvestmentSetupStatus(completed, hasAccounts, hasTrades);
    }

    private boolean hasFundedAccount(long householdId) {
        return count("""
                select count(*)
                from investment_accounts investment
                join financial_accounts funding
                  on funding.id=investment.funding_account_id
                 and funding.household_id=investment.household_id
                where investment.household_id=?
                  and investment.archived_at is null
                  and funding.archived_at is null
                  and funding.opening_confirmed=true
                """, householdId) > 0;
    }

    private long count(String sql, long householdId) {
        return jdbc.queryForObject(sql, Long.class, householdId);
    }
}
