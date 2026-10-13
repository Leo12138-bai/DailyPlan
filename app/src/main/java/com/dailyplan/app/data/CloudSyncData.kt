package com.dailyplan.app.data

import android.util.Base64
import java.nio.charset.StandardCharsets

data class CloudTaskRecord(val task: TaskEntity, val categoryName: String)

data class CloudOccurrenceRecord(
    val taskExternalId: String,
    val date: String,
    val title: String,
    val description: String,
    val isCompleted: Boolean,
    val completedAt: Long?,
    val snoozedUntil: Long?,
    val updatedAt: Long,
)

data class CloudDeletionRecord(
    val collectionName: String,
    val documentId: String,
    val deletedAt: Long,
)

data class CloudPlanSnapshot(
    val tasks: Map<String, CloudTaskRecord> = emptyMap(),
    val categories: Map<String, CategoryEntity> = emptyMap(),
    val occurrences: Map<String, CloudOccurrenceRecord> = emptyMap(),
    val deletions: Map<String, CloudDeletionRecord> = emptyMap(),
)

object CloudCollections {
    const val TASKS = "tasks"
    const val CATEGORIES = "categories"
    const val OCCURRENCES = "occurrences"
    const val DELETIONS = "deletions"
}

fun categoryCloudDocumentId(normalizedName: String): String = Base64.encodeToString(
    normalizedName.trim().lowercase(java.util.Locale.ROOT).toByteArray(StandardCharsets.UTF_8),
    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
)

fun occurrenceCloudDocumentId(taskExternalId: String, date: String): String = "${taskExternalId}__${date}"

fun deletionCloudDocumentId(collectionName: String, documentId: String): String = Base64.encodeToString(
    "$collectionName:$documentId".toByteArray(StandardCharsets.UTF_8),
    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
)
