package com.familyfinance.fx;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FrankfurterRateProviderTest {
    @Test void truncatedRangeCannotClaimCoverageOfUnsupportedLeadingDates() {
        var from=LocalDate.of(2026,8,1);var to=LocalDate.of(2026,8,3);
        var provider=new FrankfurterRateProvider(){
            @Override String request(String dates){
                if(dates.startsWith("date="))throw new IllegalArgumentException("Requested historical date unavailable");
                return "[{\"date\":\"2026-08-03\",\"base\":\"CNY\",\"quote\":\"USD\",\"rate\":0.125},{\"date\":\"2026-08-03\",\"base\":\"CNY\",\"quote\":\"HKD\",\"rate\":1.25}]";
            }
        };
        try{assertThatThrownBy(()->provider.fetchRange(from,to)).isInstanceOf(IllegalArgumentException.class);}
        finally{provider.close();}
    }
    @Test void rangeParsesAtomicPairsAndRejectsMissingPairsAndDatesOutsideBounds() {
        String pair="[{\"date\":\"2026-08-03\",\"base\":\"CNY\",\"quote\":\"USD\",\"rate\":0.125},{\"date\":\"2026-08-03\",\"base\":\"CNY\",\"quote\":\"HKD\",\"rate\":1.25}]";
        var from=LocalDate.of(2026,8,1);var to=LocalDate.of(2026,8,30);
        var batches=FrankfurterRateProvider.parseRange(pair,from,to);
        assertThat(batches).hasSize(1);
        assertThat(batches.get(0).cnyPerUnit().get("USD")).isEqualByComparingTo("8");
        for(String bad:new String[]{pair.replace("HKD","USD"),pair.replace("2026-08-03","2026-07-31"),pair.replace("1.25","0"),"[]"})
            assertThatThrownBy(()->FrankfurterRateProvider.parseRange(bad,from,to)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->FrankfurterRateProvider.parseRange(pair,from,from.plusDays(90))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void dividesCnyBaseQuotesInsteadOfTreatingThemAsCnyPerForeignUnit() {
        var batch=FrankfurterRateProvider.parse("""
            [{"date":"2026-09-07","base":"CNY","quote":"USD","rate":0.125},
             {"date":"2026-09-07","base":"CNY","quote":"HKD","rate":1.25}]
            """,LocalDate.of(2026,9,8));
        assertThat(batch.cnyPerUnit().get("USD")).isEqualByComparingTo("8");
        assertThat(batch.cnyPerUnit().get("HKD")).isEqualByComparingTo("0.8");
    }
    @Test void rejectsMismatchedDaysWrongBaseDuplicatesFutureAndInvalidRates() {
        String valid="[{\"date\":\"2026-09-07\",\"base\":\"CNY\",\"quote\":\"USD\",\"rate\":0.125},{\"date\":\"2026-09-07\",\"base\":\"CNY\",\"quote\":\"HKD\",\"rate\":1.25}]";
        for(String bad:new String[]{valid.replace("1.25","0"),valid.replace("\"HKD\"","\"USD\""),valid.replace("\"CNY\"","\"USD\""),valid.replace("2026-09-07","2026-09-09"),valid.replace("\"quote\":\"HKD\"","\"quote\":\"HKD\",\"date\":\"2026-09-08\"")})
            assertThatThrownBy(()->FrankfurterRateProvider.parse(bad,LocalDate.of(2026,9,8))).isInstanceOf(IllegalArgumentException.class);
    }
}
