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
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
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
        if(result.assetCents()==null||result.netWorthCents()==null)throw new com.familyfinance.shared.ResourceConflictException("FX_RATE_MISSING","缺少汇率，暂不写入不完整的净资产快照");
        NetWorthSnapshot snapshot = snapshots.findByHouseholdIdAndSnapshotOn(householdId, snapshotOn)
                .orElseGet(() -> snapshots.save(new NetWorthSnapshot(household, snapshotOn,
                        result.assetCents(), result.liabilityCents(), result.netWorthCents())));
        recordVersion(snapshot);
        snapshot.update(result.assetCents(), result.liabilityCents(), result.netWorthCents());
        snapshot.recordBasis(result.investment());
        snapshots.flush();
        recordVersion(snapshot);
        return snapshot;
    }
    private void recordVersion(NetWorthSnapshot snapshot){
        if(snapshot.getId()==null)return;
        var prior=jdbc.query("select asset_cents,liability_cents,net_worth_cents from net_worth_snapshot_revisions where snapshot_id=? order by id desc limit 1",
                (rs,n)->java.util.List.of(rs.getLong(1),rs.getLong(2),rs.getLong(3)),snapshot.getId());
        var value=java.util.List.of(snapshot.getAssetCents(),snapshot.getLiabilityCents(),snapshot.getNetWorthCents());
        if(!prior.isEmpty()&&prior.get(0).equals(value))return;
        jdbc.update("insert into net_worth_snapshot_revisions(snapshot_id,asset_cents,liability_cents,net_worth_cents,recorded_at) values(?,?,?,?,?)",
                snapshot.getId(),snapshot.getAssetCents(),snapshot.getLiabilityCents(),snapshot.getNetWorthCents(),java.sql.Timestamp.from(clock.instant()));
    }
    public record Revision(long id,String asset,String liability,String netWorth,java.time.Instant recordedAt){}
    @Transactional(readOnly=true)
    public List<Revision> revisions(long h,LocalDate day){
        return jdbc.query("select r.* from net_worth_snapshot_revisions r join net_worth_snapshots s on s.id=r.snapshot_id where s.household_id=? and s.snapshot_on=? order by r.id desc limit 50",
                (rs,n)->new Revision(rs.getLong("id"),com.familyfinance.shared.Money.formatCents(rs.getLong("asset_cents")),com.familyfinance.shared.Money.formatCents(rs.getLong("liability_cents")),com.familyfinance.shared.Money.formatCents(rs.getLong("net_worth_cents")),rs.getTimestamp("recorded_at").toInstant()),h,day);
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

    @Transactional(readOnly = true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<NetWorthSnapshotResponse> history(long householdId) {
        LocalDate today = LocalDate.now(clock.withZone(SHANGHAI));
        return snapshots.findTop24ByHouseholdIdOrderBySnapshotOnDescIdDesc(householdId).stream()
            .filter(value->"LEDGER_AS_OF".equals(value.getAccountingBasis()) && !value.getSnapshotOn().isAfter(today))
            // Stored rows are provenance, not corrected effective-date values. Never modify them on GET.
            .map(value->NetWorthSnapshotResponse.from(value,netWorth.calculate(householdId,value.getSnapshotOn())))
            .toList();
    }
}
