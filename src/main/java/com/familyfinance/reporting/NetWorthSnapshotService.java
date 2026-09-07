package com.familyfinance.reporting;

import com.familyfinance.household.Household;
import com.familyfinance.household.HouseholdRepository;
import com.familyfinance.shared.ResourceNotFoundException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NetWorthSnapshotService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final HouseholdRepository households;
    private final NetWorthSnapshotRepository snapshots;
    private final NetWorthService netWorth;
    private final Clock clock;
    private final org.springframework.transaction.support.TransactionTemplate perHousehold;
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(NetWorthSnapshotService.class);

    public NetWorthSnapshotService(HouseholdRepository households, NetWorthSnapshotRepository snapshots,
            NetWorthService netWorth, Clock clock,org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.households = households;
        this.snapshots = snapshots;
        this.netWorth = netWorth;
        this.clock = clock;
        this.perHousehold=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.perHousehold.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.perHousehold.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public NetWorthSnapshot generate(long householdId, LocalDate snapshotOn) {
        Household household = households.findLockedById(householdId)
                .orElseThrow(() -> new ResourceNotFoundException("家庭不存在"));
        NetWorthResult result = netWorth.calculate(householdId, snapshotOn);
        NetWorthSnapshot snapshot = snapshots.findByHouseholdIdAndSnapshotOn(householdId, snapshotOn)
                .orElseGet(() -> snapshots.save(new NetWorthSnapshot(household, snapshotOn,
                        result.assetCents(), result.liabilityCents(), result.netWorthCents())));
        snapshot.update(result.assetCents(), result.liabilityCents(), result.netWorthCents());
        snapshot.recordBasis(result.investment());
        snapshots.flush();
        return snapshot;
    }

    @Scheduled(cron = "0 50 23 * * *", zone = "Asia/Shanghai")
    public void generateDaily() {
        LocalDate today = LocalDate.now(clock.withZone(SHANGHAI));
        for (Household household : households.findAll()) {
            try {
                perHousehold.execute(ignored->generate(household.getId(), today));
            } catch (RuntimeException failure) {
                // Each failed household has already rolled back; others continue independently.
                log.warn("Net worth snapshot skipped for household {} ({})",household.getId(),failure.getClass().getSimpleName());
            }
        }
    }

    @Transactional(readOnly = true)
    public List<NetWorthSnapshot> history(long householdId) {
        return snapshots.findTop24ByHouseholdIdOrderBySnapshotOnDescIdDesc(householdId).stream()
            .filter(value->"LEDGER_AS_OF".equals(value.getAccountingBasis())).toList();
    }
}
