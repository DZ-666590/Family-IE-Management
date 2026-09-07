package com.familyfinance.asset;

import com.familyfinance.loan.Loan;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Domain metadata for a purchase whose single economic journal belongs to its loan. */
@Service
public class LoanPurchasedAssetService {
    private final AssetRepository assets;
    private final AssetValuationRepository valuations;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public LoanPurchasedAssetService(AssetRepository assets,AssetValuationRepository valuations,JdbcTemplate jdbc,Clock clock){
        this.assets=assets;this.valuations=valuations;this.jdbc=jdbc;this.clock=clock;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Asset create(Loan loan,LocalDate day){
        AssetType type=switch(loan.getType()){case MORTGAGE->AssetType.PROPERTY;case CAR->AssetType.VEHICLE;case OTHER->AssetType.OTHER;};
        String prefix=switch(type){case PROPERTY->"房产";case VEHICLE->"车辆";case OTHER->"其他资产";};
        // Household mutation lock is held. Current names include archived and manually named assets.
        var names=new HashSet<>(jdbc.queryForList("select name from assets where household_id=? for update",String.class,loan.getHousehold().getId()));
        int number=1;while(names.contains(prefix+number))number++;
        var asset=new Asset(loan.getHousehold(),prefix+number,type,null,day,loan.getPrincipalCents(),loan.getPrincipalCents(),loan.getCreatedBy());
        asset.purchasedWithLoan(loan.getId());
        asset.initialize(AssetAccountingMode.FINANCED_PURCHASE,day,loan.getPrincipalCents(),null);
        assets.saveAndFlush(asset);
        valuations.saveAndFlush(new AssetValuation(loan.getHousehold(),asset,day,loan.getPrincipalCents(),AssetValuationSource.PURCHASE,null,loan.getCreatedBy(),clock.instant()));
        return asset;
    }
}
