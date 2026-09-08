package com.familyfinance.fx;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FrankfurterRateProviderTest {
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
