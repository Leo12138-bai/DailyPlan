package com.dailyplan.app.sync

import com.dailyplan.app.data.CategoryEntity
import com.dailyplan.app.data.CloudCollections
import com.dailyplan.app.data.CloudDeletionRecord
import com.dailyplan.app.data.CloudOccurrenceRecord
import com.dailyplan.app.data.CloudPlanSnapshot
import com.dailyplan.app.data.CloudTaskRecord
import com.dailyplan.app.data.TaskEntity
import com.dailyplan.app.data.TaskRepository
import com.dailyplan.app.data.deletionCloudDocumentId
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.util.Locale

class CloudSyncManager(private val repository: TaskRepository) {
    private val firestore = FirebaseFirestore.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = mutableMapOf<String, MutableMap<String, Map<String, Any?>>>()
    private val listeners = mutableListOf<ListenerRegistration>()
    private val serverCollectionsLoaded = mutableSetOf<String>()
    private val syncMutex = Mutex()
    private var startJob: Job? = null
    private var localJob: Job? = null
    private var activeUid: String? = null
    private var remoteSnapshotReady = false
    private var onStatus: (String) -> Unit = {}

    fun start(uid: String, status: (String) -> Unit) {
        if (activeUid == uid) return
        stop()
        activeUid = uid
        onStatus = status
        startJob = scope.launch {
            try {
                onStatus("正在合并本机与云端数据…")
                val root = firestore.collection("users").document(uid)
                val remote = loadSnapshot(root)
                val local = repository.cloudSnapshot()
                val merged = merge(local, remote)
                repository.applyCloudSnapshot(merged)
                cache[COLLECTION_TASKS] = merged.tasks.mapValuesTo(mutableMapOf()) { taskData(it.value) }
                cache[COLLECTION_CATEGORIES] = merged.categories.mapValuesTo(mutableMapOf()) { categoryData(it.value) }
                cache[COLLECTION_OCCURRENCES] = merged.occurrences.mapValuesTo(mutableMapOf()) { occurrenceData(it.value) }
                remoteSnapshotReady = true
                writeSnapshot(root, merged)
                attachListeners(root)
                observeLocal(root)
                onStatus("云端已同步")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onStatus("同步失败：${error.message ?: "请检查网络与 Firebase 配置"}")
                // Keep the local plan usable, but wait for a server snapshot before uploading local state.
                val root = firestore.collection("users").document(uid)
                attachListeners(root)
            }
        }
    }

    fun stop() {
        listeners.forEach(ListenerRegistration::remove)
        listeners.clear()
        startJob?.cancel()
        startJob = null
        localJob?.cancel()
        localJob = null
        activeUid = null
        remoteSnapshotReady = false
        serverCollectionsLoaded.clear()
        cache.clear()
    }

    fun close() {
        stop()
        scope.cancel()
    }

    private suspend fun loadSnapshot(root: com.google.firebase.firestore.DocumentReference): CloudPlanSnapshot {
        val taskDocs = root.collection(COLLECTION_TASKS).get(Source.SERVER).await().documents
        val categoryDocs = root.collection(COLLECTION_CATEGORIES).get(Source.SERVER).await().documents
        val occurrenceDocs = root.collection(COLLECTION_OCCURRENCES).get(Source.SERVER).await().documents
        return CloudPlanSnapshot(
            tasks = taskDocs.mapNotNull { document -> document.toCloudTask()?.let { document.id to it } }.toMap(),
            categories = categoryDocs.mapNotNull { document -> document.toCloudCategory()?.let { document.id to it } }.toMap(),
            occurrences = occurrenceDocs.mapNotNull { document -> document.toCloudOccurrence()?.let { document.id to it } }.toMap(),
        deletions = root.collection(COLLECTION_DELETIONS).get(Source.SERVER).await().documents
            .mapNotNull { document -> document.toCloudDeletion()?.let { document.id to it } }.toMap(),
        )
    }

