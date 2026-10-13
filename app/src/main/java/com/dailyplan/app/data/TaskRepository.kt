package com.dailyplan.app.data

import android.content.Context
import androidx.room.withTransaction
import com.dailyplan.app.reminder.ReminderScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

private fun decodeCategoryCloudDocumentId(documentId: String): String? = runCatching {
    String(
        android.util.Base64.decode(
            documentId,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        ),
        java.nio.charset.StandardCharsets.UTF_8,
    )
}.getOrNull()

enum class PlanExportScope { ALL, ACTIVE, DATE_RANGE, CATEGORY, SELECTED, TEMPLATE }

data class PlanExportOptions(
    val scope: PlanExportScope,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val categoryId: Long? = null,
    val selectedTaskIds: Set<Long> = emptySet(),
)

data class PlanImportPreview(
    val parsed: ParsedPlanFile,
    val duplicateRows: Set<Int>,
    val categoriesToCreate: List<String>,
)

data class PlanImportResult(
    val created: Int,
    val skippedDuplicates: Int,
    val skippedErrors: Int,
    val reminderFailures: Int,
)

data class BatchOperationResult(val succeeded: Int, val skipped: Int = 0, val detail: String = "")

class TaskRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = TaskDatabase.get(appContext)
    private val dao = database.taskDao()
    private val scheduler = ReminderScheduler(appContext)

    fun observeTasks(): Flow<List<TaskEntity>> = dao.observeActiveTasks()
    fun observeCategories(): Flow<List<CategoryEntity>> = dao.observeCategories()

    fun observeCloudSnapshot(): Flow<CloudPlanSnapshot> = combine(
        dao.observeAllTasks(), dao.observeCategories(), dao.observeAllOccurrences(), dao.observeSyncDeletions(),
    ) { tasks, categories, occurrences, deletions -> cloudSnapshot(tasks, categories, occurrences, deletions) }

    suspend fun cloudSnapshot(): CloudPlanSnapshot = cloudSnapshot(
        dao.allTasksIncludingDeleted(), dao.categories(), dao.allOccurrences(), dao.syncDeletions(),
    )

    private fun cloudSnapshot(
        tasks: List<TaskEntity>,
        categories: List<CategoryEntity>,
        occurrences: List<OccurrenceEntity>,
        deletions: List<SyncDeletionEntity>,
    ): CloudPlanSnapshot {
        val categoryById = categories.associateBy(CategoryEntity::id)
        val externalIds = tasks.associate { it.id to it.externalId }
        return CloudPlanSnapshot(
            tasks = tasks.associate { task ->
                task.externalId to CloudTaskRecord(task, categoryById[task.categoryId]?.name.orEmpty())
            },
            categories = categories.associateBy { categoryCloudDocumentId(it.normalizedName) },
            occurrences = occurrences.asSequence()
                .filter { it.isCompleted || it.snoozedUntil != null }
                .mapNotNull { row ->
                    val externalId = externalIds[row.taskId] ?: return@mapNotNull null
                    val key = occurrenceCloudDocumentId(externalId, row.date)
                    key to CloudOccurrenceRecord(
                        taskExternalId = externalId,
                        date = row.date,
                        title = row.title,
                        description = row.description,
                        isCompleted = row.isCompleted,
                        completedAt = row.completedAt,
                        snoozedUntil = row.snoozedUntil,
                        updatedAt = row.updatedAt,
                    )
                }.toMap(),
            deletions = deletions.associate { deletion ->
                deletionCloudDocumentId(deletion.collectionName, deletion.documentId) to CloudDeletionRecord(
                    collectionName = deletion.collectionName,
                    documentId = deletion.documentId,
                    deletedAt = deletion.deletedAt,
                )
            },
        )
    }

    suspend fun applyCloudSnapshot(
        snapshot: CloudPlanSnapshot,
        removedCategoryDocumentIds: Set<String> = emptySet(),
        removedOccurrenceKeys: Set<String> = emptySet(),
    ) {
        val updatedTaskIds = mutableListOf<Long>()
        database.withTransaction {
            val existingDeletions = dao.syncDeletions()
            val mergedDeletions = (existingDeletions + snapshot.deletions.values.map {
                SyncDeletionEntity(it.collectionName, it.documentId, it.deletedAt)
            }).groupBy { it.collectionName to it.documentId }
                .map { (key, candidates) -> SyncDeletionEntity(key.first, key.second, candidates.maxOf(SyncDeletionEntity::deletedAt)) }
            val categories = dao.categories().toMutableList()
            snapshot.categories.values.forEach { remote ->
                val existing = categories.firstOrNull { it.normalizedName == remote.normalizedName }
                if (existing == null) {
                    dao.upsertCategory(remote.copy(id = 0))
                } else if (remote.updatedAt >= existing.updatedAt && (remote.name != existing.name || remote.color != existing.color || remote.sortOrder != existing.sortOrder)) {
                    dao.upsertCategory(remote.copy(id = existing.id))
                }
            }
            val categoryNames = dao.categories().associate { it.normalizedName to it.id }
            snapshot.tasks.values.forEach { remote ->
                val task = remote.task
                val existing = dao.taskByExternalId(task.externalId)
                val categoryId = categoryNames[normalizeCategoryName(remote.categoryName)]
                val mergedTask = task.copy(id = existing?.id ?: 0, categoryId = categoryId)
                if (existing == null || task.updatedAt >= existing.updatedAt && mergedTask != existing) {
                    dao.upsertTask(mergedTask)
                    dao.taskByExternalId(task.externalId)?.let { updatedTaskIds += it.id }
                }
            }
            val taskByExternalId = dao.allTasksIncludingDeleted().associateBy(TaskEntity::externalId)
            snapshot.occurrences.values.forEach { remote ->
                val task = taskByExternalId[remote.taskExternalId] ?: return@forEach
                val existing = dao.occurrence(task.id, remote.date)
                if (existing == null || remote.updatedAt >= existing.updatedAt && (remote.isCompleted != existing.isCompleted || remote.snoozedUntil != existing.snoozedUntil || remote.title != existing.title || remote.description != existing.description)) {
                    dao.upsertOccurrence(
                        OccurrenceEntity(
                            taskId = task.id,
                            date = remote.date,
                            title = remote.title,
                            description = remote.description,
                            isCompleted = remote.isCompleted,
                            completedAt = remote.completedAt,
                            snoozedUntil = remote.snoozedUntil,
                            updatedAt = remote.updatedAt,
                        ),
                    )
                }
            }
            val allCategories = dao.categories().associateBy { categoryCloudDocumentId(it.normalizedName) }
            val allOccurrences = dao.allOccurrences().mapNotNull { row ->
                taskByExternalId[row.taskId]?.externalId?.let { occurrenceCloudDocumentId(it, row.date) to row }
            }.toMap()
            val activeDeletions = mergedDeletions.filter { deletion ->
                val recordUpdatedAt = when (deletion.collectionName) {
                    CloudCollections.CATEGORIES -> allCategories[deletion.documentId]?.updatedAt
                    CloudCollections.OCCURRENCES -> allOccurrences[deletion.documentId]?.updatedAt
                    else -> null
                }
                recordUpdatedAt == null || recordUpdatedAt <= deletion.deletedAt
            }
            activeDeletions.forEach { deletion ->
                when (deletion.collectionName) {
                    CloudCollections.CATEGORIES -> {
                        val normalizedName = decodeCategoryCloudDocumentId(deletion.documentId) ?: return@forEach
                        dao.categoryByNormalizedName(normalizedName)?.let { category ->
                            if (deletion.deletedAt >= category.updatedAt) {
                                dao.moveCategoryTasksToUnclassified(category.id, maxOf(System.currentTimeMillis(), deletion.deletedAt + 1))
                                dao.deleteCategory(category.id)
                            }
                        }
                    }
                    CloudCollections.OCCURRENCES -> {
                        val separator = deletion.documentId.lastIndexOf("__")
                        if (separator <= 0) return@forEach
                        val externalId = deletion.documentId.substring(0, separator)
                        val date = deletion.documentId.substring(separator + 2)
                        val task = taskByExternalId[externalId] ?: return@forEach
                        val occurrence = dao.occurrence(task.id, date)
                        if (occurrence != null && deletion.deletedAt >= occurrence.updatedAt) {
                            dao.deleteOccurrence(task.id, date)
                        }
                    }
                }
            }
            removedCategoryDocumentIds.forEach { documentId ->
                val normalizedName = decodeCategoryCloudDocumentId(documentId) ?: return@forEach
                dao.categoryByNormalizedName(normalizedName)?.let { category ->
                    dao.moveCategoryTasksToUnclassified(category.id)
                    dao.deleteCategory(category.id)
                }
            }
            removedOccurrenceKeys.forEach { key ->
                val separator = key.lastIndexOf("__")
                if (separator <= 0) return@forEach
                val externalId = key.substring(0, separator)
                val date = key.substring(separator + 2)
                taskByExternalId[externalId]?.let { dao.deleteOccurrence(it.id, date) }
            }
            dao.clearSyncDeletions()
            if (activeDeletions.isNotEmpty()) dao.upsertSyncDeletions(activeDeletions)
        }
        if (updatedTaskIds.isNotEmpty()) runCatching { syncReminders(updatedTaskIds) }
    }

    fun observeOccurrences(from: LocalDate, to: LocalDate): Flow<List<OccurrenceEntity>> =
        dao.observeOccurrences(from.toString(), to.toString())

    suspend fun ensureOccurrences(from: LocalDate, to: LocalDate) {
        val rows = buildList {
            dao.activeTasks().filterNot(TaskEntity::isPaused).forEach { task ->
                RecurrenceCalculator.dates(task, from, to).forEach { date ->
                    add(OccurrenceEntity(task.id, date.toString(), task.title, task.description))
                }
            }
        }
        for (chunk in rows.chunked(500)) dao.insertOccurrences(chunk)
    }

    suspend fun ensureOverdueOnce() {
        val today = LocalDate.now()
        dao.overdueOnceTasks(today.toString()).filterNot(TaskEntity::isPaused).forEach { task ->
            val date = LocalDate.parse(task.startDate)
            if (RecurrenceCalculator.occursOn(task, date)) {
                dao.insertOccurrence(OccurrenceEntity(task.id, task.startDate, task.title, task.description))
            }
        }
    }

    fun observeOverdueOnce(): Flow<List<OccurrenceEntity>> = dao.observeOverdueOnce(LocalDate.now().toString())

    suspend fun save(task: TaskEntity) {
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        if (task.id == 0L) {
            val id = dao.insertTask(task.copy(updatedAt = now))
            runCatching { scheduler.scheduleNext(id) }
            return
        }

        val previous = dao.task(task.id)
        val ruleChanged = previous != null && (
            task.type != previous.type || task.repeatKind != previous.repeatKind ||
                task.startDate != previous.startDate || task.endDate != previous.endDate ||
                task.intervalDays != previous.intervalDays || task.weeklyDays != previous.weeklyDays ||
                task.monthlyDay != previous.monthlyDay
            )
        val reminderChanged = previous != null && (
            task.reminderEnabled != previous.reminderEnabled || task.reminderTime != previous.reminderTime ||
                task.reminderOffset != previous.reminderOffset || task.isPaused != previous.isPaused
            )
        val updated = task.copy(
            externalId = previous?.externalId ?: task.externalId,
            ruleEffectiveFrom = if (ruleChanged) today.toString() else previous?.ruleEffectiveFrom,
            updatedAt = now,
        )
        val snoozesToCancel = database.withTransaction {
            dao.updateTask(updated)
            if (ruleChanged || reminderChanged) {
                val snoozes = clearSnoozesInDatabase(task.id)
                dao.removePendingFrom(task.id, today.toString())
                snoozes
            } else {
                if (previous != null && (task.title != previous.title || task.description != previous.description)) {
                    dao.updatePendingOccurrenceText(task.id, today.toString(), task.title, task.description)
                }
                emptyList()
            }
        }
        cancelSnoozeAlarms(task.id, snoozesToCancel)
        if (ruleChanged || reminderChanged) runCatching { scheduler.scheduleNext(task.id) }
    }

    suspend fun addCategory(name: String, color: Long, sortOrder: Int? = null): CategoryEntity {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "分类名称不能为空" }
        require(cleanName.length <= 80) { "分类名称不能超过 80 个字符" }
        val normalized = normalizeCategoryName(cleanName)
        require(dao.categoryByNormalizedName(normalized) == null) { "已存在同名分类" }
        val categories = dao.categories()
        val updatedAt = timestampAfterDeletion(CloudCollections.CATEGORIES, categoryCloudDocumentId(normalized))
        val insertedId = dao.insertCategory(
            CategoryEntity(
                name = cleanName,
                normalizedName = normalized,
                color = color,
                sortOrder = sortOrder ?: ((categories.maxOfOrNull(CategoryEntity::sortOrder) ?: -1) + 1),
                updatedAt = updatedAt,
            ),
        )
        require(insertedId > 0) { "分类名称已存在" }
        return dao.category(insertedId) ?: error("分类创建失败")
    }

    suspend fun renameCategory(category: CategoryEntity, name: String, color: Long) {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "分类名称不能为空" }
        require(cleanName.length <= 80) { "分类名称不能超过 80 个字符" }
        val normalized = normalizeCategoryName(cleanName)
        val duplicate = dao.categoryByNormalizedName(normalized)
        require(duplicate == null || duplicate.id == category.id) { "已存在同名分类" }
        val updatedAt = timestampAfterDeletion(CloudCollections.CATEGORIES, categoryCloudDocumentId(normalized), category.updatedAt)
        database.withTransaction {
            if (category.normalizedName != normalized) {
                val oldDocumentId = categoryCloudDocumentId(category.normalizedName)
                dao.upsertSyncDeletion(
                    SyncDeletionEntity(
                        CloudCollections.CATEGORIES,
                        oldDocumentId,
                        timestampAfterDeletion(CloudCollections.CATEGORIES, oldDocumentId, category.updatedAt),
                    ),
                )
            }
            dao.updateCategory(category.copy(name = cleanName, normalizedName = normalized, color = color, updatedAt = updatedAt))
        }
    }

    suspend fun deleteCategory(categoryId: Long): Int = database.withTransaction {
        val count = dao.activeTaskCountInCategory(categoryId)
        val category = dao.category(categoryId)
        val deletedAt = category?.let { timestampAfterDeletion(CloudCollections.CATEGORIES, categoryCloudDocumentId(it.normalizedName), it.updatedAt) }
            ?: System.currentTimeMillis()
        category?.let {
            dao.upsertSyncDeletion(
                SyncDeletionEntity(
                    CloudCollections.CATEGORIES,
                    categoryCloudDocumentId(it.normalizedName),
                    deletedAt,
                ),
            )
        }
        dao.moveCategoryTasksToUnclassified(categoryId, deletedAt + 1)
        dao.deleteCategory(categoryId)
        count
    }

    suspend fun moveCategory(categoryId: Long, offset: Int) {
        val categories = dao.categories()
        val currentIndex = categories.indexOfFirst { it.id == categoryId }
        val targetIndex = (currentIndex + offset).coerceIn(0, categories.lastIndex)
        if (currentIndex < 0 || currentIndex == targetIndex) return
        val current = categories[currentIndex]
        val target = categories[targetIndex]
        database.withTransaction {
            val now = System.currentTimeMillis()
            dao.updateCategory(current.copy(sortOrder = target.sortOrder, updatedAt = now))
            dao.updateCategory(target.copy(sortOrder = current.sortOrder, updatedAt = now))
        }
    }

    suspend fun setPaused(task: TaskEntity, paused: Boolean) {
        val today = LocalDate.now()
        val updated = task.copy(isPaused = paused, ruleEffectiveFrom = if (paused) task.ruleEffectiveFrom else today.toString(), updatedAt = System.currentTimeMillis())
        val snoozesToCancel = database.withTransaction {
            dao.updateTask(updated)
            if (paused) {
                val snoozes = clearSnoozesInDatabase(task.id)
                dao.removePendingFrom(task.id, today.plusDays(1).toString())
                snoozes
            } else emptyList()
        }
        if (paused) {
            cancelSnoozeAlarms(task.id, snoozesToCancel)
            scheduler.cancel(task.id)
        } else runCatching { scheduler.scheduleNext(task.id) }
    }

    suspend fun delete(task: TaskEntity) {
        ensureOccurrences(LocalDate.parse(task.startDate), LocalDate.now())
        val snoozesToCancel = database.withTransaction {
            dao.updateTask(task.copy(isDeleted = true, updatedAt = System.currentTimeMillis()))
            val snoozes = clearSnoozesInDatabase(task.id)
            dao.removePendingFrom(task.id, LocalDate.now().plusDays(1).toString())
            snoozes
        }
        cancelSnoozeAlarms(task.id, snoozesToCancel)
        scheduler.cancel(task.id)
    }

    suspend fun bulkSetCategory(taskIds: Set<Long>, categoryId: Long?): BatchOperationResult {
        if (taskIds.isEmpty()) return BatchOperationResult(0, detail = "没有选中任务")
        if (categoryId != null) require(dao.category(categoryId) != null) { "目标分类已不存在" }
        var count = 0
        database.withTransaction {
            taskIds.toList().chunked(500).forEach { ids -> count += dao.setTaskCategory(ids, categoryId, System.currentTimeMillis()) }
        }
        return BatchOperationResult(count, taskIds.size - count)
    }

    suspend fun restoreTaskCategories(categoriesByTaskId: Map<Long, Long?>): Int = database.withTransaction {
        var restored = 0
        val grouped = mutableMapOf<Long?, MutableList<Long>>()
        categoriesByTaskId.forEach { (taskId, categoryId) ->
            val targetId = categoryId?.takeIf { dao.category(it) != null }
            grouped.getOrPut(targetId) { mutableListOf() } += taskId
        }
        grouped.forEach { (categoryId, taskIds) ->
            taskIds.chunked(500).forEach { ids -> restored += dao.setTaskCategory(ids, categoryId, System.currentTimeMillis()) }
        }
        restored
    }

    suspend fun bulkChangeStartDate(taskIds: Set<Long>, date: LocalDate): BatchOperationResult {
        if (taskIds.isEmpty()) return BatchOperationResult(0, detail = "没有选中任务")
        val today = LocalDate.now()
        val snoozesToCancel = mutableMapOf<Long, List<OccurrenceEntity>>()
        val changed = database.withTransaction {
            val tasks = taskIds.mapNotNull { id -> dao.task(id) }.filterNot(TaskEntity::isDeleted)
            tasks.forEach { task ->
                val nextEffective = if (task.type == "once") null else maxOf(today, date).toString()
                dao.updateTask(task.copy(
                    startDate = date.toString(),
                    endDate = if (task.type == "once") null else task.endDate,
                    ruleEffectiveFrom = nextEffective,
                    updatedAt = System.currentTimeMillis(),
                ))
                snoozesToCancel[task.id] = clearSnoozesInDatabase(task.id)
                dao.removePendingFrom(task.id, today.toString())
            }
            tasks
        }
        changed.forEach { cancelSnoozeAlarms(it.id, snoozesToCancel[it.id].orEmpty()) }
        val reminderFailures = syncReminders(changed.map(TaskEntity::id))
        return BatchOperationResult(
            succeeded = changed.size,
            skipped = taskIds.size - changed.size,
            detail = if (reminderFailures == 0) "单次任务日期已更新；周期任务按新的周期起始日期从今天起重新计算，过去的完成记录未改动。"
            else "${reminderFailures} 项提醒暂未同步成功，已触发重新调度。",
        )
    }

    suspend fun bulkSetPaused(taskIds: Set<Long>, paused: Boolean): BatchOperationResult {
        if (taskIds.isEmpty()) return BatchOperationResult(0, detail = "没有选中任务")
        val today = LocalDate.now()
        val snoozesToCancel = mutableMapOf<Long, List<OccurrenceEntity>>()
        val changed = database.withTransaction {
            val tasks = taskIds.mapNotNull { id -> dao.task(id) }.filterNot(TaskEntity::isDeleted)
            tasks.forEach { task ->
                dao.updateTask(task.copy(isPaused = paused, ruleEffectiveFrom = if (paused) task.ruleEffectiveFrom else today.toString(), updatedAt = System.currentTimeMillis()))
                if (paused) {
                    snoozesToCancel[task.id] = clearSnoozesInDatabase(task.id)
                    dao.removePendingFrom(task.id, today.plusDays(1).toString())
                }
            }
            tasks
        }
        changed.forEach { task ->
            if (paused) {
                cancelSnoozeAlarms(task.id, snoozesToCancel[task.id].orEmpty())
                scheduler.cancel(task.id)
            }
        }
        val reminderFailures = if (paused) 0 else syncReminders(changed.map(TaskEntity::id))
        return BatchOperationResult(
            succeeded = changed.size,
            skipped = taskIds.size - changed.size,
            detail = if (reminderFailures == 0) "提醒已${if (paused) "取消" else "重新安排"}。暂停期间不会补发错过的提醒。"
            else "${reminderFailures} 项提醒暂未同步成功，已触发重新调度。",
        )
    }

    suspend fun bulkDelete(taskIds: Set<Long>): BatchOperationResult {
        if (taskIds.isEmpty()) return BatchOperationResult(0, detail = "没有选中任务")
        val tasks = taskIds.mapNotNull { id -> dao.task(id) }.filterNot(TaskEntity::isDeleted)
        tasks.forEach { task ->
            ensureOccurrences(LocalDate.parse(task.startDate), LocalDate.now())
        }
        val snoozesToCancel = mutableMapOf<Long, List<OccurrenceEntity>>()
        database.withTransaction {
            tasks.forEach { task ->
                dao.updateTask(task.copy(isDeleted = true, updatedAt = System.currentTimeMillis()))
                snoozesToCancel[task.id] = clearSnoozesInDatabase(task.id)
                dao.removePendingFrom(task.id, LocalDate.now().plusDays(1).toString())
            }
        }
        tasks.forEach { task ->
            cancelSnoozeAlarms(task.id, snoozesToCancel[task.id].orEmpty())
            scheduler.cancel(task.id)
        }
        return BatchOperationResult(tasks.size, taskIds.size - tasks.size, "已软删除整个任务计划，历史执行记录保留。")
    }

    suspend fun toggle(row: OccurrenceEntity) {
        val current = dao.occurrence(row.taskId, row.date) ?: return
        setCompletion(current, !current.isCompleted)
    }

    suspend fun setCompletion(row: OccurrenceEntity, complete: Boolean) {
        val current = dao.occurrence(row.taskId, row.date) ?: return
        val occurrenceId = dao.task(row.taskId)?.externalId?.let { occurrenceCloudDocumentId(it, row.date) }
        val updatedAt = occurrenceId?.let { timestampAfterDeletion(CloudCollections.OCCURRENCES, it, current.updatedAt) } ?: System.currentTimeMillis()
        if (!complete && (current.isCompleted || current.snoozedUntil != null)) {
            if (occurrenceId != null) {
                dao.upsertSyncDeletion(
                    SyncDeletionEntity(
                        CloudCollections.OCCURRENCES,
                        occurrenceId,
                        updatedAt,
                    ),
                )
            }
        }
        dao.updateOccurrence(
            current.copy(isCompleted = complete, completedAt = if (complete) updatedAt else null, snoozedUntil = null, updatedAt = updatedAt),
        )
        if (complete) scheduler.cancelSnooze(row.taskId, row.date)
        runCatching { scheduler.scheduleNext(row.taskId) }
    }

    suspend fun occurrence(taskId: Long, date: LocalDate): OccurrenceEntity? {
        ensureOccurrences(date, date)
        return dao.occurrence(taskId, date.toString())
    }

    suspend fun markComplete(taskId: Long, date: String) {
        val row = dao.occurrence(taskId, date) ?: return
        if (!row.isCompleted) {
            val occurrenceId = dao.task(taskId)?.externalId?.let { occurrenceCloudDocumentId(it, date) }
            val updatedAt = occurrenceId?.let { timestampAfterDeletion(CloudCollections.OCCURRENCES, it, row.updatedAt) } ?: System.currentTimeMillis()
            dao.updateOccurrence(row.copy(isCompleted = true, completedAt = updatedAt, snoozedUntil = null, updatedAt = updatedAt))
        }
        scheduler.cancel(taskId)
        scheduler.cancelSnooze(taskId, date)
        runCatching { scheduler.scheduleNext(taskId) }
    }

    suspend fun setSnooze(taskId: Long, date: String, until: Long?) {
        dao.occurrence(taskId, date)?.let { current ->
            val occurrenceId = dao.task(taskId)?.externalId?.let { occurrenceCloudDocumentId(it, date) }
            val updatedAt = occurrenceId?.let { timestampAfterDeletion(CloudCollections.OCCURRENCES, it, current.updatedAt) } ?: System.currentTimeMillis()
            if (until == null && current.snoozedUntil != null && !current.isCompleted) {
                occurrenceId?.let { id ->
                    dao.upsertSyncDeletion(
                        SyncDeletionEntity(
                            CloudCollections.OCCURRENCES,
                            id,
                            updatedAt,
                        ),
                    )
                }
            }
            dao.updateOccurrence(current.copy(snoozedUntil = until, updatedAt = updatedAt))
        }
    }

    suspend fun earliestTaskDate(): LocalDate? = dao.earliestStartDate()?.let(LocalDate::parse)

    suspend fun previewImport(parsed: ParsedPlanFile): PlanImportPreview {
        val categories = dao.categories()
        val categoryById = categories.associateBy(CategoryEntity::id)
        val existingTasks = dao.allTasksIncludingDeleted()
        val existingExternalIds = existingTasks.mapTo(mutableSetOf(), TaskEntity::externalId)
        val existingFingerprints = existingTasks.mapTo(mutableSetOf()) { task ->
            task.fingerprint(categoryById[task.categoryId]?.name.orEmpty())
        }
        val duplicateRows = mutableSetOf<Int>()
        parsed.records.forEach { record ->
            val isDuplicate = record.externalId in existingExternalIds || record.fingerprint() in existingFingerprints
            if (isDuplicate) duplicateRows += record.sourceRow
            existingExternalIds += record.externalId
            existingFingerprints += record.fingerprint()
        }
        val knownNames = categories.mapTo(mutableSetOf()) { normalizeCategoryName(it.name) }
        val categoriesToCreate = parsed.records.map { it.categoryName.trim() }
            .filter { it.isNotBlank() && normalizeCategoryName(it) != normalizeCategoryName("未分类") }
            .distinctBy(::normalizeCategoryName)
            .filterNot { normalizeCategoryName(it) in knownNames }
        return PlanImportPreview(parsed, duplicateRows, categoriesToCreate)
    }

    suspend fun importPlans(
        preview: PlanImportPreview,
        skipErrorRows: Boolean,
        importDuplicatesAsNew: Boolean,
    ): PlanImportResult {
        require(skipErrorRows || preview.parsed.issues.isEmpty()) { "请先修正文件，或明确选择跳过错误行" }
        val skippedErrors = if (skipErrorRows) preview.parsed.issues.map(PlanTransferIssue::rowNumber).distinct().size else 0
        val createdIds = mutableListOf<Long>()
        var skippedDuplicates = 0
        database.withTransaction {
            val categories = dao.categories().toMutableList()
            val existingTasks = dao.allTasksIncludingDeleted()
            val existingExternalIds = existingTasks.mapTo(mutableSetOf(), TaskEntity::externalId)
            val existingFingerprints = existingTasks.mapTo(mutableSetOf()) { task ->
                task.fingerprint(categories.firstOrNull { it.id == task.categoryId }?.name.orEmpty())
            }
            val rowsToCreate = mutableListOf<Pair<PlanTransferRecord, Boolean>>()
            preview.parsed.records.forEach { record ->
                val duplicate = record.sourceRow in preview.duplicateRows ||
                    record.externalId in existingExternalIds || record.fingerprint() in existingFingerprints
                if (duplicate && !importDuplicatesAsNew) {
                    skippedDuplicates++
                } else {
                    rowsToCreate += record to duplicate
                    existingExternalIds += record.externalId
                    existingFingerprints += record.fingerprint()
                }
            }
            val categoryNames = rowsToCreate.map { it.first.categoryName.trim() }
                .filter { it.isNotBlank() && normalizeCategoryName(it) != normalizeCategoryName("未分类") }
                .distinctBy(::normalizeCategoryName)
            categoryNames.forEach { name ->
                val normalized = normalizeCategoryName(name)
                if (categories.none { it.normalizedName == normalized }) {
                    val order = (categories.maxOfOrNull(CategoryEntity::sortOrder) ?: -1) + 1
                    val id = dao.insertCategory(
                        CategoryEntity(name = name, normalizedName = normalized, color = DEFAULT_CATEGORY_COLOR, sortOrder = order),
                    )
                    if (id > 0) dao.category(id)?.let(categories::add)
                }
            }
            val categoryIds = categories.associate { it.normalizedName to it.id }
            val entities = rowsToCreate.map { (record, wasDuplicate) ->
                TaskEntity(
                    title = record.title,
                    description = record.description,
                    type = record.type,
                    repeatKind = record.repeatKind,
                    startDate = record.startDate,
                    endDate = record.endDate,
                    ruleEffectiveFrom = record.ruleEffectiveFrom,
                    intervalDays = record.intervalDays,
                    weeklyDays = record.weeklyDays,
                    monthlyDay = record.monthlyDay,
                    reminderEnabled = record.reminderEnabled,
                    reminderTime = record.reminderTime,
                    reminderOffset = record.reminderOffset,
                    isPaused = record.isPaused,
                    externalId = if (wasDuplicate) UUID.randomUUID().toString() else record.externalId,
                    categoryId = categoryIds[normalizeCategoryName(record.categoryName)].takeIf { record.categoryName.isNotBlank() && normalizeCategoryName(record.categoryName) != normalizeCategoryName("未分类") },
                    updatedAt = System.currentTimeMillis(),
                )
            }
            val inserted = if (entities.isEmpty()) emptyList() else dao.insertTasks(entities)
            inserted.filter { it > 0 }.forEach(createdIds::add)
            skippedDuplicates += inserted.count { it <= 0 }
        }
        val reminderFailures = syncReminders(createdIds)
        return PlanImportResult(createdIds.size, skippedDuplicates, skippedErrors, reminderFailures)
    }

    suspend fun exportPlans(options: PlanExportOptions): List<PlanTransferRecord> {
        val tasks = dao.activeTasks()
        val categories = dao.categories().associateBy(CategoryEntity::id)
        val selected = when (options.scope) {
            PlanExportScope.ALL -> tasks
            PlanExportScope.SELECTED -> tasks.filter { it.id in options.selectedTaskIds }
            PlanExportScope.TEMPLATE -> emptyList()
            PlanExportScope.CATEGORY -> tasks.filter { it.categoryId == options.categoryId }
            PlanExportScope.ACTIVE -> tasks.filter { task ->
                if (task.isPaused) false
                else if (task.type == "once") dao.occurrence(task.id, task.startDate)?.isCompleted != true
                else task.endDate == null || LocalDate.parse(task.endDate) >= LocalDate.now()
            }
            PlanExportScope.DATE_RANGE -> {
                val from = requireNotNull(options.from) { "请选择开始日期" }
                val to = requireNotNull(options.to) { "请选择结束日期" }
                require(to >= from) { "结束日期不能早于开始日期" }
                tasks.filter { task -> RecurrenceCalculator.dates(task, from, to).isNotEmpty() }
            }
        }
        return selected.map { task ->
            PlanTransferRecord(
                externalId = task.externalId,
                title = task.title,
                description = task.description,
                categoryName = categories[task.categoryId]?.name ?: "未分类",
                type = task.type,
                repeatKind = task.repeatKind,
                startDate = task.startDate,
                endDate = task.endDate,
                ruleEffectiveFrom = task.ruleEffectiveFrom,
                weeklyDays = task.weeklyDays,
                intervalDays = task.intervalDays,
                monthlyDay = task.monthlyDay,
                reminderEnabled = task.reminderEnabled,
                reminderTime = task.reminderTime,
                reminderOffset = task.reminderOffset,
                isPaused = task.isPaused,
            )
        }
    }

    suspend fun refreshReminders() = scheduler.rescheduleAll()

    private suspend fun syncReminders(taskIds: List<Long>): Int {
        val failed = taskIds.count { id -> runCatching { scheduler.scheduleNext(id) }.getOrDefault(false).not() }
        if (failed == 0) return 0
        return runCatching { scheduler.rescheduleAll() }.getOrDefault(failed)
    }

    private suspend fun clearSnoozesInDatabase(taskId: Long): List<OccurrenceEntity> {
        val rows = dao.snoozedOccurrences().filter { it.taskId == taskId }
        val externalId = dao.task(taskId)?.externalId
        rows.forEach { row ->
            val occurrenceId = externalId?.let { occurrenceCloudDocumentId(it, row.date) }
            val updatedAt = occurrenceId?.let { timestampAfterDeletion(CloudCollections.OCCURRENCES, it, row.updatedAt) } ?: System.currentTimeMillis()
            if (!row.isCompleted && externalId != null) {
                dao.upsertSyncDeletion(
                    SyncDeletionEntity(
                        CloudCollections.OCCURRENCES,
                        requireNotNull(occurrenceId),
                        updatedAt,
                    ),
                )
            }
            dao.updateOccurrence(row.copy(snoozedUntil = null, updatedAt = updatedAt))
        }
        return rows
    }

    private suspend fun timestampAfterDeletion(collectionName: String, documentId: String, after: Long = 0L): Long {
        val deletedAt = dao.syncDeletions().firstOrNull { it.collectionName == collectionName && it.documentId == documentId }?.deletedAt ?: 0L
        return maxOf(System.currentTimeMillis(), deletedAt + 1, after + 1)
    }

    private suspend fun cancelSnoozeAlarms(taskId: Long, rows: List<OccurrenceEntity>) {
        rows.forEach { row ->
            runCatching { scheduler.cancelSnooze(taskId, row.date) }
        }
    }

    private fun TaskEntity.fingerprint(categoryName: String): String = listOf(
        title.trim().lowercase(Locale.ROOT), description.trim(), categoryName.normalizeForFingerprint(),
        type, repeatKind, startDate, endDate.orEmpty(), ruleEffectiveFrom.orEmpty(), intervalDays.toString(), weeklyDays, monthlyDay.toString(),
        reminderEnabled.toString(), reminderTime, reminderOffset.toString(), isPaused.toString(),
    ).joinToString("\u0000")

    private fun PlanTransferRecord.fingerprint(): String = listOf(
        title.trim().lowercase(Locale.ROOT), description.trim(), categoryName.normalizeForFingerprint(),
        type, repeatKind, startDate, endDate.orEmpty(), ruleEffectiveFrom.orEmpty(), intervalDays.toString(), weeklyDays, monthlyDay.toString(),
        reminderEnabled.toString(), reminderTime, reminderOffset.toString(), isPaused.toString(),
    ).joinToString("\u0000")

    companion object {
        private const val DEFAULT_CATEGORY_COLOR = 0xFF4F6BED.toLong()
    }
}

private fun String.normalizeForFingerprint(): String =
    if (isBlank() || normalizeCategoryName(this) == normalizeCategoryName("未分类")) "" else normalizeCategoryName(this)
