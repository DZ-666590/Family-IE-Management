package com.familyfinance.asset;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetRepository extends JpaRepository<Asset, Long> {

    Optional<Asset> findByIdAndHouseholdId(Long id, Long householdId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select a from Asset a where a.id=:id and a.household.id=:household")
    Optional<Asset> findCurrent(long id,long household);

    Page<Asset> findByHouseholdIdAndStatus(Long householdId, AssetStatus status, Pageable pageable);

    Page<Asset> findByHouseholdIdAndTypeAndStatus(
            Long householdId, AssetType type, AssetStatus status, Pageable pageable);
    java.util.List<Asset> findAllByHouseholdIdAndStatus(Long householdId, AssetStatus status);
}
