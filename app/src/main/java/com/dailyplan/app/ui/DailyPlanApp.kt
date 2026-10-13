package com.dailyplan.app.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dailyplan.app.data.OccurrenceEntity
import com.dailyplan.app.data.CategoryEntity
import com.dailyplan.app.data.PlanExportOptions
import com.dailyplan.app.data.PlanExportScope
import com.dailyplan.app.data.TaskEntity
import com.dailyplan.app.BuildConfig
import com.dailyplan.app.update.ApkInstaller
import com.dailyplan.app.update.UpdateRelease
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch

private enum class AppTab(val label: String) {
    TODAY("今日"), PLAN("计划"), HISTORY("记录"), SETTINGS("设置")
}

private enum class ThemeChoice(val label: String) {
    SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色")
}

@Composable
fun DailyPlanApp(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val accountState by viewModel.accountState.collectAsState()
    val transferState by viewModel.transferState.collectAsState()
    val operationMessage by viewModel.operationMessage.collectAsState()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(AppTab.TODAY) }
    var selectedDate by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var month by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var editorTask by remember { mutableStateOf<TaskEntity?>(null) }
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var showTransfer by rememberSaveable { mutableStateOf(false) }
    var showCategoryManager by rememberSaveable { mutableStateOf(false) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedTaskIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var categoryFilter by rememberSaveable { mutableStateOf("all") }
    var batchDialog by rememberSaveable { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf<Pair<String, PlanExportOptions>?>(null) }
    var theme by remember { mutableStateOf(loadTheme(context)) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val csvExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val pending = pendingExport
        if (uri != null && pending != null) viewModel.exportFile(uri, pending.first, pending.second)
        pendingExport = null
    }
    val xlsxExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    ) { uri ->
        val pending = pendingExport
        if (uri != null && pending != null) viewModel.exportFile(uri, pending.first, pending.second)
        pendingExport = null
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            showTransfer = false
            viewModel.importFile(uri)
        }
    }
    val startExport: (String, PlanExportOptions) -> Unit = { fileName, options ->
        pendingExport = fileName to options
        showTransfer = false
        if (fileName.endsWith(".xlsx", ignoreCase = true)) xlsxExportLauncher.launch(fileName)
        else csvExportLauncher.launch(fileName)
    }
    val startSelection: (Long) -> Unit = { id -> selectionMode = true; selectedTaskIds = setOf(id) }
    val toggleSelection: (Long) -> Unit = { id ->
        selectedTaskIds = if (id in selectedTaskIds) selectedTaskIds - id else selectedTaskIds + id
    }
    val exitSelection = {
        selectionMode = false
        selectedTaskIds = emptySet()
        batchDialog = ""
    }
    LaunchedEffect(operationMessage) {
        operationMessage?.let { feedback ->
            val result = snackbarHostState.showSnackbar(
                message = feedback.message,
                actionLabel = if (feedback.undoAvailable) "撤销" else null,
            )
            viewModel.clearOperationMessage()
            if (result == SnackbarResult.ActionPerformed) viewModel.undoLastBatch()
        }
    }
    LaunchedEffect(transferState.message) {
        transferState.message?.let { snackbarHostState.showSnackbar(it); viewModel.clearTransferMessage() }
    }
    val swipeComplete: (OccurrenceEntity) -> Unit = { row ->
        if (!row.isCompleted) {
            viewModel.setCompletion(row, true)
            coroutineScope.launch {
                if (snackbarHostState.showSnackbar("已完成", "撤销") == SnackbarResult.ActionPerformed) {
                    viewModel.setCompletion(row, false)
                }
            }
        }
    }
    val isDark = when (theme) {
        ThemeChoice.DARK -> true
        ThemeChoice.LIGHT -> false
        ThemeChoice.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    val colors = if (isDark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF3568D4),
        secondary = androidx.compose.ui.graphics.Color(0xFF15966D),
        tertiary = androidx.compose.ui.graphics.Color(0xFFE68B2C),
        background = androidx.compose.ui.graphics.Color(0xFFF5F7FA),
        surface = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    )

    MaterialTheme(colorScheme = colors) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                Column {
                    if (selectionMode) {
                        BatchActionBar(
                            count = selectedTaskIds.size,
                            onCategory = { batchDialog = "category" },
                            onDate = { batchDialog = "date" },
                            onPause = { viewModel.bulkPause(selectedTaskIds, true); exitSelection() },
                            onResume = { viewModel.bulkPause(selectedTaskIds, false); exitSelection() },
                            onExport = { showTransfer = true },
                            onDelete = { batchDialog = "delete" },
                            onCancel = exitSelection,
                        )
                    }
                    NavigationBar {
                        AppTab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = {
                                    tab = item
                                    if (item == AppTab.HISTORY) viewModel.loadHistory()
                                    if (item == AppTab.TODAY) {
                                        selectedDate = LocalDate.now().toString()
                                        month = YearMonth.now().toString()
                                        viewModel.loadHome()
                                    }
                                },
                                icon = { Text(when (item) { AppTab.TODAY -> "●"; AppTab.PLAN -> "▦"; AppTab.HISTORY -> "✓"; AppTab.SETTINGS -> "⚙" }) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
            },
            floatingActionButton = {
                if (tab == AppTab.TODAY || tab == AppTab.PLAN) {
                    FloatingActionButton(onClick = { editorTask = null; showEditor = true }) {
                        Icon(Icons.Default.Add, contentDescription = "新建任务")
                    }
                }
            },
        ) { padding ->
            when (tab) {
                AppTab.TODAY -> TodayScreen(
                    state = state,
                    onToggle = viewModel::toggle,
                    onSwipeComplete = swipeComplete,
                    onEdit = { editorTask = it; showEditor = true },
                    onPause = { task, paused -> viewModel.pause(task, paused) },
                    onDelete = viewModel::delete,
                    categories = state.categories,
                    categoryFilter = categoryFilter,
                    onCategoryFilter = { categoryFilter = it },
                    selectionMode = selectionMode,
                    selectedTaskIds = selectedTaskIds,
                    onStartSelection = startSelection,
                    onToggleSelection = toggleSelection,
                    onSelectAll = { ids -> selectionMode = true; selectedTaskIds = ids },
                    onExitSelection = exitSelection,
                    modifier = Modifier.padding(padding),
                )
                AppTab.PLAN -> PlanScreen(
                    state = state,
                    month = YearMonth.parse(month),
                    selectedDate = LocalDate.parse(selectedDate),
                    onMonthChange = {
                        month = it.toString()
                        selectedDate = it.atDay(LocalDate.parse(selectedDate).dayOfMonth.coerceAtMost(it.lengthOfMonth())).toString()
                        viewModel.loadAround(it.atDay(1))
                    },
                    onSelectDate = { selectedDate = it.toString(); viewModel.loadAround(it) },
                    onToggle = viewModel::toggle,
                    onSwipeComplete = swipeComplete,
                    onEdit = { editorTask = it; showEditor = true },
                    onPause = { task, paused -> viewModel.pause(task, paused) },
                    onDelete = viewModel::delete,
                    categories = state.categories,
                    categoryFilter = categoryFilter,
                    onCategoryFilter = { categoryFilter = it },
                    selectionMode = selectionMode,
                    selectedTaskIds = selectedTaskIds,
                    onStartSelection = startSelection,
                    onToggleSelection = toggleSelection,
                    onSelectAll = { ids -> selectionMode = true; selectedTaskIds = ids },
                    onExitSelection = exitSelection,
                    modifier = Modifier.padding(padding),
                )
                AppTab.HISTORY -> HistoryScreen(state, modifier = Modifier.padding(padding))
                AppTab.SETTINGS -> SettingsScreen(
                    account = accountState,
                    onSignIn = viewModel::signIn,
                    onResetPassword = viewModel::resetPassword,
                    onSignOut = viewModel::signOut,
                    theme = theme,
                    onTheme = { theme = it; saveTheme(context, it) },
                    updateState = updateState,
                    onCheckUpdates = { viewModel.checkForUpdate(manual = true) },
                    onAutomaticChecksChanged = viewModel::setAutomaticChecksEnabled,
                    onManageCategories = { showCategoryManager = true },
                    onPlanTransfer = { showTransfer = true },
                    modifier = Modifier.padding(padding),
                )
            }
        }

        if (showEditor) {
            TaskEditorDialog(
                initial = editorTask,
                categories = state.categories,
                onDismiss = { showEditor = false },
                defaultDate = selectedDate,
                onSave = { task -> viewModel.save(task); showEditor = false },
                onCreateCategory = { name, done ->
                    coroutineScope.launch {
                        runCatching { viewModel.createCategoryNow(name, 0xFF4F6BED.toLong()) }
                            .onSuccess { done(it.id, null) }
                            .onFailure { done(null, it.message ?: "创建分类失败") }
                    }
                },
            )
        }

        updateState.release?.let { release ->
            UpdateDialog(
                release = release,
                updateState = updateState,
                onLater = viewModel::dismissUpdate,
                onDownload = viewModel::downloadUpdate,
            )
        }

        if (showCategoryManager) {
            CategoryManagementDialog(
                categories = state.categories,
                taskCounts = state.tasks.groupingBy { it.categoryId }.eachCount().mapNotNull { (id, count) -> id?.let { it to count } }.toMap(),
                onDismiss = { showCategoryManager = false },
                onAdd = viewModel::addCategory,
                onEdit = viewModel::renameCategory,
                onDelete = viewModel::deleteCategory,
                onMove = viewModel::moveCategory,
            )
        }

        if (showTransfer) {
            PlanTransferDialog(
                categories = state.categories,
                selectedTaskIds = selectedTaskIds.takeIf { selectionMode }.orEmpty(),
                state = transferState,
                onDismiss = { showTransfer = false },
                onImport = {
                    importLauncher.launch(arrayOf("text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                },
                onExport = startExport,
            )
        }

        val importPreview = transferState.preview
        if (importPreview != null) {
            PlanImportPreviewDialog(
                preview = importPreview,
                fileName = transferState.fileName,
                isWorking = transferState.isWorking,
                onDismiss = viewModel::dismissImportPreview,
                onConfirm = viewModel::confirmImport,
            )
        } else if (transferState.isWorking) {
            AlertDialog(onDismissRequest = {}, title = { Text("正在处理计划文件") }, text = { CircularProgressIndicator() }, confirmButton = {})
        }

        if (batchDialog == "category") {
            BulkCategoryDialog(state.categories, onDismiss = { batchDialog = "" }, onConfirm = { categoryId ->
                viewModel.bulkCategory(selectedTaskIds, categoryId); exitSelection()
            })
        } else if (batchDialog == "date") {
            BulkDateDialog(state.tasks.filter { it.id in selectedTaskIds }, onDismiss = { batchDialog = "" }, onConfirm = { date ->
                viewModel.bulkDate(selectedTaskIds, date); exitSelection()
            })
        } else if (batchDialog == "delete") {
            val selectedTasks = state.tasks.filter { it.id in selectedTaskIds }
            BulkDeleteDialog(selectedTasks.size, selectedTasks.any { it.type != "once" }, onDismiss = { batchDialog = "" }, onConfirm = {
                viewModel.bulkDelete(selectedTaskIds); exitSelection()
            })
        }
    }
}

@Composable
private fun TodayScreen(
    state: PlanState,
    onToggle: (OccurrenceEntity) -> Unit,
    onSwipeComplete: (OccurrenceEntity) -> Unit,
    onEdit: (TaskEntity) -> Unit,
    onPause: (TaskEntity, Boolean) -> Unit,
    onDelete: (TaskEntity) -> Unit,
    categories: List<CategoryEntity>,
    categoryFilter: String,
    onCategoryFilter: (String) -> Unit,
    selectionMode: Boolean,
    selectedTaskIds: Set<Long>,
    onStartSelection: (Long) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onSelectAll: (Set<Long>) -> Unit,
    onExitSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
    val taskById = state.tasks.associateBy(TaskEntity::id)
    fun included(taskId: Long): Boolean = when (categoryFilter) {
        "all" -> true
        "unclassified" -> taskById[taskId]?.categoryId == null
        else -> taskById[taskId]?.categoryId?.toString() == categoryFilter
    }
    val todayRows = state.occurrences.filter { it.date == today.toString() && included(it.taskId) }
    val completed = todayRows.count(OccurrenceEntity::isCompleted)
    val progress = if (todayRows.isEmpty()) 0 else (completed * 100 / todayRows.size)
    val overdue = state.overdueOnce.filterNot(OccurrenceEntity::isCompleted).filter { included(it.taskId) }
    val visibleIds = (todayRows + overdue).mapTo(linkedSetOf(), OccurrenceEntity::taskId)

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Spacer(Modifier.height(16.dp))
            Text("今天，${today.monthValue}月${today.dayOfMonth}日", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.SIMPLIFIED_CHINESE) + " · 从现在开始完成今天的计划", color = MaterialTheme.colorScheme.onSurfaceVariant)
            CategoryFilterRow(categories, categoryFilter, onCategoryFilter, Modifier.padding(top = 10.dp))
            if (selectionMode) SelectionHeader(selectedTaskIds, visibleIds, onSelectAll, onExitSelection)
            Spacer(Modifier.height(20.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text(if (categoryFilter == "all") "今日进度" else "当前分类进度", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator(progress = { if (todayRows.isEmpty()) 0f else progress / 100f }, modifier = Modifier.size(42.dp), strokeWidth = 5.dp)
                        Column {
                            Text("$completed / ${todayRows.size} 项", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(if (todayRows.isEmpty()) "还没有安排今天的任务" else "已完成 $progress%")
                        }
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
        }
        if (overdue.isNotEmpty()) {
            item { Text("逾期任务", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary) }
            items(overdue, key = { "overdue-${it.taskId}-${it.date}" }) { row ->
                val task = taskById[row.taskId]
                TaskRow(row, task, onToggle, onSwipeComplete, { task?.let(onEdit) }, { task?.let { onPause(it, !it.isPaused) } }, { task?.let(onDelete) },
                    category = categories.firstOrNull { it.id == task?.categoryId }, selectionMode = selectionMode,
                    selected = row.taskId in selectedTaskIds, onStartSelection = { task?.let { onStartSelection(it.id) } }, onSelect = { task?.let { onToggleSelection(it.id) } })
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
        item { Text("待办清单", style = MaterialTheme.typography.titleMedium); Text("点击圆圈完成 · 右滑完成 · 左滑编辑", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val currentRows = todayRows.sortedWith(compareBy<OccurrenceEntity> { it.isCompleted }.thenBy { it.title })
        if (currentRows.isEmpty()) item { EmptyCard("今天还没有任务，添加一个计划吧") }
        items(currentRows, key = { "today-${it.taskId}-${it.date}" }) { row ->
            val task = taskById[row.taskId]
            TaskRow(row, task, onToggle, onSwipeComplete, { task?.let(onEdit) }, { task?.let { onPause(it, !it.isPaused) } }, { task?.let(onDelete) },
                category = categories.firstOrNull { it.id == task?.categoryId }, selectionMode = selectionMode,
                selected = row.taskId in selectedTaskIds, onStartSelection = { task?.let { onStartSelection(it.id) } }, onSelect = { task?.let { onToggleSelection(it.id) } })
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
private fun PlanScreen(
    state: PlanState,
    month: YearMonth,
    selectedDate: LocalDate,
    onMonthChange: (YearMonth) -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onToggle: (OccurrenceEntity) -> Unit,
    onSwipeComplete: (OccurrenceEntity) -> Unit,
    onEdit: (TaskEntity) -> Unit,
    onPause: (TaskEntity, Boolean) -> Unit,
    onDelete: (TaskEntity) -> Unit,
    categories: List<CategoryEntity>,
    categoryFilter: String,
    onCategoryFilter: (String) -> Unit,
    selectionMode: Boolean,
    selectedTaskIds: Set<Long>,
    onStartSelection: (Long) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onSelectAll: (Set<Long>) -> Unit,
    onExitSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val taskById = state.tasks.associateBy(TaskEntity::id)
    fun included(taskId: Long): Boolean = when (categoryFilter) {
        "all" -> true
        "unclassified" -> taskById[taskId]?.categoryId == null
        else -> taskById[taskId]?.categoryId?.toString() == categoryFilter
    }
    val filteredOccurrences = state.occurrences.filter { included(it.taskId) }
    val countByDay = filteredOccurrences.groupingBy { it.date }.eachCount()
    val startOffset = month.atDay(1).dayOfWeek.value - 1
    val cells: List<LocalDate?> = List(startOffset) { null } + (1..month.lengthOfMonth()).map(month::atDay)
    val selectedRows = filteredOccurrences.filter { it.date == selectedDate.toString() }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Spacer(Modifier.height(16.dp))
            Text("计划", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            CategoryFilterRow(categories, categoryFilter, onCategoryFilter, Modifier.padding(top = 10.dp))
            if (selectionMode) SelectionHeader(selectedTaskIds, selectedRows.mapTo(linkedSetOf(), OccurrenceEntity::taskId), onSelectAll, onExitSelection)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onMonthChange(month.minusMonths(1)) }) { Icon(Icons.Default.ChevronLeft, "上个月") }
                Text("${month.year}年${month.monthValue}月", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { onMonthChange(month.plusMonths(1)) }) { Icon(Icons.Default.ChevronRight, "下个月") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.height(8.dp))
        }
        item {
            LazyVerticalGrid(columns = GridCells.Fixed(7), modifier = Modifier.height(((cells.size + 6) / 7 * 48).dp)) {
                items(cells) { date ->
                    if (date == null) Spacer(Modifier.size(42.dp)) else {
                        val selected = date == selectedDate
                        Column(
                            Modifier.size(42.dp).padding(2.dp)
                                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                                .clickable { onSelectDate(date) }
                                .padding(top = 3.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(date.dayOfMonth.toString(), color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            if (countByDay[date.toString()] ?: 0 > 0) Box(Modifier.size(4.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape))
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("${selectedDate.monthValue}月${selectedDate.dayOfMonth}日的计划", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        if (selectedRows.isEmpty()) item { EmptyCard("这一天没有安排任务") }
        items(selectedRows, key = { "plan-${it.taskId}-${it.date}" }) { row ->
            val task = taskById[row.taskId]
            TaskRow(row, task, onToggle, onSwipeComplete, { task?.let(onEdit) }, { task?.let { onPause(it, !it.isPaused) } }, { task?.let(onDelete) },
                category = categories.firstOrNull { it.id == task?.categoryId }, selectionMode = selectionMode,
                selected = row.taskId in selectedTaskIds, onStartSelection = { task?.let { onStartSelection(it.id) } }, onSelect = { task?.let { onToggleSelection(it.id) } })
        }
        item { Spacer(Modifier.height(90.dp)) }
    }
}

@Composable
private fun HistoryScreen(state: PlanState, modifier: Modifier = Modifier) {
    val grouped = state.occurrences.groupBy { it.date }.toSortedMap(compareByDescending { it })
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Spacer(Modifier.height(18.dp))
            Text("任务记录", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("按日期查看已完成和未完成的任务", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
        }
        if (grouped.isEmpty()) item { EmptyCard("还没有历史记录") }
        grouped.forEach { (date, rows) ->
            val done = rows.count(OccurrenceEntity::isCompleted)
            item(key = "history-head-$date") {
                Text("${LocalDate.parse(date).format(DateTimeFormatter.ofPattern("M月d日 · EEEE", Locale.SIMPLIFIED_CHINESE))}    $done / ${rows.size} 项", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            }
            items(rows, key = { "history-${it.taskId}-${it.date}" }) { row -> HistoryRow(row) }
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

@Composable
private fun HistoryRow(row: OccurrenceEntity) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).background(if (row.isCompleted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant, CircleShape), Alignment.Center) {
            if (row.isCompleted) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(row.title, color = MaterialTheme.colorScheme.onSurfaceVariant, textDecoration = if (row.isCompleted) TextDecoration.LineThrough else TextDecoration.None)
        Spacer(Modifier.weight(1f))
        Text(if (row.isCompleted) "已完成" else "未完成", style = MaterialTheme.typography.bodySmall, color = if (row.isCompleted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun SettingsScreen(
    account: AccountUiState,
    onSignIn: (String, String, Boolean) -> Unit,
    onResetPassword: (String) -> Unit,
    onSignOut: () -> Unit,
    theme: ThemeChoice,
    onTheme: (ThemeChoice) -> Unit,
    updateState: UpdateUiState,
    onCheckUpdates: () -> Unit,
    onAutomaticChecksChanged: (Boolean) -> Unit,
    onManageCategories: () -> Unit,
    onPlanTransfer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val notificationPermission = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val exactAllowed = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()

    var accountEmail by rememberSaveable { mutableStateOf("") }
    var accountPassword by remember { mutableStateOf("") }
    var creatingAccount by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(account.email) {
        if (account.email != null) accountPassword = ""
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("设置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        Text("账号与云端同步", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Card(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (account.email != null) {
                    Text("已登录：${account.email}", style = MaterialTheme.typography.titleSmall)
                    Text(account.syncMessage.ifBlank { "正在连接云端…" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onSignOut, enabled = !account.isWorking) { Text(if (account.isWorking) "正在退出…" else "退出登录") }
                } else {
                    Text("登录后，任务、分类和完成记录会在已登录设备间同步。现有本机数据会在首次登录时合并到云端。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = accountEmail,
                        onValueChange = { accountEmail = it },
                        label = { Text("邮箱地址") },
                        singleLine = true,
                        enabled = account.configured && !account.isWorking,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = accountPassword,
                        onValueChange = { accountPassword = it },
                        label = { Text("密码（至少 6 位）") },
                        singleLine = true,
                        enabled = account.configured && !account.isWorking,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { onSignIn(accountEmail, accountPassword, creatingAccount) },
                            enabled = account.configured && !account.isWorking && accountEmail.isNotBlank() && accountPassword.length >= 6,
                        ) { Text(if (account.isWorking) "处理中…" else if (creatingAccount) "创建账号" else "登录") }
                        TextButton(onClick = { creatingAccount = !creatingAccount }) {
                            Text(if (creatingAccount) "返回登录" else "注册新账号")
                        }
                        if (!creatingAccount) TextButton(onClick = { onResetPassword(accountEmail) }, enabled = accountEmail.isNotBlank()) { Text("忘记密码") }
                    }
                }
                account.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (!account.configured) Text("Firebase 尚未配置：需先把 Firebase 的 google-services.json 放入 app 文件夹。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("提醒权限", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Card(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("通知权限")
                        Text(if (notificationPermission) "已允许接收提醒" else "未允许，任务提醒不会显示", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!notificationPermission) TextButton(onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("开启") }
                }
                Divider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.NotificationsActive, null, tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("准时提醒")
                        Text(if (exactAllowed) "精确闹钟可用" else "当前为系统尽力提醒，时间可能延后", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (Build.VERSION.SDK_INT >= 31 && !exactAllowed) TextButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                        }
                    }) { Text("设置") }
                }
                Text("提醒还会遵循手机的勿扰模式、省电策略和锁屏隐私设置。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("计划数据", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Card(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("计划导入与导出", style = MaterialTheme.typography.titleSmall)
                Text("可用 CSV 或 Excel 文件保存、迁移任务计划。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onPlanTransfer) { Text("导入 / 导出") }
                    TextButton(onClick = onManageCategories) { Text("分类管理") }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("外观", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Card(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("主题")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                    ThemeChoice.entries.forEach { choice -> FilterChip(selected = theme == choice, onClick = { onTheme(choice) }, label = { Text(choice.label) }) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("关于与更新", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Card(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("每日计划 · V${BuildConfig.VERSION_NAME}")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("自动检查更新")
                        Text("每 24 小时最多自动检查一次", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = updateState.automaticChecksEnabled, onCheckedChange = onAutomaticChecksChanged)
                }
                Text("最近检查：${formatCheckTime(updateState.lastCheckTime)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onCheckUpdates, enabled = !updateState.isChecking) {
                    if (updateState.isChecking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("检查更新")
                }
                updateState.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("检查失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("任务、记录和提醒设置保存在本机；兼容更新会保留这些数据。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun UpdateDialog(
    release: UpdateRelease,
    updateState: UpdateUiState,
    onLater: () -> Unit,
    onDownload: () -> Unit,
) {
    val context = LocalContext.current
    var installError by remember(release.versionCode) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("发现新版本") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("当前版本：V${BuildConfig.VERSION_NAME}")
                Text("最新版本：V${release.versionName}")
                Text("安装包大小：${String.format(Locale.US, "%.1f MB", release.apkSize / 1_048_576.0)}")
                Text("更新日志", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(release.changelog.ifBlank { "暂无更新说明" }, style = MaterialTheme.typography.bodySmall)
                if (updateState.isDownloading) {
                    LinearProgressIndicator(progress = { updateState.downloadProgress / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("正在下载 ${updateState.downloadProgress}%", style = MaterialTheme.typography.bodySmall)
                }
                updateState.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.contains("失败") || it.contains("不兼容")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                installError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !updateState.isDownloading,
                onClick = {
                    val path = updateState.downloadedApkPath
                    if (path == null) onDownload()
                    else installError = ApkInstaller.requestInstall(context, File(path))
                },
            ) {
                Text(
                    when {
                        updateState.downloadedApkPath != null -> "安装更新"
                        updateState.isDownloading -> "下载中…"
                        updateState.message != null -> "重试下载"
                        else -> "立即更新"
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onLater) { Text("稍后更新") } },
    )
}

private fun formatCheckTime(timestamp: Long): String = if (timestamp <= 0L) {
    "尚未检查"
} else {
    java.time.Instant.ofEpochMilli(timestamp)
        .atZone(java.time.ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskRow(
    row: OccurrenceEntity,
    task: TaskEntity?,
    onToggle: (OccurrenceEntity) -> Unit,
    onSwipeComplete: (OccurrenceEntity) -> Unit,
    onEdit: () -> Unit,
    onPause: () -> Unit,
    onDelete: () -> Unit,
    category: CategoryEntity? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onStartSelection: () -> Unit = {},
    onSelect: () -> Unit = {},
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var dragTotal by remember { mutableFloatStateOf(0f) }
    val date = LocalDate.parse(row.date)
    val old = date < LocalDate.now() && !row.isCompleted
    Card(
        Modifier.fillMaxWidth().padding(vertical = 5.dp)
            .pointerInput(row.taskId, row.date, selectionMode) {
                if (!selectionMode) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, amount -> dragTotal += amount; change.consume() },
                        onDragEnd = {
                            if (dragTotal > 72f) onSwipeComplete(row) else if (dragTotal < -72f) menuExpanded = true
                            dragTotal = 0f
                        },
                        onDragCancel = { dragTotal = 0f },
                    )
                }
            }
            .combinedClickable(
                onClick = { if (selectionMode && task != null) onSelect() },
                onLongClick = { if (!selectionMode && task != null) onStartSelection() },
            ),
        colors = CardDefaults.cardColors(
            containerColor = when {
                selected -> MaterialTheme.colorScheme.primaryContainer
                old -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = if (selectionMode) selected else row.isCompleted, onCheckedChange = {
                if (selectionMode) { if (task != null) onSelect() } else onToggle(row)
            })
            Column(Modifier.weight(1f).clickable {
                if (selectionMode) { if (task != null) onSelect() } else onToggle(row)
            }.padding(vertical = 4.dp)) {
                Text(row.title, fontWeight = FontWeight.Medium, textDecoration = if (row.isCompleted) TextDecoration.LineThrough else TextDecoration.None)
                val detail = buildString {
                    if (row.description.isNotBlank()) append(row.description)
                    if (task != null) {
                        if (isNotEmpty()) append(" · ")
                        append(category?.name ?: "未分类")
                        append(" · ")
                        append(taskSummary(task))
                        if (task.reminderEnabled) append(" · ${task.reminderTime}")
                        if (task.isPaused) append(" · 已暂停")
                    } else if (old) append("逾期")
                }
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            if (!selectionMode) {
                Box {
                    IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.MoreVert, "更多操作") }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text("编辑") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menuExpanded = false; onEdit() })
                        if (task != null) DropdownMenuItem(
                            text = { Text(if (task.isPaused) "恢复任务" else "暂停任务") },
                            leadingIcon = { Icon(if (task.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, null) },
                            onClick = { menuExpanded = false; onPause() },
                        )
                        DropdownMenuItem(text = { Text("删除任务") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menuExpanded = false; showDeleteConfirm = true })
                    }
                }
            }
        }
    }
    if (showDeleteConfirm) AlertDialog(
        onDismissRequest = { showDeleteConfirm = false },
        title = { Text("删除这个任务？") },
        text = { Text("已发生的历史记录会保留。") },
        confirmButton = { TextButton(onClick = { showDeleteConfirm = false; onDelete() }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } },
    )
}

@Composable
private fun EmptyCard(message: String) {
    Card(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(message, Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskEditorDialog(
    initial: TaskEntity?,
    defaultDate: String,
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (TaskEntity) -> Unit,
    onCreateCategory: (String, (Long?, String?) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var notificationsAllowed by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsAllowed = it }
    var title by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
    var description by remember(initial) { mutableStateOf(initial?.description.orEmpty()) }
    var type by remember(initial) { mutableStateOf(initial?.type ?: "daily") }
    var repeatKind by remember(initial) { mutableStateOf(initial?.repeatKind ?: "weekly") }
    var startDate by remember(initial, defaultDate) { mutableStateOf(initial?.startDate ?: defaultDate) }
    var endDate by remember(initial) { mutableStateOf(initial?.endDate.orEmpty()) }
    var interval by remember(initial) { mutableStateOf((initial?.intervalDays ?: 3).toString()) }
    var monthlyDay by remember(initial) { mutableStateOf((initial?.monthlyDay ?: 15).toString()) }
    var weekdays by remember(initial) { mutableStateOf((initial?.weeklyDays ?: "1,3,5").split(',').filter(String::isNotBlank).toSet()) }
    var reminder by remember(initial) { mutableStateOf(initial?.reminderEnabled ?: false) }
    var reminderTime by remember(initial) { mutableStateOf(initial?.reminderTime ?: "09:00") }
    var offset by remember(initial) { mutableIntStateOf(initial?.reminderOffset ?: 0) }
    var selectedCategoryId by remember(initial) { mutableStateOf(initial?.categoryId) }
    var categoryMenu by remember { mutableStateOf(false) }
    var createCategory by remember { mutableStateOf(false) }
    var categoryName by remember { mutableStateOf("") }
    var categoryError by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val days = listOf("一", "二", "三", "四", "五", "六", "日")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建任务" else "编辑任务") },
        text = {
            Column(Modifier.height(560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it; error = null }, label = { Text("任务名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it }, label = { Text("备注（可选）") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Text("任务分类", style = MaterialTheme.typography.labelLarge)
                Box {
                    TextButton(onClick = { categoryMenu = true }) {
                        Text(categories.firstOrNull { it.id == selectedCategoryId }?.name ?: "未分类")
                    }
                    DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                        DropdownMenuItem(text = { Text("未分类") }, onClick = { selectedCategoryId = null; categoryMenu = false })
                        categories.forEach { category ->
                            DropdownMenuItem(text = { Text(category.name) }, onClick = { selectedCategoryId = category.id; categoryMenu = false })
                        }
                        DropdownMenuItem(text = { Text("＋ 新建分类") }, onClick = { categoryMenu = false; categoryName = ""; createCategory = true })
                    }
                }
                Text("执行方式", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("daily" to "每天", "once" to "单次", "custom" to "自定义周期").forEach { (value, label) ->
                        FilterChip(selected = type == value, onClick = { type = value }, label = { Text(label) })
                    }
                }
                OutlinedTextField(startDate, { startDate = it; error = null }, label = { Text(if (type == "once") "执行日期（YYYY-MM-DD）" else "开始日期（YYYY-MM-DD）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (type == "custom") {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("weekly" to "每周", "interval" to "每隔N天", "monthly" to "每月").forEach { (value, label) ->
                            FilterChip(selected = repeatKind == value, onClick = { repeatKind = value }, label = { Text(label) })
                        }
                    }
                    when (repeatKind) {
                        "weekly" -> Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            (1..7).forEach { day -> FilterChip(selected = day.toString() in weekdays, onClick = {
                                weekdays = if (day.toString() in weekdays) weekdays - day.toString() else weekdays + day.toString()
                            }, label = { Text(days[day - 1]) }) }
                        }
                        "interval" -> OutlinedTextField(interval, { interval = it.filter(Char::isDigit) }, label = { Text("间隔天数（1-36500）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        "monthly" -> OutlinedTextField(monthlyDay, { monthlyDay = it.filter(Char::isDigit) }, label = { Text("每月几日（1-31，无该日时取月底）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedTextField(endDate, { endDate = it }, label = { Text("结束日期（可留空）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("任务提醒")
                        Text("默认关闭，可为每个任务单独设置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = reminder, onCheckedChange = { enabled ->
                        reminder = enabled
                        if (enabled && Build.VERSION.SDK_INT >= 33 && !notificationsAllowed) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    })
                }
                if (reminder) {
                    OutlinedTextField(reminderTime, { reminderTime = it }, label = { Text("提醒时间（HH:mm）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("提前提醒")
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf(0, 5, 10, 30).forEach { minutes -> FilterChip(selected = offset == minutes, onClick = { offset = minutes }, label = { Text(if (minutes == 0) "准时" else "提前${minutes}分") }) }
                    }
                    if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed) {
                        Text("通知权限未开启，保存的提醒不会显示；之后可在设置中开启。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsedStart = runCatching { LocalDate.parse(startDate) }.getOrNull()
                val parsedEnd = if (endDate.isBlank()) null else runCatching { LocalDate.parse(endDate) }.getOrNull()
                val validTime = runCatching { LocalTime.parse(reminderTime) }.isSuccess
                when {
                    title.isBlank() -> error = "请输入任务名称"
                    parsedStart == null -> error = "日期格式请使用 YYYY-MM-DD"
                    endDate.isNotBlank() && (parsedEnd == null || parsedEnd < parsedStart) -> error = "结束日期无效或早于开始日期"
                    reminder && !validTime -> error = "提醒时间请使用 HH:mm 格式"
                    type == "custom" && repeatKind == "weekly" && weekdays.isEmpty() -> error = "至少选择一个星期日期"
                    else -> onSave(
                        TaskEntity(
                            id = initial?.id ?: 0,
                            title = title.trim(),
                            description = description.trim(),
                            type = type,
                            repeatKind = if (type == "custom") repeatKind else type,
                            startDate = parsedStart.toString(),
                            endDate = if (type == "custom") parsedEnd?.toString() else null,
                            intervalDays = interval.toIntOrNull()?.coerceIn(1, 36_500) ?: 3,
                            weeklyDays = weekdays.sortedBy { it.toIntOrNull() ?: 0 }.joinToString(","),
                            monthlyDay = (monthlyDay.toIntOrNull() ?: 15).coerceIn(1, 31),
                            reminderEnabled = reminder,
                            reminderTime = if (validTime) reminderTime else "09:00",
                            reminderOffset = offset,
                            isPaused = initial?.isPaused ?: false,
                            createdAt = initial?.createdAt ?: System.currentTimeMillis(),
                            externalId = initial?.externalId ?: java.util.UUID.randomUUID().toString(),
                            categoryId = selectedCategoryId,
                        ),
                    )
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (createCategory) AlertDialog(
        onDismissRequest = { createCategory = false; categoryError = null },
        title = { Text("新建分类") },
        text = {
            Column {
                OutlinedTextField(categoryName, { categoryName = it; categoryError = null }, label = { Text("分类名称") }, singleLine = true)
                categoryError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (categoryName.isBlank()) categoryError = "分类名称不能为空"
                else onCreateCategory(categoryName.trim()) { id, errorMessage ->
                    if (id != null) { selectedCategoryId = id; createCategory = false }
                    else categoryError = errorMessage ?: "创建分类失败"
                }
            }) { Text("创建并选择") }
        },
        dismissButton = { TextButton(onClick = { createCategory = false }) { Text("取消") } },
    )
}

private fun taskSummary(task: TaskEntity): String = when (task.type) {
    "once" -> "单次计划"
    "daily" -> "每天重复"
    else -> when (task.repeatKind) {
        "weekly" -> {
            val weekdays = listOf("一", "二", "三", "四", "五", "六", "日")
            val days = task.weeklyDays.split(',')
                .mapNotNull { it.toIntOrNull() }
                .sorted()
                .joinToString("、") { day -> weekdays.getOrElse(day - 1) { "?" } }
            "每周$days"
        }
        "interval" -> "每隔${task.intervalDays}天"
        "monthly" -> "每月${task.monthlyDay}日"
        else -> "自定义周期"
    }
}

private fun loadTheme(context: Context): ThemeChoice = runCatching {
    ThemeChoice.valueOf(context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("theme", ThemeChoice.SYSTEM.name)!!)
}.getOrDefault(ThemeChoice.SYSTEM)

private fun saveTheme(context: Context, theme: ThemeChoice) {
    context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("theme", theme.name).apply()
}
