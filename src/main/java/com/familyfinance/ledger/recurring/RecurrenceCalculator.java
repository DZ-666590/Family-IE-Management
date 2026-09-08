package com.familyfinance.ledger.recurring;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;

final class RecurrenceCalculator {
    private RecurrenceCalculator() {}

    static LocalDate firstDue(
            RecurringScheduleType type, Integer dayOfMonth, DayOfWeek dayOfWeek, LocalDate start) {
        if (type != RecurringScheduleType.WEEKLY) {
            YearMonth month = YearMonth.from(start);
            LocalDate candidate = month.atDay(Math.min(dayOfMonth, month.lengthOfMonth()));
            if (candidate.isBefore(start)) {
                month = month.plusMonths(monthsPerInterval(type, 1));
                candidate = month.atDay(Math.min(dayOfMonth, month.lengthOfMonth()));
            }
            return candidate;
        }
        int days = Math.floorMod(dayOfWeek.getValue() - start.getDayOfWeek().getValue(), 7);
        return start.plusDays(days);
    }

    static LocalDate nextDue(RecurringRule rule, LocalDate current) {
        return nextDue(rule.getScheduleType(), rule.getIntervalValue(), rule.getDayOfMonth(), current);
    }

    static LocalDate nextDue(
            RecurringScheduleType type, int interval, Integer dayOfMonth, LocalDate current) {
        if (type == RecurringScheduleType.WEEKLY) return current.plusWeeks(interval);
        YearMonth target = type == RecurringScheduleType.YEARLY
                ? YearMonth.from(current).plusYears(interval)
                : YearMonth.from(current).plusMonths(monthsPerInterval(type, interval));
        return target.atDay(Math.min(dayOfMonth, target.lengthOfMonth()));
    }

    private static int monthsPerInterval(RecurringScheduleType type, int interval) {
        return switch (type) {
            case QUARTERLY -> Math.multiplyExact(interval, 3);
            case YEARLY -> Math.multiplyExact(interval, 12);
            default -> interval;
        };
    }

    static LocalDate withinEnd(LocalDate due, LocalDate end) {
        return due == null || end == null || !due.isAfter(end) ? due : null;
    }
}