    private fun merge(local: CloudPlanSnapshot, remote: CloudPlanSnapshot): CloudPlanSnapshot {
        val tasks = (local.tasks.keys + remote.tasks.keys).associateWith { key ->
            val localValue = local.tasks[key]
            val remoteValue = remote.tasks[key]
            if (remoteValue != null && (localValue == null || remoteValue.task.updatedAt >= localValue.task.updatedAt)) remoteValue else requireNotNull(localValue)
        }
        val categories = (local.categories.keys + remote.categories.keys).associateWith { key ->
            val localValue = local.categories[key]
            val remoteValue = remote.categories[key]
            if (remoteValue != null && (localValue == null || remoteValue.updatedAt >= localValue.updatedAt)) remoteValue else requireNotNull(localValue)
        }.toMutableMap()
        val occurrences = (local.occurrences.keys + remote.occurrences.keys).associateWith { key ->
            val localValue = local.occurrences[key]
            val remoteValue = remote.occurrences[key]
            if (remoteValue != null && (localValue == null || remoteValue.updatedAt >= localValue.updatedAt)) remoteValue else requireNotNull(localValue)
        }.toMutableMap()
        val deletions = (local.deletions.keys + remote.deletions.keys).associateWith { key ->
            val localValue = local.deletions[key]
            val remoteValue = remote.deletions[key]
            if (remoteValue != null && (localValue == null || remoteValue.deletedAt >= localValue.deletedAt)) remoteValue else requireNotNull(localValue)
        }.toMutableMap()
        deletions.values.toList().forEach { deletion ->
            val recordUpdatedAt = when (deletion.collectionName) {
                COLLECTION_CATEGORIES -> categories[deletion.documentId]?.updatedAt
                COLLECTION_OCCURRENCES -> occurrences[deletion.documentId]?.updatedAt
                else -> null
            }
            if (recordUpdatedAt != null && recordUpdatedAt > deletion.deletedAt) {
                deletions.remove(deletionCloudDocumentId(deletion.collectionName, deletion.documentId))
            } else when (deletion.collectionName) {
                COLLECTION_CATEGORIES -> categories.remove(deletion.documentId)
                COLLECTION_OCCURRENCES -> occurrences.remove(deletion.documentId)
            }
        }
        return CloudPlanSnapshot(tasks, categories, occurrences, deletions)
    }

