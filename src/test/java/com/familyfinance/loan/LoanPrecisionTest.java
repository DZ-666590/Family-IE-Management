package com.familyfinance.loan;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class LoanPrecisionTest {
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
