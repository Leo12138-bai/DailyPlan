package com.dailyplan.app.data

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

data class PlanTransferRecord(
    val externalId: String,
    val title: String,
    val description: String,
    val categoryName: String,
    val type: String,
    val repeatKind: String,
    val startDate: String,
    val endDate: String?,
    val ruleEffectiveFrom: String? = null,
    val weeklyDays: String,
    val intervalDays: Int,
    val monthlyDay: Int,
    val reminderEnabled: Boolean,
    val reminderTime: String,
    val reminderOffset: Int,
    val isPaused: Boolean,
    val sourceRow: Int = 0,
)

data class PlanTransferIssue(val rowNumber: Int, val field: String, val message: String)

data class ParsedPlanFile(
    val records: List<PlanTransferRecord>,
    val issues: List<PlanTransferIssue>,
    val totalRows: Int,
)

object PlanTransferCodec {
    val headers = listOf(
        "任务标识", "任务名称", "任务备注", "任务分类", "任务类型", "开始日期", "结束日期", "周期规则生效日期", "执行日期",
        "每周重复日", "间隔天数", "每月执行日", "提醒开关", "提醒时间", "提前提醒分钟数", "暂停状态",
    )

    private const val MAX_XLSX_EXPANDED_BYTES = 24 * 1024 * 1024
    private val dateOrigin = LocalDate.of(1899, 12, 30)

    fun exportCsv(records: List<PlanTransferRecord>): ByteArray {
        val text = buildString {
            append('\uFEFF')
            append(headers.joinToString(",", transform = ::csvCell)).append("\r\n")
            records.forEach { record ->
                append(record.toCells().joinToString(",", transform = ::csvCell)).append("\r\n")
            }
        }
        return text.toByteArray(StandardCharsets.UTF_8)
    }

