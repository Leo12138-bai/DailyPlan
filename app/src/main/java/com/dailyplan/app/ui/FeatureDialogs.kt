package com.dailyplan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dailyplan.app.data.CategoryEntity
import com.dailyplan.app.data.PlanExportOptions
import com.dailyplan.app.data.PlanExportScope
import com.dailyplan.app.data.PlanImportPreview
import com.dailyplan.app.data.TaskEntity
import com.dailyplan.app.ui.TransferUiState
import java.time.LocalDate

@Composable
fun CategoryFilterRow(
    categories: List<CategoryEntity>,
    selected: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FilterChip(selected == "all", { onSelected("all") }, label = { Text("全部") }) }
        items(categories, key = CategoryEntity::id) { category ->
            FilterChip(selected == category.id.toString(), { onSelected(category.id.toString()) }, label = { Text(category.name) })
        }
        item { FilterChip(selected == "unclassified", { onSelected("unclassified") }, label = { Text("未分类") }) }
    }
}

@Composable
fun SelectionHeader(
    selectedTaskIds: Set<Long>,
    visibleIds: Set<Long>,
    onSelectAll: (Set<Long>) -> Unit,
    onExit: () -> Unit,
) {
    val allVisibleSelected = visibleIds.isNotEmpty() && visibleIds.all { it in selectedTaskIds }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("已选 ${selectedTaskIds.size} 项", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = { onSelectAll(if (allVisibleSelected) emptySet() else visibleIds) }, enabled = visibleIds.isNotEmpty()) {
            Text(if (allVisibleSelected) "取消全选" else "全选")
        }
        TextButton(onClick = onExit) { Text("退出") }
    }
}

@Composable
fun BatchActionBar(
    count: Int,
    onCategory: () -> Unit,
    onDate: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$count 项", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 6.dp))
        TextButton(onClick = onCategory) { Text("分类") }
        TextButton(onClick = onDate) { Text("日期") }
        TextButton(onClick = onPause) { Text("暂停") }
        TextButton(onClick = onResume) { Text("恢复") }
        TextButton(onClick = onExport) { Text("导出") }
        TextButton(onClick = onDelete) { Text("删除") }
        TextButton(onClick = onCancel) { Text("取消") }
    }
}

private val categoryColors = listOf(
    0xFF4F6BED.toLong(), 0xFF7B61A8.toLong(), 0xFF168A72.toLong(),
    0xFFD47D35.toLong(), 0xFFD24D57.toLong(), 0xFF3182A5.toLong(),
)

