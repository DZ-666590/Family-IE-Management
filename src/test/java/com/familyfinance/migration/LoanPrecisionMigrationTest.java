package com.familyfinance.migration;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.flywaydb.core.Flyway;

class LoanPrecisionMigrationTest {
 @TempDir Path temp;
 @Test void populatedMySqlV22UpgradePreservesLegacyAmountsAndMetadataIsUnknown()throws Exception{
  String url=System.getenv("LOAN_PRECISION_MYSQL_URL");
  org.junit.jupiter.api.Assumptions.assumeTrue(url!=null,"disposable MySQL migration database not configured");
  String user=System.getenv("LOAN_PRECISION_MYSQL_USER"),password=System.getenv("LOAN_PRECISION_MYSQL_PASSWORD");
  var config=Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration-mysql");
  assertThat(config.load().info().applied()).as("requires a fresh disposable schema").isEmpty();
  config.target("22").load().migrate();
  try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){
   for(String sql:List.of(
    "insert into households(id,name,created_at) values(1,'precision migration',current_timestamp)",
    "insert into app_users(id,household_id,username,email,display_name,password_hash,created_at) values(1,1,'precision','precision@test.invalid','Fixture','unused',current_timestamp)",
    "insert into categories(id,household_id,kind,name,color,is_default,created_at) values(1,1,'EXPENSE','Loan','#123456',false,current_timestamp)",
    "insert into financial_accounts(id,household_id,name,type,currency) values(1,1,'Fixture','BANK','CNY')",
    "insert into loans(id,household_id,name,loan_type,payment_account_id,payment_category_id,principal_cents,annual_rate,term_months,repayment_method,start_on,current_principal_cents,status,created_by) values(1,1,'Legacy','OTHER',1,1,10000,0.12,3,'EQUAL_PAYMENT','2026-01-01',9000,'ACTIVE',1)",
    "insert into loan_installments(id,household_id,loan_id,installment_no,due_on,principal_cents,interest_cents,status) values(1,1,1,1,'2026-02-01',3000,100,'CANCELLED'),(2,1,1,2,'2026-03-01',4500,55,'PENDING'),(3,1,1,3,'2026-04-01',4500,25,'PENDING')",
    "insert into loan_prepayments(id,household_id,loan_id,request_key,amount_cents,paid_on,created_at) values(1,1,1,'original-key',1000,'2026-01-02',current_timestamp)",
    "insert into ledger_accounts(household_id,account_code,kind,balance_amount) values(1,'LOAN:1','LOAN',100),(1,'EQUITY:OPENING','EQUITY',100)",
    "insert into ledger_journals(id,household_id,source_type,source_id,revision,request_key,request_digest,operation,effective_on,recorded_at,actor_id) values(1,1,'LOAN_OPENING',1,1,'legacy-key','"+"a".repeat(64)+"','POST','2026-01-01',current_timestamp,1)",
    "insert into ledger_entries(id,household_id,journal_id,line_no,account_code,debit_amount,credit_amount) values(1,1,1,1,'EQUITY:OPENING',100,0),(2,1,1,2,'LOAN:1',0,100)",
    "insert into ledger_sources(household_id,source_type,source_id,revision,current_journal_id) values(1,'LOAN_OPENING',1,1,1)"))s.executeUpdate(sql);
   var queries=List.of("select id,principal_cents,current_principal_cents,status,start_on,annual_rate from loans order by id","select id,loan_id,installment_no,due_on,principal_cents,interest_cents,status from loan_installments order by id","select id,loan_id,request_key,amount_cents,interest_cents,paid_on,operation_kind from loan_prepayments order by id","select * from ledger_journals order by id","select * from ledger_entries order by id","select * from ledger_sources order by household_id,source_type,source_id");
   var snapshots=new ArrayList<List<List<String>>>();for(var q:queries)snapshots.add(rows(c,q));
   var latest=Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration-mysql").load();latest.migrate();
   assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("23");
   for(int i=0;i<queries.size();i++)assertThat(rows(c,queries.get(i))).isEqualTo(snapshots.get(i));
   assertThat(rows(c,"select principal_amount,current_principal_amount from loans where id=1")).containsExactly(List.of("100.00","90.00"));
   assertThat(rows(c,"select count(*) from loan_installments where precise_principal_amount is null and precise_interest_amount is null and interest_carry_amount is null and rounding_policy is null")).containsExactly(List.of("3"));
   for(String invalid:List.of("update loans set principal_cents=1 where id=1","update loan_installments set interest_cents=2 where id=2","update loan_prepayments set amount_cents=1 where id=1","update loan_installments set principal_amount=0,interest_amount=0 where id=2","update loans set current_principal_amount=100.01 where id=1"))assertThatThrownBy(()->s.executeUpdate(invalid)).isInstanceOf(SQLException.class);
   s.executeUpdate("update loan_installments set principal_amount=0,interest_amount=0.01 where id=2");
   assertThat(rows(c,"select principal_cents,interest_cents from loan_installments where id=2")).containsExactly(List.of("0","1"));
  }
 }
 @Test void populatedV22KeepsLegacyPlansAndAmountsWithoutInventingPrecision()throws Exception{
  Path file=StageOneDatabaseFixture.create(temp.resolve("loan-decimal"));
  var before=MigrationTestSupport.migrateExistingDatabaseTo(file,"22");
  before.executeUpdate("insert into loans(id,household_id,name,loan_type,payment_account_id,payment_category_id,principal_cents,annual_rate,term_months,repayment_method,start_on,current_principal_cents,status,created_by) select 1,1,'Legacy','OTHER',min(account_id),1,10000,0.12,3,'EQUAL_PAYMENT','2026-01-01',9000,'ACTIVE',1 from financial_transactions");
  before.executeUpdate("insert into loan_installments(id,household_id,loan_id,installment_no,due_on,principal_cents,interest_cents,status) values(1,1,1,1,'2026-02-01',3000,100,'CANCELLED'),(2,1,1,2,'2026-03-01',4500,55,'PENDING'),(3,1,1,3,'2026-04-01',4500,25,'PENDING')");
  before.executeUpdate("insert into loan_prepayments(id,household_id,loan_id,request_key,amount_cents,paid_on,created_at) values(1,1,1,'original-key',1000,'2026-01-02',current_timestamp)");
  var queries=List.of("select id,principal_cents,current_principal_cents,status,start_on,annual_rate from loans order by id","select id,loan_id,installment_no,due_on,principal_cents,interest_cents,status,confirmed_transaction_id from loan_installments order by id","select id,loan_id,request_key,amount_cents,interest_cents,paid_on,transaction_id,operation_kind from loan_prepayments order by id","select * from financial_transactions order by id","select * from ledger_journals order by id","select * from ledger_sources order by household_id,source_type,source_id");
  var snapshot=new ArrayList<List<List<String>>>();for(var q:queries)snapshot.add(rows(before.databaseUrl(),q));
  var after=MigrationTestSupport.migrateExistingDatabase(file);
  assertThat(after.version()).isEqualTo("23");
  for(int i=0;i<queries.size();i++)assertThat(rows(after.databaseUrl(),queries.get(i))).isEqualTo(snapshot.get(i));
  assertThat(after.queryString("select principal_amount from loans where id=1")).isEqualTo("100.00");
  assertThat(after.queryString("select current_principal_amount from loans where id=1")).isEqualTo("90.00");
  assertThat(after.queryString("select amount from loan_prepayments where id=1")).isEqualTo("10.00");
  assertThat(after.queryLong("select count(*) from loan_installments where precise_principal_amount is null and precise_interest_amount is null and interest_carry_amount is null and rounding_policy is null")).isEqualTo(3);
  assertThat(after.queryLong("select repayment_policy_revision from loans where id=1")).isZero();
  for(String sql:List.of("update loans set principal_cents=9999 where id=1","update loan_installments set interest_cents=2 where id=2","update loan_prepayments set amount_cents=1 where id=1","update loan_installments set principal_amount=0,interest_amount=0 where id=2","update loans set current_principal_amount=100.01 where id=1"))assertThatThrownBy(()->after.executeUpdate(sql)).isInstanceOf(IllegalStateException.class);
  after.executeUpdate("update loan_installments set principal_amount=0,interest_amount=0.01 where id=2");
  assertThat(after.queryLong("select principal_cents+interest_cents from loan_installments where id=2")).isEqualTo(1);
 }
 private List<List<String>> rows(String url,String sql)throws Exception{
  var out=new ArrayList<List<String>>();try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement();var r=s.executeQuery(sql)){while(r.next()){var row=new ArrayList<String>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.add(r.getString(i));out.add(row);}}return out;
 }
 private List<List<String>> rows(Connection c,String sql)throws Exception{
  var out=new ArrayList<List<String>>();try(var s=c.createStatement();var r=s.executeQuery(sql)){while(r.next()){var row=new ArrayList<String>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.add(r.getString(i));out.add(row);}}return out;
 }
}