    private suspend fun writeSnapshot(root: com.google.firebase.firestore.DocumentReference, snapshot: CloudPlanSnapshot) {
        val writes = buildList {
            snapshot.tasks.forEach { (id, record) -> add(root.collection(COLLECTION_TASKS).document(id) to taskData(record)) }
            snapshot.categories.forEach { (id, category) -> add(root.collection(COLLECTION_CATEGORIES).document(id) to categoryData(category)) }
            snapshot.occurrences.forEach { (id, record) -> add(root.collection(COLLECTION_OCCURRENCES).document(id) to occurrenceData(record)) }
            snapshot.deletions.forEach { (id, deletion) -> add(root.collection(COLLECTION_DELETIONS).document(id) to deletionData(deletion)) }
        }
        writes.chunked(MAX_BATCH_WRITES).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { (reference, data) -> batch.set(reference, data) }
            batch.commit().await()
        }
        deleteTargetsForTombstones(root, snapshot.deletions.values)
        val activeDeletionIds = snapshot.deletions.keys
        root.collection(COLLECTION_DELETIONS).get(Source.SERVER).await().documents
            .filterNot { it.id in activeDeletionIds }
            .forEach { it.reference.delete().await() }
    }

    private suspend fun deleteTargetsForTombstones(
        root: com.google.firebase.firestore.DocumentReference,
        deletions: Collection<CloudDeletionRecord>,
    ) {
        deletions.forEach { deletion ->
            root.collection(deletion.collectionName).document(deletion.documentId).delete().await()
        }
    }

    private fun attachListeners(root: com.google.firebase.firestore.DocumentReference) {
        if (listeners.isNotEmpty()) return
        listenCollection(root, COLLECTION_TASKS)
        listenCollection(root, COLLECTION_CATEGORIES)
        listenCollection(root, COLLECTION_OCCURRENCES)
        listenCollection(root, COLLECTION_DELETIONS)
    }

    private fun listenCollection(root: com.google.firebase.firestore.DocumentReference, name: String) {
        val listener = root.collection(name).addSnapshotListener { snapshot, error ->
            if (error != null) {
                onStatus("同步暂停：${error.message ?: "网络不可用"}")
                return@addSnapshotListener
            }
            if (snapshot == null) return@addSnapshotListener
            val next = snapshot.documents.mapNotNull { document -> document.data?.let { document.id to it } }.toMap()
            val removedCategories = if (name == COLLECTION_CATEGORIES) removedIds(snapshot) else emptySet()
            val removedOccurrences = if (name == COLLECTION_OCCURRENCES) removedIds(snapshot) else emptySet()
            scope.launch {
                var becameReady = false
                runCatching {
                    syncMutex.withLock {
                        cache[name] = next.toMutableMap()
                        repository.applyCloudSnapshot(cacheSnapshot(), removedCategories, removedOccurrences)
                        if (!snapshot.metadata.isFromCache) serverCollectionsLoaded += name
                        if (!remoteSnapshotReady && serverCollectionsLoaded.containsAll(
                                setOf(COLLECTION_TASKS, COLLECTION_CATEGORIES, COLLECTION_OCCURRENCES, COLLECTION_DELETIONS),
                            )
                        ) {
                            remoteSnapshotReady = true
                            becameReady = true
                        }
                    }
                }.onSuccess {
                    onStatus(when {
                        snapshot.metadata.hasPendingWrites() -> "正在上传更改…"
                        snapshot.metadata.isFromCache || !remoteSnapshotReady -> "显示缓存，等待云端连接…"
                        else -> "云端已同步"
                    })
                    if (becameReady) observeLocal(root)
                }.onFailure {
                    onStatus("同步失败：${it.message ?: "稍后会重试"}")
                }
            }
        }
        listeners += listener
    }

    private fun removedIds(snapshot: QuerySnapshot): Set<String> = snapshot.documentChanges
        .filter { it.type == com.google.firebase.firestore.DocumentChange.Type.REMOVED }
        .mapTo(mutableSetOf()) { it.document.id }

    private fun cacheSnapshot() = CloudPlanSnapshot(
        tasks = cache[COLLECTION_TASKS].orEmpty().mapNotNull { (id, data) -> data.toCloudTask()?.let { id to it } }.toMap(),
        categories = cache[COLLECTION_CATEGORIES].orEmpty().mapNotNull { (id, data) -> data.toCloudCategory()?.let { id to it } }.toMap(),
        occurrences = cache[COLLECTION_OCCURRENCES].orEmpty().mapNotNull { (id, data) -> data.toCloudOccurrence()?.let { id to it } }.toMap(),
        deletions = cache[COLLECTION_DELETIONS].orEmpty().mapNotNull { (id, data) -> data.toCloudDeletion()?.let { id to it } }.toMap(),
    )

    private fun observeLocal(root: com.google.firebase.firestore.DocumentReference) {
        if (localJob?.isActive == true) return
        localJob = scope.launch {
            repository.observeCloudSnapshot().collect { snapshot ->
                runCatching { syncMutex.withLock { syncLocalSnapshot(root, snapshot) } }
                    .onFailure { onStatus("同步稍后重试：${it.message ?: "网络不可用"}") }
            }
        }
    }

    private suspend fun syncLocalSnapshot(root: com.google.firebase.firestore.DocumentReference, snapshot: CloudPlanSnapshot) {
        if (!remoteSnapshotReady) return
        syncCollection(root, COLLECTION_TASKS, snapshot.tasks.mapValues { taskData(it.value) }, deleteMissing = false)
        syncCollection(root, COLLECTION_CATEGORIES, snapshot.categories.mapValues { categoryData(it.value) }, deleteMissing = false)
        syncCollection(root, COLLECTION_OCCURRENCES, snapshot.occurrences.mapValues { occurrenceData(it.value) }, deleteMissing = false)
        syncDeletionCollection(root, snapshot.deletions)
    }

    private suspend fun syncCollection(
        root: com.google.firebase.firestore.DocumentReference,
        name: String,
        local: Map<String, Map<String, Any?>>,
        deleteMissing: Boolean,
    ) {
        val remote = cache.getOrPut(name) { mutableMapOf() }
        local.forEach { (id, data) ->
            if (canonical(remote[id]) != canonical(data)) {
                root.collection(name).document(id).set(data).addOnFailureListener { error ->
                    onStatus("同步失败：${error.message ?: "稍后会重试"}")
                }
                remote[id] = data
            }
        }
        if (deleteMissing) {
            (remote.keys - local.keys).toList().forEach { id ->
                root.collection(name).document(id).delete().addOnFailureListener { error ->
                    onStatus("同步失败：${error.message ?: "稍后会重试"}")
                }
                remote.remove(id)
            }
        }
    }

    private fun syncDeletionCollection(
        root: com.google.firebase.firestore.DocumentReference,
        deletions: Map<String, CloudDeletionRecord>,
    ) {
        val remote = cache.getOrPut(COLLECTION_DELETIONS) { mutableMapOf() }
        deletions.forEach { (id, deletion) ->
            val data = deletionData(deletion)
            if (canonical(remote[id]) != canonical(data)) {
                root.collection(COLLECTION_DELETIONS).document(id).set(data).addOnFailureListener { error ->
                    onStatus("同步失败：${error.message ?: "稍后会重试"}")
                }
                remote[id] = data
            }
            val targetWasCached = cache[deletion.collectionName]?.remove(deletion.documentId) != null
            if (targetWasCached) {
                root.collection(deletion.collectionName).document(deletion.documentId).delete().addOnFailureListener { error ->
                    onStatus("同步失败：${error.message ?: "稍后会重试"}")
                }
            }
        }
        (remote.keys - deletions.keys).toList().forEach { id ->
            root.collection(COLLECTION_DELETIONS).document(id).delete().addOnFailureListener { error ->
                onStatus("同步失败：${error.message ?: "稍后会重试"}")
            }
            remote.remove(id)
        }
    }

    private fun taskData(record: CloudTaskRecord): Map<String, Any?> = with(record.task) {
        mapOf(
            "externalId" to externalId,
            "title" to title,
            "description" to description,
            "categoryName" to record.categoryName,
            "type" to type,
            "repeatKind" to repeatKind,
            "startDate" to startDate,
            "endDate" to endDate,
            "ruleEffectiveFrom" to ruleEffectiveFrom,
            "intervalDays" to intervalDays,
            "weeklyDays" to weeklyDays,
            "monthlyDay" to monthlyDay,
            "reminderEnabled" to reminderEnabled,
            "reminderTime" to reminderTime,
            "reminderOffset" to reminderOffset,
            "isPaused" to isPaused,
            "isDeleted" to isDeleted,
            "createdAt" to createdAt,
            "updatedAt" to updatedAt,
        )
    }

    private fun categoryData(category: CategoryEntity): Map<String, Any?> = mapOf(
        "name" to category.name,
        "normalizedName" to category.normalizedName,
        "colorHex" to String.format(Locale.ROOT, "#%08X", category.color),
        "sortOrder" to category.sortOrder,
        "createdAt" to category.createdAt,
        "updatedAt" to category.updatedAt,
    )

    private fun occurrenceData(record: CloudOccurrenceRecord): Map<String, Any?> = mapOf(
        "taskExternalId" to record.taskExternalId,
        "date" to record.date,
        "title" to record.title,
        "description" to record.description,
        "isCompleted" to record.isCompleted,
        "completedAt" to record.completedAt,
        "snoozedUntil" to record.snoozedUntil,
        "updatedAt" to record.updatedAt,
    )

    private fun deletionData(record: CloudDeletionRecord): Map<String, Any?> = mapOf(
        "collectionName" to record.collectionName,
        "documentId" to record.documentId,
        "deletedAt" to record.deletedAt,
    )

    private fun DocumentSnapshot.toCloudTask(): CloudTaskRecord? = data.toCloudTask()
    private fun Map<String, Any?>?.toCloudTask(): CloudTaskRecord? {
        val values = this ?: return null
        val externalId = values.string("externalId") ?: return null
        val title = values.string("title") ?: return null
        return CloudTaskRecord(
            task = TaskEntity(
                title = title,
                description = values.string("description").orEmpty(),
                type = values.string("type") ?: "daily",
                repeatKind = values.string("repeatKind") ?: "daily",
                startDate = values.string("startDate") ?: return null,
                endDate = values.string("endDate"),
                ruleEffectiveFrom = values.string("ruleEffectiveFrom"),
                intervalDays = values.int("intervalDays", 3),
                weeklyDays = values.string("weeklyDays") ?: "1,3,5",
                monthlyDay = values.int("monthlyDay", 15),
                reminderEnabled = values.boolean("reminderEnabled"),
                reminderTime = values.string("reminderTime") ?: "09:00",
                reminderOffset = values.int("reminderOffset", 0),
                isPaused = values.boolean("isPaused"),
                isDeleted = values.boolean("isDeleted"),
                createdAt = values.long("createdAt"),
                externalId = externalId,
                updatedAt = values.long("updatedAt"),
            ),
            categoryName = values.string("categoryName").orEmpty(),
        )
    }

    private fun DocumentSnapshot.toCloudCategory(): CategoryEntity? = data.toCloudCategory()
    private fun Map<String, Any?>?.toCloudCategory(): CategoryEntity? {
        val values = this ?: return null
        val name = values.string("name") ?: return null
        val normalized = values.string("normalizedName") ?: name.trim().lowercase(Locale.ROOT)
        val hex = (values.string("colorHex") ?: "#FF4F6BED").removePrefix("#")
        val color = runCatching { hex.toLong(16) }.getOrDefault(0xFF4F6BEDL)
        return CategoryEntity(
            name = name,
            normalizedName = normalized,
            color = color,
            sortOrder = values.int("sortOrder", 0),
            createdAt = values.long("createdAt"),
            updatedAt = values.long("updatedAt"),
        )
    }

    private fun DocumentSnapshot.toCloudOccurrence(): CloudOccurrenceRecord? = data.toCloudOccurrence()
    private fun Map<String, Any?>?.toCloudOccurrence(): CloudOccurrenceRecord? {
        val values = this ?: return null
        return CloudOccurrenceRecord(
            taskExternalId = values.string("taskExternalId") ?: return null,
            date = values.string("date") ?: return null,
            title = values.string("title").orEmpty(),
            description = values.string("description").orEmpty(),
            isCompleted = values.boolean("isCompleted"),
            completedAt = values.nullableLong("completedAt"),
            snoozedUntil = values.nullableLong("snoozedUntil"),
            updatedAt = values.long("updatedAt"),
        )
    }

    private fun DocumentSnapshot.toCloudDeletion(): CloudDeletionRecord? = data.toCloudDeletion()
    private fun Map<String, Any?>?.toCloudDeletion(): CloudDeletionRecord? {
        val values = this ?: return null
        val collectionName = values.string("collectionName") ?: return null
        if (collectionName !in setOf(COLLECTION_CATEGORIES, COLLECTION_OCCURRENCES)) return null
        return CloudDeletionRecord(
            collectionName = collectionName,
            documentId = values.string("documentId") ?: return null,
            deletedAt = values.long("deletedAt"),
        )
    }

    private fun Map<String, Any?>.string(key: String): String? = this[key] as? String
    private fun Map<String, Any?>.int(key: String, default: Int): Int = (this[key] as? Number)?.toInt() ?: default
    private fun Map<String, Any?>.long(key: String): Long = (this[key] as? Number)?.toLong() ?: 0L
    private fun Map<String, Any?>.nullableLong(key: String): Long? = (this[key] as? Number)?.toLong()
    private fun Map<String, Any?>.boolean(key: String): Boolean = this[key] as? Boolean ?: false

    private fun canonical(values: Map<String, Any?>?): String = values.orEmpty().toSortedMap().entries.joinToString("|") { (key, value) -> "$key=${value ?: "<null>"}" }

    companion object {
        const val COLLECTION_TASKS = CloudCollections.TASKS
        const val COLLECTION_CATEGORIES = CloudCollections.CATEGORIES
        const val COLLECTION_OCCURRENCES = CloudCollections.OCCURRENCES
        const val COLLECTION_DELETIONS = CloudCollections.DELETIONS
        private const val MAX_BATCH_WRITES = 400
    }
}