@Composable
fun CategoryManagementDialog(
    categories: List<CategoryEntity>,
    taskCounts: Map<Long, Int>,
    onDismiss: () -> Unit,
    onAdd: (String, Long) -> Unit,
    onEdit: (CategoryEntity, String, Long) -> Unit,
    onDelete: (CategoryEntity) -> Unit,
    onMove: (Long, Int) -> Unit,
) {
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CategoryEntity?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("任务分类管理", style = MaterialTheme.typography.headlineSmall)
                Text("旧任务会显示为「未分类」。删除分类只会把任务移入未分类。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = { creating = true }) { Text("新建分类") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    categories.forEachIndexed { index, category ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(16.dp).background(Color(category.color), CircleShape))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(category.name, style = MaterialTheme.typography.titleSmall)
                                    Text("${taskCounts[category.id] ?: 0} 项任务", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { onMove(category.id, -1) }, enabled = index > 0) { Text("上移") }
                                TextButton(onClick = { onMove(category.id, 1) }, enabled = index < categories.lastIndex) { Text("下移") }
                                TextButton(onClick = { editing = category }) { Text("编辑") }
                                TextButton(onClick = { deleting = category }) { Text("删除") }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("完成") }
                }
            }
        }
    }

    if (creating || editing != null) {
        val category = editing
        CategoryEditorDialog(
            initialName = category?.name.orEmpty(),
            initialColor = category?.color ?: categoryColors.first(),
            title = if (category == null) "新建分类" else "编辑分类",
            onDismiss = { creating = false; editing = null },
            onSave = { name, color ->
                if (category == null) onAdd(name, color) else onEdit(category, name, color)
                creating = false
                editing = null
            },
        )
    }
    deleting?.let { category ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除「${category.name}」？") },
            text = { Text("该分类下有 ${taskCounts[category.id] ?: 0} 项任务。任务和完成记录会保留，任务会转入「未分类」。") },
            confirmButton = { TextButton(onClick = { onDelete(category); deleting = null }) { Text("删除分类") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun CategoryEditorDialog(
    initialName: String,
    initialColor: Long,
    title: String,
    onDismiss: () -> Unit,
    onSave: (String, Long) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var color by remember(initialColor) { mutableStateOf(initialColor) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it; error = null }, label = { Text("分类名称") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    categoryColors.forEach { candidate ->
                        Box(
                            Modifier.size(34.dp).background(Color(candidate), CircleShape)
                                .clickable { color = candidate },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (color == candidate) Text("✓", color = Color.White)
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) error = "分类名称不能为空" else onSave(name.trim(), color)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun PlanTransferDialog(
    categories: List<CategoryEntity>,
    selectedTaskIds: Set<Long>,
    state: TransferUiState,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
    onExport: (String, PlanExportOptions) -> Unit,
) {
    var scope by remember(selectedTaskIds) { mutableStateOf(if (selectedTaskIds.isNotEmpty()) PlanExportScope.SELECTED else PlanExportScope.ALL) }
    var format by remember { mutableStateOf("csv") }
    var fromText by remember { mutableStateOf(LocalDate.now().withDayOfMonth(1).toString()) }
    var toText by remember { mutableStateOf(LocalDate.now().toString()) }
    var categorySelection by remember { mutableStateOf("unclassified") }
    var categoryMenu by remember { mutableStateOf(false) }
    val from = runCatching { LocalDate.parse(fromText) }.getOrNull()
    val to = runCatching { LocalDate.parse(toText) }.getOrNull()
    val dateValid = scope != PlanExportScope.DATE_RANGE || (from != null && to != null && to >= from)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.88f), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(20.dp)) {
                Text("计划导入与导出", style = MaterialTheme.typography.headlineSmall)
                Text("仅处理任务计划，不导出历史完成记录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("导出范围", style = MaterialTheme.typography.titleMedium)
                    ExportScopeChoice(scope == PlanExportScope.ALL, "全部任务计划") { scope = PlanExportScope.ALL }
                    ExportScopeChoice(scope == PlanExportScope.ACTIVE, "未完成的单次任务和有效重复任务") { scope = PlanExportScope.ACTIVE }
                    ExportScopeChoice(scope == PlanExportScope.DATE_RANGE, "在日期范围内有执行日期的任务") { scope = PlanExportScope.DATE_RANGE }
                    if (scope == PlanExportScope.DATE_RANGE) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(fromText, { fromText = it }, label = { Text("开始日期") }, singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedTextField(toText, { toText = it }, label = { Text("结束日期") }, singleLine = true, modifier = Modifier.weight(1f))
                        }
                        if (!dateValid) Text("日期无效，或结束日期早于开始日期。", color = MaterialTheme.colorScheme.error)
                    }
                    ExportScopeChoice(scope == PlanExportScope.CATEGORY, "指定分类") { scope = PlanExportScope.CATEGORY }
                    if (scope == PlanExportScope.CATEGORY) {
                        Box {
                            TextButton(onClick = { categoryMenu = true }) {
                                Text(categories.firstOrNull { it.id.toString() == categorySelection }?.name ?: "未分类")
                            }
                            DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                                DropdownMenuItem(text = { Text("未分类") }, onClick = { categorySelection = "unclassified"; categoryMenu = false })
                                categories.forEach { category ->
                                    DropdownMenuItem(text = { Text(category.name) }, onClick = { categorySelection = category.id.toString(); categoryMenu = false })
                                }
                            }
                        }
                    }
                    if (selectedTaskIds.isNotEmpty()) {
                        ExportScopeChoice(scope == PlanExportScope.SELECTED, "当前多选的 ${selectedTaskIds.size} 项任务") { scope = PlanExportScope.SELECTED }
                    }
                    Text("文件格式", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = format == "csv", onClick = { format = "csv" }, label = { Text("CSV") })
                        FilterChip(selected = format == "xlsx", onClick = { format = "xlsx" }, label = { Text("Excel (.xlsx)") })
                    }
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onImport, enabled = !state.isWorking) { Text("选择文件导入") }
                    TextButton(
                        onClick = { onExport("DailyPlan-template.$format", PlanExportOptions(PlanExportScope.TEMPLATE)) },
                        enabled = !state.isWorking,
                    ) { Text("空白模板") }
                    Button(
                        onClick = {
                            val categoryId = categorySelection.toLongOrNull()
                            val options = PlanExportOptions(scope, from, to, categoryId, selectedTaskIds)
                            onExport("DailyPlan-${LocalDate.now()}.$format", options)
                        },
                        enabled = !state.isWorking && dateValid && (scope != PlanExportScope.SELECTED || selectedTaskIds.isNotEmpty()),
                    ) { Text("导出") }
                }
            }
        }
    }
}

