package com.aestus.sleeplevelalarm

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import androidx.room.Room
import com.aestus.sleeplevelalarm.alarm.WatchAlarmScheduler
import com.aestus.sleeplevelalarm.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineExceptionHandler
import android.util.Log

class SleepApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO +
        CoroutineExceptionHandler { _, error -> Log.e("SleepLevelAlarm", "Background operation failed", error) })
    val database by lazy { Room.databaseBuilder(this, SleepDatabase::class.java, "sleep.db").build() }
    val settings by lazy { SettingsStore(this) }
    val scheduler by lazy { WatchAlarmScheduler(this) }
    val repository by lazy { SessionRepository(this, database, scheduler) }
    override fun onCreate() {
        super.onCreate()
        val jobs = getSystemService(JobScheduler::class.java)
        val retention = jobs.getPendingJob(20)
        if (retention == null || !retention.isRequireBatteryNotLow) {
            jobs.schedule(JobInfo.Builder(20, ComponentName(this, RetentionJobService::class.java))
                .setPeriodic(24 * 60 * 60 * 1000L).setPersisted(true)
                .setRequiresBatteryNotLow(true).build())
        }
    }
}
