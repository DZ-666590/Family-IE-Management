package com.familyfinance.investment;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestmentAccountRepository extends JpaRepository<InvestmentAccount, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select a from InvestmentAccount a where a.id=:id and a.household.id=:household")
    Optional<InvestmentAccount> findCurrent(long id,long household);

    Optional<InvestmentAccount> findByIdAndHouseholdId(Long id, Long householdId);

    Page<InvestmentAccount> findByHouseholdIdAndArchivedAtIsNull(Long householdId, Pageable pageable);

    Page<InvestmentAccount> findByHouseholdIdAndArchivedAtIsNotNull(Long householdId, Pageable pageable);

    boolean existsByHouseholdIdAndName(Long householdId, String name);

    boolean existsByHouseholdIdAndNameAndIdNot(Long householdId, String name, Long id);
}
