package com.dailyplan.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanTransferCodecTest {
    @Test
    fun csvRoundTripKeepsQuotesCommasNewlinesAndStableId() {
        val source = record(title = "买菜,\"水果\"\n顺便买牛奶", description = "第一行\n第二行")

        val parsed = PlanTransferCodec.parse(PlanTransferCodec.exportCsv(listOf(source)), "plans.csv")

        assertTrue(parsed.issues.isEmpty())
        assertEquals(1, parsed.totalRows)
        assertEquals(source.externalId, parsed.records.single().externalId)
        assertEquals(source.title, parsed.records.single().title)
        assertEquals(source.description, parsed.records.single().description)
        assertEquals(source.weeklyDays, parsed.records.single().weeklyDays)
    }

    @Test
    fun xlsxRoundTripReadsTextCellsWithoutFormulaEvaluation() {
        val source = record(title = "=1+1", description = "计划")

        val bytes = PlanTransferCodec.exportXlsx(listOf(source))
        val parsed = PlanTransferCodec.parse(bytes, "plans.xlsx")

        assertTrue(parsed.issues.isEmpty())
        assertEquals(source.externalId, parsed.records.single().externalId)
        assertEquals(source.title, parsed.records.single().title)
        assertEquals(source.startDate, parsed.records.single().startDate)
    }

    @Test
    fun csvAndXlsxRoundTripEverySupportedScheduleType() {
        val sources = listOf(
            record(externalId = "once", type = "once", repeatKind = "once"),
            record(externalId = "daily", type = "daily", repeatKind = "daily"),
            record(externalId = "weekly", repeatKind = "weekly", ruleEffectiveFrom = "2026-10-15"),
            record(externalId = "interval", repeatKind = "interval"),
            record(externalId = "monthly", repeatKind = "monthly"),
        )

        listOf(
            PlanTransferCodec.exportCsv(sources) to "plans.csv",
            PlanTransferCodec.exportXlsx(sources) to "plans.xlsx",
        ).forEach { (bytes, name) ->
            val parsed = PlanTransferCodec.parse(bytes, name)
            assertTrue("$name: ${parsed.issues}", parsed.issues.isEmpty())
            assertEquals(sources.size, parsed.records.size)
            sources.zip(parsed.records).forEach { (source, actual) ->
                assertEquals(source.externalId, actual.externalId)
                assertEquals(source.type, actual.type)
                assertEquals(source.repeatKind, actual.repeatKind)
                assertEquals(source.startDate, actual.startDate)
                assertEquals(source.ruleEffectiveFrom, actual.ruleEffectiveFrom)
                assertEquals(source.categoryName, actual.categoryName)
                assertEquals(source.reminderTime, actual.reminderTime)
            }
        }
    }

    @Test
    fun csvExportProtectsFormulaLikeTextAndCanImportItBackAsText() {
        val source = record(title = "=SUM(1,2)")
        val bytes = PlanTransferCodec.exportCsv(listOf(source))
        val csvText = bytes.toString(Charsets.UTF_8)

        assertTrue(csvText.contains("\"'=SUM(1,2)\""))
        assertEquals(source.title, PlanTransferCodec.parse(bytes, "plans.csv").records.single().title)
    }

    @Test
    fun invalidDateAndRepeatParametersAreReportedByRowAndField() {
        val csv = buildString {
            append(PlanTransferCodec.headers.joinToString(","))
            append("\n")
            append(csvRow("weekly", "每周", "2026-02-30", weeklyDays = "1,x"))
            append(csvRow("interval", "间隔", "2026-02-28"))
            append(csvRow("monthly", "每月", "2026-02-28", monthlyDay = "32"))
        }.toByteArray(Charsets.UTF_8)

        val parsed = PlanTransferCodec.parse(csv, "bad.csv")

        assertTrue(parsed.records.isEmpty())
        assertTrue(parsed.issues.any { it.rowNumber == 2 && it.field == "开始日期" })
        assertTrue(parsed.issues.any { it.rowNumber == 2 && it.field == "每周重复日" })
        assertTrue(parsed.issues.any { it.rowNumber == 3 && it.field == "间隔天数" })
        assertTrue(parsed.issues.any { it.rowNumber == 4 && it.field == "每月执行日" })
    }

    private fun csvRow(
        id: String,
        type: String,
        startDate: String,
        weeklyDays: String = "",
        intervalDays: String = "",
        monthlyDay: String = "",
    ) = listOf(id, "Task $id", "", "", type, startDate, "", "", "", weeklyDays, intervalDays, monthlyDay, "false", "09:00", "0", "false")
        .joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" } + "\n"

    private fun record(
        title: String = "完成报告",
        description: String = "备注",
        externalId: String = "stable-task-id",
        type: String = "custom",
        repeatKind: String = "weekly",
        ruleEffectiveFrom: String? = null,
    ) = PlanTransferRecord(
        externalId = externalId,
        title = title,
        description = description,
        categoryName = "科研",
        type = type,
        repeatKind = repeatKind,
        startDate = "2026-10-12",
        endDate = null,
        ruleEffectiveFrom = ruleEffectiveFrom,
        weeklyDays = "1,3,5",
        intervalDays = 3,
        monthlyDay = 15,
        reminderEnabled = true,
        reminderTime = "09:00",
        reminderOffset = 10,
        isPaused = false,
    )
}