    fun exportXlsx(records: List<PlanTransferRecord>): ByteArray {
        val rows = listOf(headers) + records.map { it.toCells() }
        val sheet = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
            rows.forEachIndexed { rowIndex, row ->
                val rowNumber = rowIndex + 1
                append("<row r=\"").append(rowNumber).append("\">")
                row.forEachIndexed { columnIndex, value ->
                    val reference = columnName(columnIndex + 1) + rowNumber
                    val safe = safeSpreadsheetText(value)
                    append("<c r=\"").append(reference).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                    append(xmlEscape(safe))
                    append("</t></is></c>")
                }
                append("</row>")
            }
            append("</sheetData></worksheet>")
        }
        val entries = linkedMapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="每日计划" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to sheet,
        )
        return ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                entries.forEach { (name, value) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(value.toByteArray(StandardCharsets.UTF_8))
                    zip.closeEntry()
                }
            }
            bytes.toByteArray()
        }
    }

    fun parse(bytes: ByteArray, fileName: String): ParsedPlanFile {
        require(bytes.size <= MAX_XLSX_EXPANDED_BYTES) { "文件过大，暂不支持超过 24 MB 的计划文件" }
        val rows = when (fileName.substringAfterLast('.', "").lowercase()) {
            "csv" -> parseCsv(String(bytes, StandardCharsets.UTF_8))
            "xlsx" -> parseXlsx(bytes)
            else -> throw IllegalArgumentException("仅支持 .csv 或 .xlsx 文件")
        }
        require(rows.isNotEmpty()) { "文件中没有计划数据" }
        val normalizedHeaders = rows.first().map(::normalizeHeader)
        require("任务名称" in normalizedHeaders) { "缺少必需字段：任务名称" }
        val parsed = mutableListOf<PlanTransferRecord>()
        val issues = mutableListOf<PlanTransferIssue>()
        val allDataRows = rows.drop(1)
        val dataRows = allDataRows.filterNot { row -> row.all(String::isBlank) }
        allDataRows.forEachIndexed { index, cells ->
            if (cells.all(String::isBlank)) return@forEachIndexed
            val rowNumber = index + 2
            val values = normalizedHeaders.mapIndexedNotNull { column, header ->
                header.takeIf(String::isNotBlank)?.let { it to cells.getOrElse(column) { "" }.removePrefix("\uFEFF") }
            }.toMap()
            val record = parseRecord(values, rowNumber, issues)
            if (record != null) parsed += record
        }
        return ParsedPlanFile(parsed, issues, dataRows.size)
    }

    private fun parseRecord(values: Map<String, String>, row: Int, issues: MutableList<PlanTransferIssue>): PlanTransferRecord? {
        fun value(field: String): String = values[field].orEmpty().unprotectSpreadsheetText()
        fun issue(field: String, message: String) { issues += PlanTransferIssue(row, field, message) }

        val title = value("任务名称").trim()
        if (title.isBlank()) issue("任务名称", "不能为空")
        if (title.length > 500) issue("任务名称", "不能超过 500 个字符")
        val externalId = value("任务标识").trim()
        if (externalId.length > 128) issue("任务标识", "不能超过 128 个字符")
        val category = value("任务分类").trim()
        if (category.length > 80) issue("任务分类", "不能超过 80 个字符")

        val rawType = value("任务类型").trim().lowercase()
        val (type, repeatKind) = when (rawType) {
            "单次", "once", "single" -> "once" to "once"
            "每日", "每天", "daily" -> "daily" to "daily"
            "每周", "weekly" -> "custom" to "weekly"
            "间隔", "每隔n天", "interval" -> "custom" to "interval"
            "每月", "monthly" -> "custom" to "monthly"
            else -> {
                issue("任务类型", "请填写单次、每日、每周、间隔或每月")
                "" to ""
            }
        }
        val executeDate = value("执行日期").takeIf(String::isNotBlank)
        val startText = if (type == "once" && executeDate != null) executeDate else value("开始日期")
        val startDate = parseDate(startText)
        if (startDate == null) issue(if (type == "once") "执行日期" else "开始日期", "日期无效，请使用 YYYY-MM-DD")
        val endText = value("结束日期").takeIf(String::isNotBlank)
        val endDate = endText?.let(::parseDate)
        if (endText != null && endDate == null) issue("结束日期", "日期无效，请使用 YYYY-MM-DD")
        if (startDate != null && endDate != null && endDate < startDate) issue("结束日期", "不能早于开始日期")
        val effectiveText = value("周期规则生效日期").takeIf(String::isNotBlank)
        val ruleEffectiveFrom = effectiveText?.let(::parseDate)
        if (effectiveText != null && ruleEffectiveFrom == null) issue("周期规则生效日期", "日期无效，请使用 YYYY-MM-DD")

        val weeklyDaysRaw = value("每周重复日")
        val weeklyParts = weeklyDaysRaw.split(',', '，', ';', '；', ' ').map(String::trim).filter(String::isNotBlank)
        val weeklyNumbers = weeklyParts.mapNotNull(String::toIntOrNull)
        val weeklyDays = weeklyNumbers.distinct().sorted().joinToString(",")
        if (repeatKind == "weekly") {
            if (weeklyNumbers.isEmpty() || weeklyNumbers.size != weeklyParts.size || weeklyNumbers.any { it !in 1..7 }) {
                issue("每周重复日", "请用 1-7 填写周一至周日，例如 1,3,5")
            }
        }
        val intervalValue = value("间隔天数").trim().toIntOrNull()
        val interval = intervalValue ?: 1
        if (repeatKind == "interval" && (intervalValue == null || interval !in 1..36_500)) {
            issue("间隔天数", "必须是 1-36500 的整数")
        }
        val monthlyValue = value("每月执行日").trim().toIntOrNull()
        val monthlyDay = monthlyValue ?: 1
        if (repeatKind == "monthly" && (monthlyValue == null || monthlyDay !in 1..31)) {
            issue("每月执行日", "必须是 1-31 的整数")
        }

        val reminderEnabled = parseBoolean(value("提醒开关"), false)
        if (reminderEnabled == null) issue("提醒开关", "请填写 true/false 或 是/否")
        val reminderTime = value("提醒时间").ifBlank { "09:00" }
        if (runCatching { LocalTime.parse(reminderTime) }.isFailure) issue("提醒时间", "请使用 HH:mm 格式")
        val offsetValue = value("提前提醒分钟数").trim().toIntOrNull()
        val offset = offsetValue ?: 0
        if ((value("提前提醒分钟数").isNotBlank() && offsetValue == null) || offset !in 0..1440) {
            issue("提前提醒分钟数", "必须是 0-1440 的整数")
        }
        val paused = parseBoolean(value("暂停状态"), false)
        if (paused == null) issue("暂停状态", "请填写 true/false 或 是/否")

        if (issues.any { it.rowNumber == row }) return null
        return PlanTransferRecord(
            externalId = externalId.ifBlank { UUID.randomUUID().toString() },
            title = title,
            description = value("任务备注"),
            categoryName = category,
            type = type,
            repeatKind = repeatKind,
            startDate = startDate!!.toString(),
            endDate = endDate?.toString(),
            ruleEffectiveFrom = ruleEffectiveFrom?.toString(),
            weeklyDays = weeklyDays.ifBlank { "1,3,5" },
            intervalDays = interval,
            monthlyDay = monthlyDay,
            reminderEnabled = reminderEnabled!!,
            reminderTime = reminderTime,
            reminderOffset = offset,
            isPaused = paused!!,
            sourceRow = row,
        )
    }

    private fun parseDate(raw: String): LocalDate? {
        val value = raw.trim()
        runCatching { LocalDate.parse(value) }.getOrNull()?.let { return it }
        val formats = listOf(
            DateTimeFormatter.ofPattern("M/d/uuuu", Locale.ROOT),
            DateTimeFormatter.ofPattern("M/d/uu", Locale.ROOT),
            DateTimeFormatter.ofPattern("uuuu/M/d", Locale.ROOT),
            DateTimeFormatter.ofPattern("uuuu.M.d", Locale.ROOT),
            DateTimeFormatter.ofPattern("uuuu年M月d日", Locale.SIMPLIFIED_CHINESE),
        )
        return formats.firstNotNullOfOrNull { formatter -> runCatching { LocalDate.parse(value, formatter) }.getOrNull() }
    }

    private fun parseBoolean(raw: String, default: Boolean): Boolean? = when (raw.trim().lowercase()) {
        "" -> default
        "true", "1", "yes", "y", "是", "开启" -> true
        "false", "0", "no", "n", "否", "关闭" -> false
        else -> null
    }

    private fun parseCsv(text: String): List<List<String>> {
        val source = text.removePrefix("\uFEFF")
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < source.length) {
            val char = source[index]
            if (inQuotes) {
                if (char == '"' && source.getOrNull(index + 1) == '"') {
                    field.append('"'); index++
                } else if (char == '"') inQuotes = false else field.append(char)
            } else when (char) {
                '"' -> if (field.isEmpty()) inQuotes = true else field.append(char)
                ',' -> { row += field.toString(); field.setLength(0) }
                '\n', '\r' -> {
                    if (char == '\r' && source.getOrNull(index + 1) == '\n') index++
                    row += field.toString(); field.setLength(0)
                    rows += row.toList(); row.clear()
                }
                else -> field.append(char)
            }
            index++
        }
        require(!inQuotes) { "CSV 引号未闭合" }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            rows += row.toList()
        }
        return rows
    }

    private fun parseXlsx(bytes: ByteArray): List<List<String>> {
        val files = linkedMapOf<String, ByteArray>()
        var expanded = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    require(files.size < 100) { "Excel 包含过多文件项" }
                    val content = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        expanded += count
                        require(expanded <= MAX_XLSX_EXPANDED_BYTES) { "Excel 内容过大" }
                        content.write(buffer, 0, count)
                    }
                    files[entry.name] = content.toByteArray()
                }
            }
        }
        val sharedStrings = files["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
        val worksheetPath = resolveWorksheet(files)
        val document = secureDocument(files[worksheetPath] ?: throw IllegalArgumentException("Excel 缺少工作表"))
        val rows = document.getElementsByTagName("row")
        val rawRows = buildList {
            for (rowIndex in 0 until rows.length) {
                val rowElement = rows.item(rowIndex) as? Element ?: continue
                val cells = rowElement.getElementsByTagName("c")
                val values = mutableMapOf<Int, String>()
                var maxColumn = -1
                for (cellIndex in 0 until cells.length) {
                    val cell = cells.item(cellIndex) as? Element ?: continue
                    val column = columnIndex(cell.getAttribute("r"))
                    if (column < 0) continue
                    maxColumn = maxOf(maxColumn, column)
                    if (cell.getElementsByTagName("f").length > 0) continue
                    val type = cell.getAttribute("t")
                    val value = when (type) {
                        "inlineStr" -> cell.getElementsByTagName("t").item(0)?.textContent.orEmpty()
                        else -> cell.getElementsByTagName("v").item(0)?.textContent.orEmpty()
                    }
                    values[column] = when (type) {
                        "s" -> sharedStrings.getOrNull(value.toIntOrNull() ?: -1).orEmpty()
                        "b" -> if (value == "1") "true" else "false"
                        else -> value
                    }
                }
                if (maxColumn >= 0) add((0..maxColumn).map { column -> values[column].orEmpty() })
            }
        }
        val headerRow = rawRows.firstOrNull().orEmpty()
        return rawRows.map { cells ->
            cells.mapIndexed { index, value ->
                val header = headerRow.getOrNull(index).orEmpty()
                if (header in setOf("开始日期", "结束日期", "周期规则生效日期", "执行日期") && value.toDoubleOrNull() != null) {
                    runCatching { excelDate(value.toDoubleOrNull()!!) }.getOrDefault(value)
                } else if (header == "提醒时间" && value.toDoubleOrNull() != null) {
                    excelTime(value.toDoubleOrNull()!!)
                } else value
            }
        }
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val document = secureDocument(bytes)
        val items = document.getElementsByTagName("si")
        return (0 until items.length).map { index ->
            val item = items.item(index) as Element
            val textNodes = item.getElementsByTagName("t")
            (0 until textNodes.length).joinToString("") { textNodes.item(it).textContent.orEmpty() }
        }
    }

    private fun resolveWorksheet(files: Map<String, ByteArray>): String {
        val workbookBytes = files["xl/workbook.xml"] ?: return "xl/worksheets/sheet1.xml"
        val workbook = secureDocument(workbookBytes)
        val sheet = workbook.getElementsByTagName("sheet").item(0) as? Element ?: return "xl/worksheets/sheet1.xml"
        val relationId = sheet.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
            .ifBlank { sheet.getAttribute("r:id") }
        val relationshipBytes = files["xl/_rels/workbook.xml.rels"] ?: return "xl/worksheets/sheet1.xml"
        val relations = secureDocument(relationshipBytes).getElementsByTagName("Relationship")
        for (index in 0 until relations.length) {
            val relation = relations.item(index) as? Element ?: continue
            if (relation.getAttribute("Id") != relationId) continue
            val target = relation.getAttribute("Target")
            require(!target.contains(":") && !target.split('/').contains("..")) { "Excel 工作表路径无效" }
            return if (target.startsWith("/")) target.removePrefix("/") else "xl/$target"
        }
        return "xl/worksheets/sheet1.xml"
    }

    private fun secureDocument(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        isExpandEntityReferences = false
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
        runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun excelDate(serial: Double): String = dateOrigin.plusDays(serial.toLong()).toString()

    private fun excelTime(serial: Double): String {
        val minutes = ((serial - kotlin.math.floor(serial)) * 24 * 60).toLong().coerceIn(0, 1439)
        return LocalTime.MIDNIGHT.plusMinutes(minutes).toString().take(5)
    }

    private fun PlanTransferRecord.toCells(): List<String> = listOf(
        externalId,
        title,
        description,
        categoryName,
        when {
            type == "once" -> "单次"
            type == "daily" -> "每日"
            repeatKind == "weekly" -> "每周"
            repeatKind == "interval" -> "间隔"
            else -> "每月"
        },
        if (type == "once") "" else startDate,
        endDate.orEmpty(),
        if (type == "once") "" else ruleEffectiveFrom.orEmpty(),
        if (type == "once") startDate else "",
        if (repeatKind == "weekly") weeklyDays else "",
        if (repeatKind == "interval") intervalDays.toString() else "",
        if (repeatKind == "monthly") monthlyDay.toString() else "",
        reminderEnabled.toString(),
        reminderTime,
        reminderOffset.toString(),
        isPaused.toString(),
    )

    private fun normalizeHeader(header: String): String = header.trim().removePrefix("\uFEFF")

    private fun String.unprotectSpreadsheetText(): String {
        val trimmed = dropWhile(Char::isWhitespace)
        val escapedUserApostrophe = trimmed.startsWith("''") && trimmed.drop(2).dropWhile(Char::isWhitespace).firstOrNull() in listOf('=', '+', '-', '@')
        if (escapedUserApostrophe) return replaceFirst("'", "")
        val protected = trimmed.startsWith("'") && trimmed.drop(1).dropWhile(Char::isWhitespace).firstOrNull() in listOf('=', '+', '-', '@')
        return if (protected) {
            replaceFirst("'", "")
        } else this
    }

    private fun csvCell(value: String): String {
        val safe = safeSpreadsheetText(value)
        return "\"${safe.replace("\"", "\"\"")}\""
    }

    private fun safeSpreadsheetText(value: String): String {
        val trimmed = value.dropWhile(Char::isWhitespace)
        val formulaLike = trimmed.firstOrNull() in listOf('=', '+', '-', '@')
        val apostropheBeforeFormula = trimmed.startsWith("'") && trimmed.drop(1).dropWhile(Char::isWhitespace).firstOrNull() in listOf('=', '+', '-', '@')
        return if (formulaLike || apostropheBeforeFormula) "'$value" else value
    }

    private fun xmlEscape(value: String): String = buildString {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (char == '\t' || char == '\n' || char == '\r' || char.code >= 0x20) append(char)
            }
        }
    }

    private fun columnName(number: Int): String {
        var value = number
        val result = StringBuilder()
        while (value > 0) {
            val digit = (value - 1) % 26
            result.append(('A'.code + digit).toChar())
            value = (value - 1) / 26
        }
        return result.reverse().toString()
    }

    private fun columnIndex(reference: String): Int {
        var value = 0
        var found = false
        for (char in reference) {
            if (!char.isLetter()) break
            found = true
            value = value * 26 + (char.uppercaseChar().code - 'A'.code + 1)
        }
        return if (found) value - 1 else -1
    }
}
