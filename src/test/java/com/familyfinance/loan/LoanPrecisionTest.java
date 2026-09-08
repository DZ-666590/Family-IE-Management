package com.familyfinance.loan;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class LoanPrecisionTest {
    @Test void customTargetMetadataConservesPrincipalWhenRoundedWeightsHaveAZeroTail() {
        var day=LocalDate.of(2026,1,1);long[] actual={100,100,99,1};String[] weights={"1","1","1","0"};
        var before=new java.util.ArrayList<InstallmentDraft>();long remaining=300;
        for(int i=0;i<4;i++){long opening=remaining;remaining-=actual[i];before.add(new InstallmentDraft(i+1,day.plusMonths(i+1),actual[i],100,remaining,new BigDecimal(weights[i]).setScale(12),BigDecimal.ONE.setScale(12),BigDecimal.ZERO.setScale(12),"CUSTOM_BOUNDED_CENTS_V1",BigDecimal.valueOf(opening,2).setScale(12),BigDecimal.ONE.setScale(12)));}
        var after=new LoanPrepaymentPlanner().planRemaining(before,new BigDecimal("0.02"),BigDecimal.ZERO,RepaymentMethod.CUSTOM,PrepaymentStrategy.REDUCE_PAYMENT,null,LoanRoundingContext.ZERO,null);
        assertThat(after).allSatisfy(r->assertThat(r.precisePrincipalAmount()).isNotNegative());
        assertThat(after.stream().map(InstallmentDraft::precisePrincipalAmount).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("0.02");
    }
    @Test void termOptionsShareOneBudgetAndSeparateUnknownFromProvenInfeasible() {
        var day=LocalDate.of(2026,1,1);
        var rows=java.util.List.of(new InstallmentDraft(1,day.plusMonths(1),4,1,2),new InstallmentDraft(2,day.plusMonths(2),1,1,1),new InstallmentDraft(3,day.plusMonths(3),1,1,0));
        var budget=new LoanPlanningBudget(2);
        var options=new LoanTermOptions().evaluateCustom(rows,new BigDecimal("0.03"),LoanRoundingContext.ZERO,null,budget);
        assertThat(options.get(0).evaluationStatus()).isEqualTo(LoanTermOptions.EvaluationStatus.FEASIBLE);
        assertThat(options.subList(1,3)).allSatisfy(o->{assertThat(o.evaluationStatus()).isEqualTo(LoanTermOptions.EvaluationStatus.UNDETERMINED);assertThat(o.reason()).isEqualTo("LOAN_PLAN_SEARCH_LIMIT");});
        assertThat(budget.used()).isEqualTo(2);
        var impossible=java.util.List.of(new InstallmentDraft(1,day.plusMonths(1),1,0,1),new InstallmentDraft(2,day.plusMonths(2),1,0,0));
        var rejected=new LoanTermOptions().evaluateCustom(impossible,new BigDecimal("0.01"),LoanRoundingContext.ZERO,null).get(1);
        assertThat(rejected.evaluationStatus()).isEqualTo(LoanTermOptions.EvaluationStatus.INFEASIBLE);
        assertThat(rejected.reason()).isNotEqualTo("LOAN_PLAN_SEARCH_LIMIT");
    }
    @Test void moderateCustomTermEnumerationHasABoundedMeasuredSearchCost() {
        var day=LocalDate.of(2026,1,1);
        var rows=java.util.stream.IntStream.range(0,120).mapToObj(i->new InstallmentDraft(i+1,day.plusMonths(i+1),100,1,(119-i)*100L)).toList();
        var budget=new LoanPlanningBudget(10_000);
        var options=new LoanTermOptions().evaluateCustom(rows,new BigDecimal("60.00"),LoanRoundingContext.ZERO,null,budget);
        assertThat(options).hasSize(120).allSatisfy(o->assertThat(o.evaluationStatus()).isEqualTo(LoanTermOptions.EvaluationStatus.FEASIBLE));
        assertThat(budget.used()).isBetween(120,8_000);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"3.00,8.08,11.08","2.99,8.05,11.04"})
    void nearInterestOnlyRoundedCashFallsBackWithoutBalloon(String principal,String interest,String cash) {
        var dates=java.util.stream.IntStream.rangeClosed(1,359).mapToObj(i->LocalDate.of(2026,1,31).plusMonths(i)).toList();
        var rows=new PreciseLoanScheduleCalculator().calculate(new BigDecimal(principal),new BigDecimal("0.12"),dates,RepaymentMethod.EQUAL_PAYMENT,new LoanRoundingContext(new BigDecimal("0.036"),new BigDecimal("0.04")));
        assertThat(rows).hasSize(359).allSatisfy(r->{assertThat(r.cashAmount()).isPositive();assertThat(r.roundingPolicy()).isEqualTo("CUMULATIVE_CENTS_V1");});
        assertThat(rows.stream().map(PreciseInstallmentDraft::interestAmount).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo(interest);
        assertThat(rows.stream().map(PreciseInstallmentDraft::cashAmount).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo(cash);
        assertThat(rows.get(358).principalAmount()).isEqualByComparingTo("0.04");
        assertThat(rows.get(358).cashAmount()).isEqualByComparingTo("0.04");
        assertThat(rows.get(358).remainingPrincipal()).isZero();
        assertThat(rows).extracting(PreciseInstallmentDraft::dueOn).isEqualTo(dates);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void customLooksAheadAtRoundedFutureInterestBeforeDeclaringInfeasible(boolean persistedRatios) {
        var day=LocalDate.of(2026,1,1);
        var original=java.util.List.of(new InstallmentDraft(1,day.plusMonths(1),4,1,2),new InstallmentDraft(2,day.plusMonths(2),1,1,1),new InstallmentDraft(3,day.plusMonths(3),1,1,0));
        var rows=original;
        if(persistedRatios)rows=original.stream().map(r->new InstallmentDraft(r.installmentNo(),r.dueOn(),r.principalCents(),r.interestCents(),r.remainingPrincipalCents(),r.principalAmount().setScale(12),r.interestAmount().setScale(12),BigDecimal.ZERO.setScale(12),"CUSTOM_CONTRACT_V1",BigDecimal.valueOf(r.remainingPrincipalCents()+r.principalCents(),2).setScale(12),new BigDecimal("0.010000000000"))).toList();
        var after=new LoanPrepaymentPlanner().planRemaining(rows,new BigDecimal("0.03"),BigDecimal.ZERO,RepaymentMethod.CUSTOM,PrepaymentStrategy.REDUCE_PAYMENT,null,LoanRoundingContext.ZERO,null);
        assertThat(after).extracting(InstallmentDraft::principalCents).containsExactly(1L,1L,1L);
        assertThat(after).extracting(InstallmentDraft::interestCents).containsExactly(1L,1L,1L);
        assertThat(after).extracting(InstallmentDraft::remainingPrincipalCents).containsExactly(2L,1L,0L);
        assertThat(after.get(0).precisePrincipalAmount()).isEqualByComparingTo("0.02");
        assertThat(after.get(0).preciseInterestAmount()).isEqualByComparingTo("0.005");
        assertThat(new LoanTermOptions().evaluateCustom(rows,new BigDecimal("0.03"),LoanRoundingContext.ZERO,null).get(2).allowed()).isTrue();
    }
    private final AmortizationCalculator calculator = new AmortizationCalculator();
    @Test void fractionalAnnuityKeeps360PositiveCashPeriodsWithoutForcingEqualPrincipal() {
        var rows = calculator.calculate(360, new BigDecimal("0.12"), 360, LocalDate.of(2026,1,31), RepaymentMethod.EQUAL_PAYMENT);
        assertThat(rows).hasSize(360).allSatisfy(r -> assertThat(r.principalCents()+r.interestCents()).isPositive());
        assertThat(rows.stream().mapToLong(InstallmentDraft::principalCents).sum()).isEqualTo(360);
        assertThat(rows.stream().mapToLong(InstallmentDraft::interestCents).sum()).isEqualTo(973);
        assertThat(rows.subList(0,8)).extracting(InstallmentDraft::principalCents).containsExactly(0L,0L,0L,0L,0L,0L,0L,0L);
        assertThat(rows.subList(0,8)).extracting(InstallmentDraft::interestCents).containsExactly(4L,3L,4L,3L,4L,4L,3L,4L);
        assertThat(rows.subList(355,360)).extracting(InstallmentDraft::principalCents).containsExactly(3L,4L,3L,4L,4L);
        assertThat(rows.get(359).remainingPrincipalCents()).isZero();
    }
    @Test void oneCentAtZeroRateCannotHaveTwoPositiveCashPayments() {
        assertThatThrownBy(() -> calculator.calculate(1,BigDecimal.ZERO,2,LocalDate.of(2026,1,31),RepaymentMethod.EQUAL_PAYMENT)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void normalFixedCashEvolvesTheoreticalBalanceAndRoundsCumulativeInterest() {
        var rows=calculator.calculate(120000,new BigDecimal("0.12"),12,LocalDate.of(2026,1,31),RepaymentMethod.EQUAL_PAYMENT);
        assertThat(rows).extracting(InstallmentDraft::interestCents).containsExactly(1200L,1105L,1010L,913L,816L,718L,618L,517L,416L,314L,210L,105L);
        assertThat(rows.subList(0,11)).allSatisfy(r -> assertThat(r.principalCents()+r.interestCents()).isEqualTo(10662));
        assertThat(rows.get(11).principalCents()+rows.get(11).interestCents()).isEqualTo(10660);
    }
    @Test void replanningPreservesTheOriginal360DatesForTheFractionalResidual() {
        var before=calculator.calculate(120000,new BigDecimal("0.12"),360,LocalDate.of(2025,12,31),RepaymentMethod.EQUAL_PAYMENT);
        var after=new LoanPrepaymentPlanner().plan(before,119640,new BigDecimal("0.12"),RepaymentMethod.EQUAL_PAYMENT,LocalDate.of(2026,1,1),PrepaymentStrategy.REDUCE_PAYMENT);
        assertThat(after).hasSize(360);
        assertThat(after).extracting(InstallmentDraft::dueOn).isEqualTo(before.stream().map(InstallmentDraft::dueOn).toList());
        assertThat(after.stream().mapToLong(InstallmentDraft::interestCents).sum()).isEqualTo(973);
    }
    @Test void fullPaidInterestBaselineAvoidsNegativeHalfCentRefundAndSurvivesNormalization() {
        var dates=java.util.List.of(LocalDate.of(2026,2,28),LocalDate.of(2026,3,31));
        var precise=new PreciseLoanScheduleCalculator();
        var rows=precise.calculate(new BigDecimal("1.00"),BigDecimal.ZERO,dates,RepaymentMethod.EQUAL_PAYMENT,new LoanRoundingContext(new BigDecimal("0.005"),new BigDecimal("0.01")));
        assertThat(rows).extracting(PreciseInstallmentDraft::interestAmount).containsExactly(new BigDecimal("0.00"),new BigDecimal("0.00"));
        rows=precise.calculate(new BigDecimal("1.00"),new BigDecimal("0.0012"),dates,RepaymentMethod.EQUAL_PAYMENT,new LoanRoundingContext(new BigDecimal("0.0049"),BigDecimal.ZERO));
        assertThat(rows).extracting(PreciseInstallmentDraft::principalAmount).containsExactly(new BigDecimal("0.49"),new BigDecimal("0.51"));
        assertThat(rows).extracting(PreciseInstallmentDraft::interestAmount).containsExactly(new BigDecimal("0.01"),new BigDecimal("0.00"));
        assertThat(rows.get(0).precisePrincipalAmount()).isEqualByComparingTo("0.4999");
        assertThat(rows.get(1).precisePrincipalAmount()).isEqualByComparingTo("0.5001");
    }
    @Test void customShorterTermPreservesRatiosAcrossRepeatedReplansAndZeroWeightPrefix() {
        var day=LocalDate.of(2026,1,1);
        var before=java.util.List.of(new InstallmentDraft(1,day.plusMonths(1),20000,3000,80000),new InstallmentDraft(2,day.plusMonths(2),30000,8000,50000),new InstallmentDraft(3,day.plusMonths(3),50000,1000,0));
        var planner=new LoanPrepaymentPlanner();
        var after=planner.planRemaining(before,new BigDecimal("750"),BigDecimal.ZERO,RepaymentMethod.CUSTOM,PrepaymentStrategy.ADJUST_TERM,2,LoanRoundingContext.ZERO,null);
        assertThat(after).extracting(InstallmentDraft::principalCents).containsExactly(30000L,45000L);
        assertThat(after).extracting(InstallmentDraft::interestCents).containsExactly(2250L,4500L);
        var again=planner.planRemaining(after,new BigDecimal("500"),BigDecimal.ZERO,RepaymentMethod.CUSTOM,PrepaymentStrategy.REDUCE_PAYMENT,null,LoanRoundingContext.ZERO,null);
        assertThat(again).extracting(InstallmentDraft::principalCents).containsExactly(20000L,30000L);
        assertThat(again).extracting(InstallmentDraft::interestCents).containsExactly(1500L,3000L);
        var interestOnly=java.util.List.of(new InstallmentDraft(1,day.plusMonths(1),0,10,100),new InstallmentDraft(2,day.plusMonths(2),0,10,100),new InstallmentDraft(3,day.plusMonths(3),100,10,0));
        var shorter=planner.planRemaining(interestOnly,new BigDecimal("0.80"),BigDecimal.ZERO,RepaymentMethod.CUSTOM,PrepaymentStrategy.ADJUST_TERM,2,LoanRoundingContext.ZERO,null);
        assertThat(shorter).extracting(InstallmentDraft::principalCents).containsExactly(0L,80L);
        assertThat(shorter).extracting(InstallmentDraft::interestCents).containsExactly(8L,8L);
        assertThatThrownBy(()->planner.planRemaining(before,new BigDecimal("750"),BigDecimal.ZERO,RepaymentMethod.CUSTOM,PrepaymentStrategy.ADJUST_TERM,null,LoanRoundingContext.ZERO,null)).isInstanceOf(com.familyfinance.shared.RequestValidationException.class);
    }
}
