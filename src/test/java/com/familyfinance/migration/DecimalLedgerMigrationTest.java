package com.familyfinance.migration;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DecimalLedgerMigrationTest {
    @TempDir Path temp;

    @Test void populatedH2V21UpgradePreservesAmountsSourcesAndReceipts() throws Exception {
        verifyUpgrade(MigrationTestSupport.h2Url(temp.resolve("decimal")),"sa","","classpath:db/migration");
    }

    /** Opt-in only: the caller supplies an empty, disposable database; this test never cleans it. */
    @Test void populatedMySqlV21UpgradePreservesAmountsSourcesAndReceipts() throws Exception {
        String url=System.getenv("DECIMAL_MIGRATION_MYSQL_URL");
        assumeTrue(url!=null,"disposable MySQL migration database not configured");
        verifyUpgrade(url,System.getenv("DECIMAL_MIGRATION_MYSQL_USER"),System.getenv("DECIMAL_MIGRATION_MYSQL_PASSWORD"),"classpath:db/migration-mysql");
    }

    private void verifyUpgrade(String url,String user,String password,String location) throws Exception {
        var config=Flyway.configure().dataSource(url,user,password).locations(location);
        assertThat(config.load().info().applied()).as("migration fixture requires a fresh database").isEmpty();
        config.target("21").load().migrate();
        try(var c=DriverManager.getConnection(url,user,password)) {
            seed(c);
            var accounts=rows(c,"select household_id,account_code,kind,balance_cents from ledger_accounts order by account_code");
            var entries=rows(c,"select * from ledger_entries order by id");
            var journals=rows(c,"select * from ledger_journals order by id");
            var sources=rows(c,"select * from ledger_sources order by source_id");
            var latest=Flyway.configure().dataSource(url,user,password).locations(location).target("22").load();
            latest.migrate();
            assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("22");
            assertThat(rows(c,"select household_id,account_code,kind,balance_cents from ledger_accounts order by account_code")).isEqualTo(accounts);
            assertThat(rows(c,"select id,household_id,journal_id,line_no,account_code,debit_cents,credit_cents,category_id,member_id from ledger_entries order by id")).isEqualTo(entries);
            assertThat(rows(c,"select * from ledger_journals order by id")).isEqualTo(journals);
            assertThat(rows(c,"select * from ledger_sources order by source_id")).isEqualTo(sources);
            assertThat(decimal(c,"select balance_amount from ledger_accounts where account_code='CASH:1'")).isEqualByComparingTo("99.98");
            try(var rs=c.createStatement().executeQuery("select debit_amount,credit_amount,debit_cents,credit_cents from ledger_entries order by id")) {
                while(rs.next()) {
                    assertThat(rs.getBigDecimal(1).movePointRight(2).longValueExact()).isEqualTo(rs.getLong(3));
                    assertThat(rs.getBigDecimal(2).movePointRight(2).longValueExact()).isEqualTo(rs.getLong(4));
                }
            }
            assertRejected(c,"update ledger_accounts set balance_cents=42 where account_code='CASH:1'");
            assertRejected(c,"update ledger_entries set debit_cents=42 where id=1");
            assertRejected(c,"update ledger_accounts set balance_amount=-0.01 where account_code='CASH:1'");
            assertRejected(c,"update ledger_accounts set balance_amount=92233720368547758.08 where account_code='EQUITY:MAX'");
            assertRejected(c,"update ledger_accounts set balance_amount=-92233720368547758.09 where account_code='EQUITY:MIN'");
            assertRejected(c,"update ledger_entries set debit_amount=92233720368547758.08 where id=1");
            assertRejected(c,"update ledger_entries set credit_amount=0.01 where id=1");
            assertRejected(c,"update ledger_entries set debit_amount=null where id=1");
            execute(c,"update ledger_accounts set balance_amount=12.34 where account_code='CASH:1'");
            assertThat(decimal(c,"select balance_cents from ledger_accounts where account_code='CASH:1'")).isEqualByComparingTo("1234");
        }
    }

    private void seed(Connection c) throws SQLException {
        execute(c,"insert into households(id,name,created_at) values (1,'migration',current_timestamp)");
        execute(c,"insert into app_users(id,household_id,username,email,display_name,password_hash,created_at) values (1,1,'migration','migration@test.invalid','fixture','unused',current_timestamp)");
        execute(c,"insert into ledger_accounts(household_id,account_code,kind,balance_cents) values (1,'CASH:1','CASH',9998),(1,'EQUITY:OPENING','EQUITY',10001),(1,'EXPENSE:1','EXPENSE',3),(1,'EQUITY:MAX','EQUITY',9223372036854775807),(1,'EQUITY:MIN','EQUITY',-9223372036854775808)");
        for(int id=1;id<=4;id++) {
            String operation=id==3?"REVERSE":id==4?"REPLACE":"POST";
            execute(c,"insert into ledger_journals(id,household_id,source_type,source_id,revision,request_key,request_digest,operation,effective_on,recorded_at,actor_id,reverses_journal_id) values ("+id+",1,'FIXTURE',"+(id==1?1:2)+","+(id<=2?1:2)+",'request-"+id+"','"+"a".repeat(64)+"','"+operation+"','2026-01-01',current_timestamp,1,"+(id==3?"2":"null")+")");
        }
        execute(c,"insert into ledger_entries(id,household_id,journal_id,line_no,account_code,debit_cents,credit_cents) values (1,1,1,1,'CASH:1',10001,0),(2,1,1,2,'EQUITY:OPENING',0,10001),(3,1,2,1,'EXPENSE:1',2,0),(4,1,2,2,'CASH:1',0,2),(5,1,3,1,'EXPENSE:1',0,2),(6,1,3,2,'CASH:1',2,0),(7,1,4,1,'EXPENSE:1',3,0),(8,1,4,2,'CASH:1',0,3)");
        execute(c,"insert into ledger_sources(household_id,source_type,source_id,revision,current_journal_id) values (1,'FIXTURE',1,1,1),(1,'FIXTURE',2,2,4)");
    }
    private static List<List<String>> rows(Connection c,String sql) throws SQLException {
        var result=new ArrayList<List<String>>();
        try(var s=c.createStatement();var rs=s.executeQuery(sql)) {
            while(rs.next()) {
                var row=new ArrayList<String>();
                for(int i=1;i<=rs.getMetaData().getColumnCount();i++) row.add(rs.getString(i));
                result.add(row);
            }
        }
        return result;
    }
    private static BigDecimal decimal(Connection c,String sql) throws SQLException {
        try(var s=c.createStatement();var rs=s.executeQuery(sql)) { rs.next(); return rs.getBigDecimal(1); }
    }
    private static void execute(Connection c,String sql) throws SQLException { try(var s=c.createStatement()) { s.executeUpdate(sql); } }
    private static void assertRejected(Connection c,String sql) { assertThatThrownBy(()->execute(c,sql)).isInstanceOf(SQLException.class); }
}
