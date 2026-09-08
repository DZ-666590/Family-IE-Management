package com.familyfinance.loan;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class LoanPrepaymentPlannerTest {
 private final LoanPrepaymentPlanner planner=new LoanPrepaymentPlanner();
 private final LocalDate paid=LocalDate.of(2026,1,1);
 private List<InstallmentDraft> standard(long principal,String rate,RepaymentMethod method){return new AmortizationCalculator().calculate(principal,new BigDecimal(rate),12,LocalDate.of(2025,12,31),method);}
 private List<InstallmentDraft> plan(List<InstallmentDraft> before,long amount,String rate,RepaymentMethod method,PrepaymentStrategy strategy){return planner.plan(before,amount,new BigDecimal(rate),method,paid,strategy);}
 @Test void zeroRateKeepsExactMonthEndDatesAndBothLiteralPaymentOutcomes(){
  var before=standard(120000,"0",RepaymentMethod.EQUAL_PAYMENT);
  var term=plan(before,30000,"0",RepaymentMethod.EQUAL_PAYMENT,PrepaymentStrategy.REDUCE_TERM);
  var payment=plan(before,30000,"0",RepaymentMethod.EQUAL_PAYMENT,PrepaymentStrategy.REDUCE_PAYMENT);
  assertThat(term).hasSize(9);assertThat(term).allSatisfy(p->assertThat(p.principalCents()).isEqualTo(10000));
  assertThat(payment).hasSize(12);assertThat(payment).allSatisfy(p->assertThat(p.principalCents()).isEqualTo(7500));
  assertThat(term.get(0).dueOn()).isEqualTo("2026-01-31");assertThat(term.get(1).dueOn()).isEqualTo("2026-02-28");assertThat(term.get(2).dueOn()).isEqualTo("2026-03-31");
  assertThat(payment.get(11).dueOn()).isEqualTo("2026-12-31");assertThat(term.stream().mapToLong(InstallmentDraft::principalCents).sum()).isEqualTo(90000);
 }
 @Test void twelvePercentLiteralFixturesIncludeFinalCentRemainder(){
  var before=standard(120000,"0.12",RepaymentMethod.EQUAL_PAYMENT);
  var term=plan(before,30000,"0.12",RepaymentMethod.EQUAL_PAYMENT,PrepaymentStrategy.REDUCE_TERM);
  var payment=plan(before,30000,"0.12",RepaymentMethod.EQUAL_PAYMENT,PrepaymentStrategy.REDUCE_PAYMENT);
  assertThat(term).hasSize(9);assertThat(term.subList(0,8)).allSatisfy(p->assertThat(p.principalCents()+p.interestCents()).isEqualTo(10662));assertThat(term.get(8).principalCents()+term.get(8).interestCents()).isEqualTo(9206);
  assertThat(term.stream().mapToLong(InstallmentDraft::interestCents).sum()).isEqualTo(4502);
  assertThat(payment).hasSize(12);assertThat(payment.subList(0,11)).allSatisfy(p->assertThat(p.principalCents()+p.interestCents()).isEqualTo(7996));assertThat(payment.get(11).principalCents()+payment.get(11).interestCents()).isEqualTo(8001);assertThat(payment.stream().mapToLong(InstallmentDraft::interestCents).sum()).isEqualTo(5957);
 }
 @Test void equalPrincipalRetainsVaryingCapsAndFixedTermDates(){
  var before=standard(120000,"0.12",RepaymentMethod.EQUAL_PRINCIPAL);
  var term=plan(before,30000,"0.12",RepaymentMethod.EQUAL_PRINCIPAL,PrepaymentStrategy.REDUCE_TERM);
  assertThat(term.get(0).principalCents()).isEqualTo(10300);assertThat(term.get(0).interestCents()).isEqualTo(900);assertThat(term.get(1).principalCents()+term.get(1).interestCents()).isEqualTo(11100);
  var payment=plan(before,30000,"0.12",RepaymentMethod.EQUAL_PRINCIPAL,PrepaymentStrategy.REDUCE_PAYMENT);
  assertThat(payment).hasSize(12);assertThat(payment).allSatisfy(p->assertThat(p.principalCents()).isEqualTo(7500));assertThat(payment.get(11).interestCents()).isEqualTo(75);
 }
 @Test void standardInterestRoundsExactHalfCentOnceAndBothStrategiesAgree(){
  var one=new AmortizationCalculator().calculate(600,new BigDecimal("0.01"),1,paid,RepaymentMethod.EQUAL_PAYMENT);
  assertThat(one.get(0).interestCents()).isEqualTo(1);
  var after=plan(standard(1201,"0.01",RepaymentMethod.EQUAL_PAYMENT),1,"0.01",RepaymentMethod.EQUAL_PAYMENT,PrepaymentStrategy.REDUCE_PAYMENT);
  assertThat(after).extracting(InstallmentDraft::principalCents).containsExactly(100L,100L,100L,101L,100L,100L,101L,100L,101L,101L,101L,95L);assertThat(after.stream().mapToLong(InstallmentDraft::interestCents).sum()).isEqualTo(6);
  var before=List.of(new InstallmentDraft(1,paid.plusMonths(1),700,1,0));
  for(var strategy:List.of(PrepaymentStrategy.REDUCE_TERM,PrepaymentStrategy.REDUCE_PAYMENT)){var next=plan(before,100,"0.01",RepaymentMethod.EQUAL_PAYMENT,strategy);assertThat(next.get(0).principalCents()).isEqualTo(600);assertThat(next.get(0).interestCents()).isEqualTo(1);}
 }
 @Test void customIrregularDatesUseOriginalImplicitRatesAndPrincipalWeights(){
  var before=List.of(new InstallmentDraft(5,LocalDate.of(2026,1,7),20000,3000,80000),new InstallmentDraft(6,LocalDate.of(2026,4,19),30000,8000,50000),new InstallmentDraft(7,LocalDate.of(2027,2,3),50000,1000,0));
  var payment=plan(before,25000,"0.99",RepaymentMethod.CUSTOM,PrepaymentStrategy.REDUCE_PAYMENT);
  assertThat(payment).extracting(InstallmentDraft::principalCents).containsExactly(15000L,22500L,37500L);assertThat(payment).extracting(InstallmentDraft::interestCents).containsExactly(2250L,6000L,750L);assertThat(payment).extracting(InstallmentDraft::dueOn).containsExactly(LocalDate.of(2026,1,7),LocalDate.of(2026,4,19),LocalDate.of(2027,2,3));
  var term=plan(before,25000,"0.99",RepaymentMethod.CUSTOM,PrepaymentStrategy.REDUCE_TERM);
  assertThat(term).extracting(InstallmentDraft::principalCents).containsExactly(20750L,32575L,21675L);assertThat(term).extracting(InstallmentDraft::interestCents).containsExactly(2250L,5425L,434L);
 }
 @Test void weightedCentsNeverInflateOriginalTailOrCreateZeroPrincipal(){
  var before=List.of(new InstallmentDraft(1,paid.plusDays(1),1,0,3),new InstallmentDraft(2,paid.plusDays(9),2,0,1),new InstallmentDraft(3,paid.plusDays(60),1,100,0));
  var payment=plan(before,1,"0",RepaymentMethod.CUSTOM,PrepaymentStrategy.REDUCE_PAYMENT);
  assertThat(payment).extracting(InstallmentDraft::principalCents).containsExactly(1L,1L,1L);assertThat(payment).extracting(InstallmentDraft::interestCents).containsExactly(0L,0L,100L);
 }
 @Test void tinyResidualRejectsFixedTermButTermStrategyClipsWithoutZeroRows(){
  var before=standard(120000,"0",RepaymentMethod.EQUAL_PAYMENT);
  assertThatThrownBy(()->plan(before,119999,"0",RepaymentMethod.EQUAL_PAYMENT,PrepaymentStrategy.REDUCE_PAYMENT)).hasMessageContaining("正现金");
  var term=plan(before,119999,"0",RepaymentMethod.EQUAL_PAYMENT,PrepaymentStrategy.REDUCE_TERM);assertThat(term).hasSize(1);assertThat(term.get(0).principalCents()).isEqualTo(1);
  var small=plan(before,1,"0",RepaymentMethod.EQUAL_PAYMENT,PrepaymentStrategy.REDUCE_TERM);assertThat(small).hasSize(12);assertThat(small.get(11).principalCents()).isEqualTo(9999);
 }
 @Test void nearFullAnnuityCannotSilentlyBecomeOneCentPrincipalFor360Periods(){
  var before=new AmortizationCalculator().calculate(120000,new BigDecimal("0.12"),360,LocalDate.of(2025,12,31),RepaymentMethod.EQUAL_PAYMENT);
  assertThat(before).hasSize(360).allSatisfy(row->assertThat(row.principalCents()).isPositive());
  assertThat(before.stream().mapToLong(InstallmentDraft::principalCents).sum()).isEqualTo(120000);
  var fixed=plan(before,119640,"0.12",RepaymentMethod.EQUAL_PAYMENT,PrepaymentStrategy.REDUCE_PAYMENT);
  assertThat(fixed).hasSize(360).allSatisfy(r->assertThat(r.principalCents()+r.interestCents()).isPositive());assertThat(fixed.stream().mapToLong(InstallmentDraft::interestCents).sum()).isEqualTo(973);
  var term=plan(before,119640,"0.12",RepaymentMethod.EQUAL_PAYMENT,PrepaymentStrategy.REDUCE_TERM);
  assertThat(term).singleElement().satisfies(r->{assertThat(r.principalCents()).isEqualTo(360);assertThat(r.interestCents()).isEqualTo(4);assertThat(r.remainingPrincipalCents()).isZero();});
 }
 @Test void dueTodayFullAndMismatchedEmptySchedulesCannotBeSilentlyReplanned(){
  var before=standard(120000,"0",RepaymentMethod.EQUAL_PAYMENT);
  assertThatThrownBy(()->planner.plan(before,100,new BigDecimal("0"),RepaymentMethod.EQUAL_PAYMENT,LocalDate.of(2026,1,31),PrepaymentStrategy.REDUCE_TERM)).hasMessageContaining("到期");
  assertThatThrownBy(()->plan(before,120000,"0",RepaymentMethod.EQUAL_PAYMENT,PrepaymentStrategy.REDUCE_TERM)).hasMessageContaining("一次结清");
  assertThatThrownBy(()->plan(List.of(),1,"0",RepaymentMethod.CUSTOM,PrepaymentStrategy.REDUCE_TERM)).hasMessageContaining("计划");
 }
}
