package com.familyfinance.reporting;

public record BudgetSummary(int activeBudgetCount, long plannedCents, Long spentCents, Integer nearLimitCount, Integer overLimitCount) {
}
