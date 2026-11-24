package com.aestus.sleeplevelalarm.tracking

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aestus.sleep.core.*
import com.aestus.sleeplevelalarm.SleepApplication
import com.aestus.sleeplevelalarm.alarm.Notifications
import com.aestus.sleeplevelalarm.data.RawRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.atomic.AtomicBoolean

class SleepTrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val batches = Channel<List<SensorSample>>(64)
    private var source: SensorSource? = null
    private var sessionId: String? = null
    @Volatile private var sensorAvailable = false
    @Volatile private var dataLoss = false
    private val stageDataLoss = AtomicBoolean()
    private var batteryRegistered = false
    private var preservingBattery = false
    private val app get() = application as SleepApplication
    override fun onBind(intent: Intent?) = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) checkBattery(intent)
        }
    }

    private fun checkBattery(intent: Intent?): Boolean {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (scale > 0 && level in 0..scale) (level.toLong() * 100 / scale).toInt() else -1
        val plugged = (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val charging = plugged && status in listOf(BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL)
        if (!TrackingPowerPolicy.preserveAlarmBattery(percent, charging)) return false
        if (!preservingBattery) {
            preservingBattery = true
            source?.stop()
            sessionId?.let { id ->
                app.scope.launch {
                    app.repository.note(id, "배터리 ${percent}% · 방전 방지를 위해 센서 추적 중지 · 최종 알람 유지")
                }
            }
            stopSelf()
        }
        return true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("sessionId") ?: run { stopSelf(); return START_NOT_STICKY }
        if (sessionId == id) return START_NOT_STICKY
        sessionId = id
        try {
            ServiceCompat.startForeground(this, Notifications.TRACKING_ID,
                Notifications.tracking(this, "최종 알람 예약됨 · 센서 연결 중"),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0)
        } catch (e: Exception) {
            app.scope.launch { app.repository.note(id, "추적 시작 제한 · 일반 알람 유지") }
            stopSelf(); return START_NOT_STICKY
        }
        if (checkBattery(registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))) return START_NOT_STICKY
        ContextCompat.registerReceiver(this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        batteryRegistered = true
        TrackingState.status.value = TrackingStatus(true, "센서 연결 중")
        source = SamsungSensorSource(this).apply {
            start({ data ->
                if (!batches.trySend(data).isSuccess) {
                    if (data.firstOrNull()?.accelerationMagnitudeG != null) stageDataLoss.set(true)
                    else {
                        dataLoss = true
                        TrackingState.status.value = TrackingStatus(true, "수집 지연 · 데이터 일부 누락")
                    }
                }
            }, { message ->
                sensorAvailable = message == "센서 연결됨 · 데이터 수신 대기"
                TrackingState.status.value = TrackingState.status.value.copy(message = message, estimate = null, sleepStage = null)
                if (!sensorAvailable) {
                    app.scope.launch { app.repository.note(id, message) }
                    stopSelf()
                }
            })
        }

        scope.launch(Dispatchers.IO) {
            var classifier: SleepStageClassifier? = null
            try {
                var session = app.repository.get(id) ?: return@launch
                if (session.state != SessionState.TRACKING) return@launch
                classifier = runCatching { SleepStageClassifier(this@SleepTrackingService) }
                    .onFailure { Log.w("SleepTracking", "Sleep stage model unavailable", it) }
                    .getOrNull()
                val stageWindow = SleepStageWindow()
                val observedSession = app.repository.observeSession(id).stateIn(this, SharingStarted.Eagerly, session)
                var aggregator = EpochAggregator()
                var engine = SleepEngine()
                var lastHeartFlushAt = SystemClock.elapsedRealtime()
                RawRecorder(this@SleepTrackingService, id, session.startedAt).use { recorder ->
                    while (isActive) {
                        val data = withTimeoutOrNull(TrackingPowerPolicy.IDLE_PROCESSING_MS) { batches.receive() }
                        val now = System.currentTimeMillis()
                        val elapsed = SystemClock.elapsedRealtime()
                        // Screen-off HR batches need a bounded flush to meet the 60-second freshness limit.
                        if (sensorAvailable && TrackingPowerPolicy.heartRateFlushDue(lastHeartFlushAt, elapsed)) {
                            withContext(Dispatchers.Main) { source?.flushHeartRate() }
                            lastHeartFlushAt = elapsed
                        }
                        if (dataLoss) {
                            aggregator = EpochAggregator()
                            engine = SleepEngine()
                            stageWindow.reset()
                            dataLoss = false
                        }
                        if (stageDataLoss.getAndSet(false)) stageWindow.reset()
                        if (data != null) {
                            val currentData = data.filter { it.at >= session.startedAt }
                            if (data.firstOrNull()?.accelerationMagnitudeG == null) recorder.append(currentData, now)
                            aggregator.add(currentData, now)
                        }
                        session = observedSession.value ?: break
                        if (session.state != SessionState.TRACKING) break
                        for (features in aggregator.drain(now)) {
                            var estimate = engine.update(features)
                            if (!sensorAvailable) estimate = estimate.copy(quality = DataQuality.INSUFFICIENT, wakeSuitable = false)
                            if (now - estimate.at > MINUTE) estimate = estimate.copy(quality = DataQuality.STALE, wakeSuitable = false)
                            val stage = classifier?.let { model ->
                                if (!sensorAvailable || now - features.end !in 0..MINUTE) {
                                    stageWindow.reset()
                                    null
                                } else stageWindow.add(features)?.let { window ->
                                    runCatching { model.predict(window) }
                                        .onFailure {
                                            Log.w("SleepTracking", "Sleep stage inference failed", it)
                                            classifier = null
                                            runCatching { model.close() }
                                        }
                                        .getOrNull()
                                }
                            }
                            app.repository.record(id, features, estimate, now)
                            if (session.onset == null && now <= session.startedAt + 30 * MINUTE) {
                                estimate.onsetCandidate?.let { onset -> session = app.repository.correctOnset(id, onset, now) ?: session }
                            }
                            TrackingState.status.value = TrackingStatus(true,
                                if (stage != null) "센서 수집 중 · AI 수면 단계"
                                else if (estimate.quality == DataQuality.GOOD) "센서 수집 중 · 실험 추정"
                                else "데이터 부족/미착용 · 일반 알람 유지",
                                estimate, features.heartRate, estimate.at, stage)
                            if (AlarmPolicy.shouldWake(session, estimate, now)) {
                                app.scheduler.scheduleWakeNow(session, AlarmReason.SMART, estimate.at)
                            }
                        }
                        val last = TrackingState.status.value.lastDataAt
                        if (last != null && now - last > MINUTE) TrackingState.status.value = TrackingState.status.value.copy(
                            message = "센서 데이터 지연 · 일반 알람 유지", estimate = null, sleepStage = null)
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) app.repository.note(id, "센서 수집 중단: ${e.javaClass.simpleName} · 일반 알람 유지")
            } finally {
                runCatching { classifier?.close() }
                withContext(NonCancellable + Dispatchers.Main) { stopSelf() }
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        if (batteryRegistered) unregisterReceiver(batteryReceiver)
        source?.stop(); scope.cancel(); batches.close()
        TrackingState.status.value = TrackingStatus()
        super.onDestroy()
    }
}
