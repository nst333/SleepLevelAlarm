package com.aestus.sleeplevelalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.AlarmManager
import com.aestus.sleep.core.*
import com.aestus.sleeplevelalarm.SleepApplication
import com.aestus.sleeplevelalarm.tracking.SleepTrackingService
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("sessionId") ?: return
        val reason = AlarmReason.entries.find { it.name == intent.getStringExtra("reason") } ?: AlarmReason.FINAL
        if (reason == AlarmReason.SMART && System.currentTimeMillis() - intent.getLongExtra("estimateAt", 0) !in 0..60_000) return
        RingingService.start(context, id, reason)
    }
}

class RecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val pending = goAsync()
        val app = context.applicationContext as SleepApplication
        app.scope.launch {
            try {
                val now = System.currentTimeMillis()
                if (intent.action == Intent.ACTION_TIME_CHANGED) {
                    context.stopService(Intent(context, SleepTrackingService::class.java))
                }
                val session = app.repository.restore(now) ?: return@launch
                if (session.state == SessionState.RINGING || now >= session.window.end) {
                    val due = session.firedAt ?: session.window.end
                    if (now - due <= 5 * MINUTE) {
                        app.scheduler.scheduleWakeNow(session, AlarmReason.RECOVERED)
                    } else app.repository.finish(session.id, false, "복구 시 알람 시각 경과 · 미실행", now)
                } else app.repository.note(session.id, "알람 복구됨 · 센서 추적은 앱에서 다시 시작해 주세요")
            } catch (e: Exception) {
                app.repository.active()?.let { app.repository.note(it.id, "알람 복구 실패 · 권한을 확인해 주세요") }
            } finally { pending.finish() }
        }
    }
}
