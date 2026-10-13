package com.dailyplan.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RecurrenceCalculatorTest {
    @Test
    fun dailyTaskOccursEveryDayFromItsEffectiveStart() {
        val task = task(
            type = "daily",
            startDate = "2026-10-01",
            ruleEffectiveFrom = "2026-10-05",
        )

        assertFalse(RecurrenceCalculator.occursOn(task, LocalDate.parse("2026-10-04")))
        assertTrue(RecurrenceCalculator.occursOn(task, LocalDate.parse("2026-10-05")))
        assertTrue(RecurrenceCalculator.occursOn(task, LocalDate.parse("2026-10-06")))
    }

    @Test
    fun onceTaskOccursOnlyOnItsExecutionDate() {
        val task = task(type = "once", startDate = "2026-10-12")

        assertFalse(RecurrenceCalculator.occursOn(task, LocalDate.parse("2026-10-11")))
        assertTrue(RecurrenceCalculator.occursOn(task, LocalDate.parse("2026-10-12")))
        assertFalse(RecurrenceCalculator.occursOn(task, LocalDate.parse("2026-10-13")))
    }

    @Test
    fun weeklyTaskUsesMondayThroughSundayAndEffectiveStart() {
        val task = task(
            type = "custom",
            repeatKind = "weekly",
            startDate = "2026-10-01",
            ruleEffectiveFrom = "2026-10-12",
            weeklyDays = "1,3,5",
        )

        assertFalse(RecurrenceCalculator.occursOn(task, LocalDate.parse("2026-10-09")))
        assertTrue(RecurrenceCalculator.occursOn(task, LocalDate.parse("2026-10-12")))
        assertFalse(RecurrenceCalculator.occursOn(task, LocalDate.parse("2026-10-13")))
    }

    @Test
    fun intervalTaskAnchorsToStartDateAndRespectsEndDate() {
        val task = task(
            type = "custom",
            repeatKind = "interval",
            startDate = "2026-10-01",
            endDate = "2026-10-10",
            intervalDays = 3,
        )

        assertEquals(
            listOf("2026-10-01", "2026-10-04", "2026-10-07", "2026-10-10"),
            RecurrenceCalculator.dates(task, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-12")).map(LocalDate::toString),
        )
    }

    @Test
    fun monthlyTaskClampsToLastDayOfShortMonth() {
        val task = task(type = "custom", repeatKind = "monthly", startDate = "2026-01-01", monthlyDay = 31)

        assertTrue(RecurrenceCalculator.occursOn(task, LocalDate.parse("2026-02-28")))
        assertTrue(RecurrenceCalculator.occursOn(task, LocalDate.parse("2026-03-31")))
        assertFalse(RecurrenceCalculator.occursOn(task, LocalDate.parse("2026-03-30")))
    }

    private fun task(
        type: String = "daily",
        repeatKind: String = "daily",
        startDate: String = "2026-10-01",
        endDate: String? = null,
        ruleEffectiveFrom: String? = null,
        intervalDays: Int = 3,
        weeklyDays: String = "1,3,5",
        monthlyDay: Int = 15,
    ) = TaskEntity(
        title = "test",
        type = type,
        repeatKind = repeatKind,
        startDate = startDate,
        endDate = endDate,
        ruleEffectiveFrom = ruleEffectiveFrom,
        intervalDays = intervalDays,
        weeklyDays = weeklyDays,
        monthlyDay = monthlyDay,
    )
}
