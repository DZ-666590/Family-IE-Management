package com.familyfinance.loan;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Exact legacy-cent adapter for new precision-aware schedules. Persisted old plans are never recalculated. */
public final class AmortizationCalculator {
    static final int RATE_SCALE = 6;
    static final BigDecimal MAX_ANNUAL_RATE = BigDecimal.ONE;
    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);

    public List<InstallmentDraft> calculate(
            long principalCents, BigDecimal annualRate, int termMonths, LocalDate startOn, RepaymentMethod method) {
        validate(principalCents, annualRate, termMonths, startOn, method);
        return new PreciseLoanScheduleCalculator().calculate(com.familyfinance.shared.DecimalMoney.fromCents(principalCents),annualRate,
                java.util.stream.IntStream.rangeClosed(1,termMonths).mapToObj(startOn::plusMonths).toList(),method,LoanRoundingContext.ZERO)
                .stream().map(PreciseInstallmentDraft::legacy).toList();
    }

    /** Round once at the cent boundary; rounding a repeating monthly rate first can lose an exact half cent. */
    static long periodInterest(long principalCents, BigDecimal annualRate) {
        return BigDecimal.valueOf(principalCents).multiply(annualRate).divide(TWELVE, 0, RoundingMode.HALF_UP).longValueExact();
    }

    private static void validate(long principalCents, BigDecimal annualRate, int termMonths, LocalDate startOn, RepaymentMethod method) {
        if (principalCents <= 0 || principalCents > 99_999_999_999L) throw new IllegalArgumentException("principal cents must be 1..99999999999");
        Objects.requireNonNull(annualRate, "annualRate must not be null");
        if (annualRate.scale() > RATE_SCALE || annualRate.signum() < 0 || annualRate.compareTo(MAX_ANNUAL_RATE) > 0) throw new IllegalArgumentException("annual rate must be 0..1 with at most 6 decimals");
        if (termMonths < 1 || termMonths > 360) throw new IllegalArgumentException("term months must be 1..360");
        Objects.requireNonNull(startOn, "startOn must not be null");
        if (method == null || method == RepaymentMethod.CUSTOM) throw new IllegalArgumentException("custom schedules are validated by the loan service");
    }
}
