package com.familyfinance.investment;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvestmentTradeRepository
        extends JpaRepository<InvestmentTrade, Long>, JpaSpecificationExecutor<InvestmentTrade> {
    List<InvestmentTrade> findByHouseholdId(long householdId,org.springframework.data.domain.Pageable page);

    Optional<InvestmentTrade> findByIdAndHouseholdId(Long id, Long householdId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from InvestmentTrade t where t.id=:id and t.household.id=:household")
    Optional<InvestmentTrade> findCurrent(long id,long household);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from InvestmentTrade t where t.household.id=:household and t.account.id=:account and t.security.id=:security order by t.tradedOn,t.id")
    List<InvestmentTrade> currentHistory(long household,long account,long security);

    @Query("select t from InvestmentTrade t where t.household.id=:household and t.tradedOn<=:day order by t.account.id,t.security.id,t.tradedOn,t.id")
    List<InvestmentTrade> historyAsOf(long household,java.time.LocalDate day);

    List<InvestmentTrade> findByHouseholdIdAndAccountIdAndSecurityId(
            Long householdId, Long accountId, Long securityId, Sort sort);

    @Query("""
            select trade from InvestmentTrade trade join trade.account account
            where trade.household.id = :householdId and account.archivedAt is null
            order by trade.account.id, trade.security.id, trade.tradedOn, trade.id
            """)
    List<InvestmentTrade> findActiveAccountTradesByHouseholdId(@Param("householdId") Long householdId);
}
