package com.dailyplan.app.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit

object RecurrenceCalculator {
    fun occursOn(task: TaskEntity, date: LocalDate): Boolean {
        val start = LocalDate.parse(task.startDate)
        val end = task.endDate?.let(LocalDate::parse)
        val ruleStart = task.ruleEffectiveFrom?.let(LocalDate::parse) ?: start
        if (date < start || date < ruleStart || (end != null && date > end)) return false

        return when (task.type) {
            "once" -> date == start
            "daily" -> true
            else -> when (task.repeatKind) {
                "weekly" -> date.dayOfWeek.value in task.weeklyDays.split(',').mapNotNull(String::toIntOrNull)
                "interval" -> ChronoUnit.DAYS.between(start, date) % task.intervalDays.coerceAtLeast(1) == 0L
                "monthly" -> date.dayOfMonth == task.monthlyDay.coerceIn(1, date.lengthOfMonth())
                else -> true
            }
        }
    }

    fun dates(task: TaskEntity, from: LocalDate, to: LocalDate): List<LocalDate> {
        if (to < from) return emptyList()
        val result = mutableListOf<LocalDate>()
        var day = from
        while (day <= to) {
            if (occursOn(task, day)) result += day
            day = day.plusDays(1)
        }
        return result
    }
}
