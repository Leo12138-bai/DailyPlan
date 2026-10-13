package com.dailyplan.app

import android.app.Application
import com.dailyplan.app.reminder.ReminderNotifications
import com.dailyplan.app.reminder.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DailyPlanApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ReminderNotifications.createChannel(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            ReminderScheduler(this@DailyPlanApplication).rescheduleAll()
        }
    }
}
