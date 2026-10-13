package com.dailyplan.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE isDeleted = 0 ORDER BY createdAt DESC")
    suspend fun activeTasks(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE isDeleted = 0 ORDER BY createdAt DESC")
    fun observeActiveTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun task(id: Long): TaskEntity?

    @Query("SELECT * FROM tasks WHERE externalId = :externalId LIMIT 1")
    suspend fun taskByExternalId(externalId: String): TaskEntity?

    @Query("SELECT * FROM tasks")
    suspend fun allTasksIncludingDeleted(): List<TaskEntity>

    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun observeAllTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE isDeleted = 0 AND type = 'once' AND startDate < :today ORDER BY startDate")
    suspend fun overdueOnceTasks(today: String): List<TaskEntity>

    @Query("SELECT MIN(startDate) FROM tasks")
    suspend fun earliestStartDate(): String?

    @Insert
    suspend fun insertTask(task: TaskEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTasks(tasks: List<TaskEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: TaskEntity)

    @Update
    suspend fun updateTask(task: TaskEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOccurrences(rows: List<OccurrenceEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOccurrence(row: OccurrenceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOccurrence(row: OccurrenceEntity)

    @Query("SELECT * FROM occurrences ORDER BY date, title")
    fun observeAllOccurrences(): Flow<List<OccurrenceEntity>>

    @Query("SELECT * FROM occurrences ORDER BY date, title")
    suspend fun allOccurrences(): List<OccurrenceEntity>

    @Query("SELECT * FROM sync_deletions")
    fun observeSyncDeletions(): Flow<List<SyncDeletionEntity>>

    @Query("SELECT * FROM sync_deletions")
    suspend fun syncDeletions(): List<SyncDeletionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncDeletion(deletion: SyncDeletionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncDeletions(deletions: List<SyncDeletionEntity>)

    @Query("DELETE FROM sync_deletions")
    suspend fun clearSyncDeletions()

    @Query("DELETE FROM occurrences WHERE taskId = :taskId AND date = :date")
    suspend fun deleteOccurrence(taskId: Long, date: String)

    @Query("SELECT * FROM occurrences WHERE date BETWEEN :from AND :to ORDER BY date, title")
    suspend fun occurrences(from: String, to: String): List<OccurrenceEntity>

    @Query("SELECT * FROM occurrences WHERE date BETWEEN :from AND :to ORDER BY date, title")
    fun observeOccurrences(from: String, to: String): Flow<List<OccurrenceEntity>>

    @Query("SELECT o.* FROM occurrences o INNER JOIN tasks t ON t.id = o.taskId WHERE t.type = 'once' AND t.isDeleted = 0 AND o.date < :today ORDER BY o.date")
    fun observeOverdueOnce(today: String): Flow<List<OccurrenceEntity>>

    @Query("SELECT * FROM occurrences WHERE taskId = :taskId AND date = :date LIMIT 1")
    suspend fun occurrence(taskId: Long, date: String): OccurrenceEntity?

    @Update
    suspend fun updateOccurrence(row: OccurrenceEntity)

    @Query("DELETE FROM occurrences WHERE taskId = :taskId AND date >= :from AND isCompleted = 0")
    suspend fun removePendingFrom(taskId: Long, from: String)

    @Query("UPDATE occurrences SET title = :title, description = :description WHERE taskId = :taskId AND date >= :from AND isCompleted = 0")
    suspend fun updatePendingOccurrenceText(taskId: Long, from: String, title: String, description: String)

    @Query("SELECT * FROM occurrences WHERE snoozedUntil IS NOT NULL AND isCompleted = 0")
    suspend fun snoozedOccurrences(): List<OccurrenceEntity>

    @Query("SELECT * FROM categories ORDER BY sortOrder, id")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sortOrder, id")
    suspend fun categories(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun category(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE normalizedName = :normalizedName LIMIT 1")
    suspend fun categoryByNormalizedName(normalizedName: String): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategory(category: CategoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategory(category: CategoryEntity)

    @Update
    suspend fun updateCategory(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteCategory(id: Long)

    @Query("SELECT COUNT(*) FROM tasks WHERE categoryId = :categoryId AND isDeleted = 0")
    suspend fun activeTaskCountInCategory(categoryId: Long): Int

    @Query("UPDATE tasks SET categoryId = NULL, updatedAt = :updatedAt WHERE categoryId = :categoryId")
    suspend fun moveCategoryTasksToUnclassified(categoryId: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE tasks SET categoryId = :categoryId, updatedAt = :updatedAt WHERE id IN (:ids) AND isDeleted = 0")
    suspend fun setTaskCategory(ids: List<Long>, categoryId: Long?, updatedAt: Long = System.currentTimeMillis()): Int
}

@Database(entities = [TaskEntity::class, OccurrenceEntity::class, CategoryEntity::class, SyncDeletionEntity::class], version = 4, exportSchema = false)
abstract class TaskDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    companion object {
        @Volatile private var instance: TaskDatabase? = null

        fun get(context: Context): TaskDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                TaskDatabase::class.java,
                "daily-plan.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) = seedDefaultCategories(db)
                })
                .build().also { instance = it }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS categories (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        normalizedName TEXT NOT NULL,
                        color INTEGER NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )""".trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_categories_normalizedName ON categories(normalizedName)")
                db.execSQL("ALTER TABLE tasks ADD COLUMN externalId TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE tasks SET externalId = lower(hex(randomblob(16))) WHERE externalId = ''")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tasks_externalId ON tasks(externalId)")
                db.execSQL("ALTER TABLE tasks ADD COLUMN categoryId INTEGER")
                seedDefaultCategories(db)
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE tasks SET updatedAt = createdAt")
                db.execSQL("ALTER TABLE categories ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE categories SET updatedAt = createdAt")
                db.execSQL("ALTER TABLE occurrences ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE occurrences SET updatedAt = COALESCE(completedAt, 0)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS sync_deletions (
                        collectionName TEXT NOT NULL,
                        documentId TEXT NOT NULL,
                        deletedAt INTEGER NOT NULL,
                        PRIMARY KEY(collectionName, documentId)
                    )""".trimIndent(),
                )
            }
        }

        private fun seedDefaultCategories(db: SupportSQLiteDatabase) {
            val defaults = listOf(
                Triple("科研", "#4F6BED", 0),
                Triple("学习", "#7B61A8", 1),
                Triple("工作", "#168A72", 2),
                Triple("生活", "#D47D35", 3),
                Triple("运动", "#D24D57", 4),
            )
            defaults.forEach { (name, hexColor, order) ->
                db.execSQL(
                    "INSERT OR IGNORE INTO categories(name, normalizedName, color, sortOrder, createdAt) VALUES(?, ?, ?, ?, ?)",
                    arrayOf(name, normalizeCategoryName(name), (java.lang.Long.parseLong(hexColor.removePrefix("#"), 16) or (0xFFL shl 24)), order, System.currentTimeMillis()),
                )
            }
        }
    }
}
