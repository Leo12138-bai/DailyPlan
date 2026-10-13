package com.dailyplan.app.data

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class TaskDatabaseMigrationTest {
    @Test
    fun versionOneMigrationAddsCategoryAndStableImportFieldsWithoutDroppingData() {
        val statements = mutableListOf<String>()
        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL") statements += args!![0] as String
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        } as SupportSQLiteDatabase

        TaskDatabase.MIGRATION_1_2.migrate(database)

        val sql = statements.joinToString("\n").uppercase()
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS CATEGORIES"))
        assertTrue(sql.contains("ALTER TABLE TASKS ADD COLUMN CATEGORYID INTEGER"))
        assertTrue(sql.contains("ALTER TABLE TASKS ADD COLUMN EXTERNALID TEXT"))
        assertTrue(sql.contains("CREATE UNIQUE INDEX"))
        assertFalse(sql.contains("DROP TABLE"))
        assertFalse(sql.contains("DELETE FROM TASKS"))
        assertFalse(sql.contains("DELETE FROM OCCURRENCES"))
    }
}
