package com.aestus.sleeplevelalarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.aestus.sleep.core.*

class WatchAlarmScheduler(private val context: Context) : AlarmScheduler {
    private val manager = context.getSystemService(AlarmManager::class.java)
    fun canSchedule() = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
    private fun operation(id: String, reason: AlarmReason, estimateAt: Long = 0) = PendingIntent.getBroadcast(context, 0,
        Intent(context, AlarmReceiver::class.java).setData(Uri.parse("sleepalarm://${reason.name.lowercase()}/$id"))
            .putExtra("sessionId", id).putExtra("reason", reason.name).putExtra("estimateAt", estimateAt),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    override fun schedule(session: SleepSession) {
        check(canSchedule()) { "정확한 알람 권한이 없습니다." }
        manager.setAlarmClock(AlarmManager.AlarmClockInfo(session.window.end, Notifications.open(context)), operation(session.id, AlarmReason.FINAL))
    }
    fun scheduleWakeNow(session: SleepSession, reason: AlarmReason, estimateAt: Long = 0) {
        require(reason != AlarmReason.FINAL)
        check(canSchedule()) { "정확한 알람 권한이 없습니다." }
        // An OS exact alarm grants the background-start exemption, including recovery after boot.
        manager.setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 100, Notifications.open(context)),
            operation(session.id, reason, estimateAt))
    }
    override fun cancel(sessionId: String) { AlarmReason.entries.forEach { manager.cancel(operation(sessionId, it)) } }
}
