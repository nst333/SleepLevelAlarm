package com.aestus.sleeplevelalarm.alarm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.*
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aestus.sleep.core.*
import com.aestus.sleeplevelalarm.SleepApplication
import com.aestus.sleeplevelalarm.tracking.SleepTrackingService
import kotlinx.coroutines.*

class RingingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ringtone: Ringtone? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringingId: String? = null
    private var timeout: Job? = null
    private val app get() = application as SleepApplication
    private val vibrator get() = getSystemService(Vibrator::class.java)

    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("sessionId") ?: run { stopSelf(); return START_NOT_STICKY }
        if (intent.action == STOP) {
            if (ringingId == null || ringingId == id) scope.launch { finish(id, "사용자가 알람 종료") }
            return START_NOT_STICKY
        }
        if (ringingId != null) return START_REDELIVER_INTENT
        ringingId = id
        ServiceCompat.startForeground(this, Notifications.ALARM_ID, Notifications.alarm(this, id),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        scope.launch {
            try {
                val reason = intent.getStringExtra("reason")?.let { value -> AlarmReason.entries.find { it.name == value } } ?: AlarmReason.FINAL
                val now = System.currentTimeMillis()
                val current = withContext(Dispatchers.IO) { app.repository.get(id) }
                val session = if (current?.state == SessionState.RINGING) current else
                    withContext(Dispatchers.IO) { app.repository.claimAlarm(id, reason, now) }
                if (session == null) { stopSelf(); return@launch }
                val remaining = (session.firedAt!! + 5 * MINUTE - now).coerceAtMost(5 * MINUTE)
                if (remaining <= 0) { finish(id, "알람 출력 시간 종료"); return@launch }
                stopService(Intent(this@RingingService, SleepTrackingService::class.java))
                wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepAlarm:Ring").apply { acquire(remaining + 10_000) }
                if (vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 350, 700, 1250), 0),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                if (session.settings.sound) {
                    ringtone = RingtoneManager.getRingtone(this@RingingService, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)).apply {
                        audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                        isLooping = true
                        play()
                    }
                }
                timeout = launch { delay(remaining); finish(id, "5분 미응답 · 알람 출력 종료") }
            } catch (e: Exception) {
                // Output failure is recorded; the notification still offers a visible alarm.
                withContext(Dispatchers.IO) { app.repository.note(id, "알람 출력 확인 필요: ${e.javaClass.simpleName}") }
                timeout = launch { delay(5 * MINUTE); finish(id, "알람 출력 오류 후 종료") }
            }
        }
        return START_REDELIVER_INTENT
    }

    private suspend fun finish(id: String, note: String) {
        ringtone?.stop(); vibrator.cancel()
        withContext(Dispatchers.IO) { app.repository.finish(id, false, note, System.currentTimeMillis()) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        timeout?.cancel(); scope.cancel(); ringtone?.stop(); vibrator.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
    companion object {
        const val STOP = "stop_alarm"
        fun start(context: Context, id: String, reason: AlarmReason) {
            ContextCompat.startForegroundService(context, Intent(context, RingingService::class.java)
                .putExtra("sessionId", id).putExtra("reason", reason.name))
        }
    }
}
