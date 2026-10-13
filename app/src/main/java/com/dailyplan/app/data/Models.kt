package com.dailyplan.app.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "tasks", indices = [Index(value = ["externalId"], unique = true)])
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String = "",
    val type: String = "daily",
    val repeatKind: String = "daily",
    val startDate: String,
    val endDate: String? = null,
    val ruleEffectiveFrom: String? = null,
    val intervalDays: Int = 3,
    val weeklyDays: String = "1,3,5",
    val monthlyDay: Int = 15,
    val reminderEnabled: Boolean = false,
    val reminderTime: String = "09:00",
    val reminderOffset: Int = 0,
    val isPaused: Boolean = false,
    val isDeleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val externalId: String = UUID.randomUUID().toString(),
    val categoryId: Long? = null,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "categories", indices = [Index(value = ["normalizedName"], unique = true)])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val normalizedName: String,
    val color: Long,
    val sortOrder: Int,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
)

fun normalizeCategoryName(name: String): String = name.trim().lowercase(java.util.Locale.ROOT)

@Entity(tableName = "occurrences", primaryKeys = ["taskId", "date"])
data class OccurrenceEntity(
    val taskId: Long,
    val date: String,
    val title: String,
    val description: String,
    val isCompleted: Boolean = false,
    val completedAt: Long? = null,
    val snoozedUntil: Long? = null,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "sync_deletions", primaryKeys = ["collectionName", "documentId"])
data class SyncDeletionEntity(
    val collectionName: String,
    val documentId: String,
    val deletedAt: Long,
)
