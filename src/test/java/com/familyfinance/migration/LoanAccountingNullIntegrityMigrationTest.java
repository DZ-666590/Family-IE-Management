package com.familyfinance.migration;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoanAccountingNullIntegrityMigrationTest {
    @TempDir Path tempDir;
    private MigrationResult fixture(String name, boolean latest) {
        Path db=StageOneDatabaseFixture.create(tempDir.resolve(name));
        var result=MigrationTestSupport.migrateExistingDatabaseTo(db,"17");
        result.executeUpdate("insert into loans(household_id,name,loan_type,payment_account_id,payment_category_id,principal_cents,annual_rate,term_months,repayment_method,start_on,current_principal_cents,status,created_by) "
            +"select 1,'Legacy','OTHER',min(account_id),1,10000,0,1,'EQUAL_PAYMENT','2026-01-01',10000,'ACTIVE',1 from financial_transactions");
        return latest?MigrationTestSupport.migrateExistingDatabase(db):result;
    }
    @Test void legacyAndFullyValidLoanTuplesSurviveButEveryPartialNullShapeIsRejected() {
        var db=fixture("loan-tuples",true);
        long cash=db.queryLong("select min(account_id) from financial_transactions");
        assertThat(db.queryLong("select count(*) from loans where funding_mode is null and accounting_on is null and disbursement_account_id is null")).isEqualTo(1);
        for(String valid:new String[]{"funding_mode='OPENING',accounting_on='2026-01-01',disbursement_account_id=null", "funding_mode='DISBURSEMENT',accounting_on='2026-01-01',disbursement_account_id="+cash})
            db.executeUpdate("update loans set "+valid);
        db.executeUpdate("update loans set funding_mode=null,accounting_on=null,disbursement_account_id=null");
        for(String invalid:new String[]{
            "funding_mode=null,accounting_on='2026-01-01',disbursement_account_id=null",
            "funding_mode=null,accounting_on=null,disbursement_account_id="+cash,
            "funding_mode=null,accounting_on='2026-01-01',disbursement_account_id="+cash,
            "funding_mode='OPENING',accounting_on=null,disbursement_account_id=null",
            "funding_mode='DISBURSEMENT',accounting_on='2026-01-01',disbursement_account_id=null"})
            assertThatThrownBy(()->db.executeUpdate("update loans set "+invalid)).isInstanceOf(IllegalStateException.class);
    }
    @Test void legacyAndValidPaymentSplitsSurviveButPartialNullAndInvalidAllocationsAreRejected() {
        var db=fixture("payment-splits",true);
        db.executeUpdate("update financial_transactions set source_type='LOAN_PAYMENT',source_id=1,loan_principal_cents=1,loan_interest_cents=0 where id=1");
        for(String invalid:new String[]{"loan_principal_cents=null,loan_interest_cents=0", "loan_principal_cents=1,loan_interest_cents=null", "loan_principal_cents=0,loan_interest_cents=1", "loan_principal_cents=1,loan_interest_cents=1", "source_type='MANUAL',loan_principal_cents=1,loan_interest_cents=0"})
            assertThatThrownBy(()->db.executeUpdate("update financial_transactions set "+invalid+" where id=1")).isInstanceOf(IllegalStateException.class);
        db.executeUpdate("update financial_transactions set loan_principal_cents=null,loan_interest_cents=null where id=1");
        assertThat(db.queryLong("select count(*) from financial_transactions where loan_principal_cents is null and loan_interest_cents is null")).isEqualTo(12);
    }
    @Test void migrationRejectsAlreadyCorruptedRowsWithoutGuessingARepair() {
        var db=fixture("existing-corruption",false);
        db.executeUpdate("update loans set accounting_on='2026-01-01'");
        assertThatThrownBy(()->MigrationTestSupport.migrateExistingDatabase(tempDir.resolve("existing-corruption")))
            .isInstanceOf(org.flywaydb.core.api.FlywayException.class);
        assertThat(db.queryLong("select count(*) from loans where funding_mode is null and accounting_on is not null")).isEqualTo(1);
    }
}
