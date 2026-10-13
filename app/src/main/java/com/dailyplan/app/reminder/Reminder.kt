package com.dailyplan.app.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dailyplan.app.MainActivity
import com.dailyplan.app.R
import com.dailyplan.app.data.OccurrenceEntity
import com.dailyplan.app.data.TaskDatabase
import com.dailyplan.app.data.TaskRepository
import com.dailyplan.app.data.TaskEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private const val CHANNEL_ID = "task_reminders"
private const val EXTRA_TASK_ID = "task_id"
private const val EXTRA_DATE = "task_date"
private const val EXTRA_SNOOZE = "snooze"

class ReminderScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    suspend fun scheduleNext(taskId: Long): Boolean {
        return try {
            cancel(taskId)
            val dao = TaskDatabase.get(context).taskDao()
            val task = dao.task(taskId) ?: return true
            if (!task.reminderEnabled || task.isPaused || task.isDeleted) return true

            val now = System.currentTimeMillis()
            var date = LocalDate.now()
            val searchDays = if (task.type == "custom" && task.repeatKind == "interval") {
                maxOf(367, task.intervalDays.coerceIn(1, 36_500) + 2)
            } else 367
            repeat(searchDays) {
                if (com.dailyplan.app.data.RecurrenceCalculator.occursOn(task, date)) {
                    val time = LocalTime.parse(task.reminderTime)
                    val trigger = date.atTime(time).minusMinutes(task.reminderOffset.toLong())
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    if (trigger > now) {
                        val row = dao.occurrence(task.id, date.toString())
                        if (row?.isCompleted != true) {
                            setAlarm(task, date.toString(), trigger, snooze = false)
                            return true
                        }
                    }
                }
                date = date.plusDays(1)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun rescheduleAll(): Int {
        val dao = TaskDatabase.get(context).taskDao()
        var failures = 0
        dao.activeTasks().forEach { task -> if (!scheduleNext(task.id)) failures++ }
        dao.snoozedOccurrences().forEach { row ->
            try {
                val until = row.snoozedUntil ?: return@forEach
                if (until > System.currentTimeMillis()) {
                    dao.task(row.taskId)?.takeIf { !it.isPaused && !it.isDeleted }?.let {
                        setAlarm(it, row.date, until, snooze = true)
                    }
                }
            } catch (_: Exception) {
                failures++
            }
        }
        return failures
    }

    fun cancel(taskId: Long) {
        runCatching { pendingIntentOrNull(taskId, "next", null)?.let(alarmManager::cancel) }
    }

    fun cancelSnooze(taskId: Long, date: String) {
        runCatching { pendingIntentOrNull(taskId, "snooze", date)?.let(alarmManager::cancel) }
    }

    suspend fun scheduleSnooze(taskId: Long, date: String, triggerAt: Long) {
        runCatching {
            TaskDatabase.get(context).taskDao().task(taskId)
                ?.takeIf { !it.isPaused && !it.isDeleted }
                ?.let { setAlarm(it, date, triggerAt, snooze = true) }
        }
    }

    private fun setAlarm(task: TaskEntity, date: String, triggerAt: Long, snooze: Boolean) {
        val operation = pendingIntent(
            task.id,
            if (snooze) "snooze" else "next",
            if (snooze) date else null,
            PendingIntent.FLAG_UPDATE_CURRENT,
            date,
            snooze,
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        }
    }

    private fun pendingIntent(
        taskId: Long,
        kind: String,
        date: String?,
        flags: Int,
        extraDate: String? = null,
        snooze: Boolean = false,
    ): PendingIntent {
        val data = Uri.parse("dailyplan://alarm/$taskId/$kind/${date.orEmpty()}")
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = "com.dailyplan.app.REMINDER.$kind"
            this.data = data
            putExtra(EXTRA_TASK_ID, taskId)
            if (extraDate != null) putExtra(EXTRA_DATE, extraDate)
            putExtra(EXTRA_SNOOZE, snooze)
        }
        val requestCode = (taskId * 31 + kind.hashCode() + (date?.hashCode() ?: 0)).toInt()
        return PendingIntent.getBroadcast(context, requestCode, intent, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun pendingIntentOrNull(taskId: Long, kind: String, date: String?): PendingIntent? {
        val data = Uri.parse("dailyplan://alarm/$taskId/$kind/${date.orEmpty()}")
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = "com.dailyplan.app.REMINDER.$kind"
            this.data = data
        }
        val requestCode = (taskId * 31 + kind.hashCode() + (date?.hashCode() ?: 0)).toInt()
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
    }
}

object ReminderNotifications {
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "任务提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "任务到期时提醒你"
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun show(context: Context, row: OccurrenceEntity, reminderOffset: Int = 0) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(
            context,
            row.taskId.toInt(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val complete = actionIntent(context, row, ReminderActionReceiver.ACTION_COMPLETE, 1)
        val snooze = actionIntent(context, row, ReminderActionReceiver.ACTION_SNOOZE, 2)
        val message = row.description.ifBlank {
            if (reminderOffset > 0) "距离任务时间还有${reminderOffset}分钟，提前准备一下。"
            else "你设置的任务现在开始，完成后记得打卡。"
        }
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("每日计划 · 任务提醒")
            .setContentText(row.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(R.drawable.ic_notification, "完成任务", complete)
            .addAction(R.drawable.ic_notification, "10分钟后提醒", snooze)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(row.taskId, row.date), notification)
    }

    fun cancel(context: Context, taskId: Long, date: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(taskId, date))
    }

    private fun actionIntent(context: Context, row: OccurrenceEntity, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            notificationId(row.taskId, row.date) * 3 + code,
            Intent(context, ReminderActionReceiver::class.java).apply {
                this.action = action
                data = Uri.parse("dailyplan://action/${row.taskId}/${row.date}/$code")
                putExtra(EXTRA_TASK_ID, row.taskId)
                putExtra(EXTRA_DATE, row.date)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun notificationId(taskId: Long, date: String): Int = (taskId * 37 + date.hashCode()).toInt()
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, 0L)
        val date = intent.getStringExtra(EXTRA_DATE) ?: return
        if (taskId == 0L) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repo = TaskRepository(context)
                val task = TaskDatabase.get(context).taskDao().task(taskId)
                val row = repo.occurrence(taskId, LocalDate.parse(date))
                if (task != null && row != null && !task.isPaused && !task.isDeleted && !row.isCompleted) {
                    val snooze = intent.getBooleanExtra(EXTRA_SNOOZE, false)
                    if (snooze) repo.setSnooze(taskId, date, null)
                    ReminderNotifications.show(context, row, if (snooze) 0 else task.reminderOffset)
                    if (!snooze) ReminderScheduler(context).scheduleNext(taskId)
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}

class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, 0L)
        val date = intent.getStringExtra(EXTRA_DATE) ?: return
        if (taskId == 0L) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repo = TaskRepository(context)
                val dao = TaskDatabase.get(context).taskDao()
                val task = dao.task(taskId)
                val row = dao.occurrence(taskId, date)
                ReminderNotifications.cancel(context, taskId, date)
                if (task == null || task.isPaused || task.isDeleted || row == null || row.isCompleted) return@launch
                when (intent.action) {
                    ACTION_COMPLETE -> repo.markComplete(taskId, date)
                    ACTION_SNOOZE -> {
                        val until = System.currentTimeMillis() + 10 * 60 * 1000L
                        repo.setSnooze(taskId, date, until)
                        ReminderScheduler(context).scheduleSnooze(taskId, date, until)
                    }
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_COMPLETE = "com.dailyplan.app.ACTION_COMPLETE"
        const val ACTION_SNOOZE = "com.dailyplan.app.ACTION_SNOOZE"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { TaskRepository(context).refreshReminders() } catch (_: Exception) { } finally { pending.finish() }
        }
    }
}