@Composable
private fun ExportScopeChoice(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
fun PlanImportPreviewDialog(
    preview: PlanImportPreview,
    fileName: String,
    isWorking: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Boolean, Boolean) -> Unit,
) {
    var skipErrors by remember(preview) { mutableStateOf(false) }
    var importDuplicatesAsNew by remember(preview) { mutableStateOf(false) }
    val issues = preview.parsed.issues
    val canImport = preview.parsed.records.isNotEmpty() && (issues.isEmpty() || skipErrors) && !isWorking

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.9f), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(18.dp)) {
                Text("导入预览", style = MaterialTheme.typography.headlineSmall)
                Text(fileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("总任务 ${preview.parsed.totalRows} 项 · 可导入 ${preview.parsed.records.size} 项 · 疑似重复 ${preview.duplicateRows.size} 项 · 错误 ${issues.size} 条")
                if (preview.categoriesToCreate.isNotEmpty()) {
                    Text("将新建分类：${preview.categoriesToCreate.joinToString("、")}", color = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    if (issues.isNotEmpty()) {
                        Text("请修正文件，或勾选后明确跳过错误行：", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        issues.take(200).forEach { issue ->
                            Text("第 ${issue.rowNumber} 行 · ${issue.field}：${issue.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        if (issues.size > 200) Text("另有 ${issues.size - 200} 条错误未展开。", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = skipErrors, onCheckedChange = { skipErrors = it })
                            Text("确认跳过全部错误行")
                        }
                    }
                    if (preview.duplicateRows.isNotEmpty()) {
                        Text("默认跳过重复任务，不会覆盖已有任务或完成记录。")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = importDuplicatesAsNew, onCheckedChange = { importDuplicatesAsNew = it })
                            Text("仍将重复项作为新任务导入")
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !isWorking) { Text("取消") }
                    Button(onClick = { onConfirm(skipErrors, importDuplicatesAsNew) }, enabled = canImport) {
                        if (isWorking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("确认导入")
                    }
                }
            }
        }
    }
}

@Composable
fun BulkCategoryDialog(categories: List<CategoryEntity>, onDismiss: () -> Unit, onConfirm: (Long?) -> Unit) {
    var categoryId by remember { mutableStateOf<Long?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量修改分类") },
        text = {
            Column(Modifier.height(350.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().clickable { categoryId = null }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = categoryId == null, onClick = { categoryId = null }); Text("未分类")
                }
                categories.forEach { category ->
                    Row(Modifier.fillMaxWidth().clickable { categoryId = category.id }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = categoryId == category.id, onClick = { categoryId = category.id }); Text(category.name)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(categoryId) }) { Text("应用分类") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun BulkDateDialog(
    selectedTasks: List<TaskEntity>,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    var dateText by remember { mutableStateOf(LocalDate.now().toString()) }
    val date = runCatching { LocalDate.parse(dateText) }.getOrNull()
    val repeatCount = selectedTasks.count { it.type != "once" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量修改日期") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("将修改 ${selectedTasks.size} 项任务：单次任务改为该执行日期，${repeatCount} 项周期任务改为该周期起始日期。")
                Text("周期规则从今天起按新起始日期重新计算；过去的完成记录不会修改。")
                OutlinedTextField(dateText, { dateText = it }, label = { Text("日期（YYYY-MM-DD）") }, singleLine = true)
                if (date == null) Text("日期格式无效。", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { date?.let(onConfirm) }, enabled = date != null) { Text("确认修改") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun BulkDeleteDialog(taskCount: Int, hasRepeating: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除 $taskCount 项任务？") },
        text = { Text(if (hasRepeating) "重复任务会删除整个计划，不只是当天的一次记录。任务以归档方式移除，历史执行记录保留。" else "任务会以归档方式移除，历史执行记录保留。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
