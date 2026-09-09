package com.familyfinance.migration;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InvestmentPlanMigrationTest {
    @TempDir Path directory;
    @Test void upgradesExistingAccountingSchemaWithoutPostingMoney() {
        Path database=directory.resolve("investment-plans");
        MigrationResult before=MigrationTestSupport.migrateFreshDatabaseTo(database,"38");
        long journals=before.queryLong("select count(*) from ledger_journals");
        MigrationResult after=MigrationTestSupport.migrateExistingDatabase(database);
        assertThat(after.version()).isEqualTo("39");
        assertThat(after.tables()).contains("INVESTMENT_PLANS","INVESTMENT_PLAN_OCCURRENCES");
        assertThat(after.queryLong("select count(*) from ledger_journals")).isEqualTo(journals);
        assertThat(after.queryLong("select count(*) from investment_plan_occurrences")).isZero();
    }
}
