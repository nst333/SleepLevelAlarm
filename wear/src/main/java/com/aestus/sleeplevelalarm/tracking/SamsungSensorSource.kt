package com.aestus.sleeplevelalarm.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import com.aestus.sleep.core.*
import com.samsung.android.service.health.tracking.*
import com.samsung.android.service.health.tracking.data.*
import kotlin.math.sqrt

class SamsungSensorSource(context: Context) : SensorSource {
    private var onSamples: (List<SensorSample>) -> Unit = {}
    private var onStatus: (String) -> Unit = {}
    private val trackers = mutableListOf<HealthTracker>()
    private var heartTracker: HealthTracker? = null
    private var stopped = false
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val stageMotion = mutableListOf<SensorSample>()
    private val stageMotionListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (stopped) return
            val at = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000
            val (x, y, z) = event.values
            val magnitude = sqrt(x.toDouble() * x + y.toDouble() * y + z.toDouble() * z) / SensorManager.GRAVITY_EARTH
            stageMotion.add(SensorSample(at, accelerationMagnitudeG = magnitude))
            if (stageMotion.size >= 25 || at - stageMotion.first().at >= 1_000) {
                onSamples(stageMotion.toList())
                stageMotion.clear()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }
    private val service: HealthTrackingService = HealthTrackingService(object : ConnectionListener {
        override fun onConnectionSuccess() {
            if (stopped) return
            try {
                val supported = service.trackingCapability.supportHealthTrackerTypes
                val required = listOf(HealthTrackerType.HEART_RATE_CONTINUOUS, HealthTrackerType.ACCELEROMETER_CONTINUOUS)
                if (!supported.containsAll(required)) {
                    onStatus("필수 센서 미지원 · 일반 알람 유지")
                    return
                }
                required.forEach { type ->
                    val tracker = service.getHealthTracker(type)
                    trackers.add(tracker)
                    if (type == HealthTrackerType.HEART_RATE_CONTINUOUS) heartTracker = tracker
                    tracker.setEventListener(listener(type))
                }
                runCatching {
                    sensorManager?.let { manager ->
                        manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { accelerometer ->
                            manager.registerListener(stageMotionListener, accelerometer, 40_000, 1_000_000)
                        }
                    }
                }.onFailure {
                    Log.w("SamsungSensorSource", "Sleep stage accelerometer unavailable", it)
                }
                onStatus("센서 연결됨 · 데이터 수신 대기")
            } catch (e: Exception) { onStatus("센서 연결 실패 · ${e.javaClass.simpleName} · 일반 알람 유지") }
        }
        override fun onConnectionEnded() { if (!stopped) onStatus("센서 연결 종료 · 일반 알람 유지") }
        override fun onConnectionFailed(exception: HealthTrackerException) {
            onStatus("삼성 센서 연결 실패 (${exception.errorCode}) · 개발 모드/서비스 확인")
        }
    }, context)

    override fun start(onSamples: (List<SensorSample>) -> Unit, onStatus: (String) -> Unit) {
        this.onSamples = onSamples; this.onStatus = onStatus
        stopped = false
        try { service.connectService() } catch (e: Exception) { onStatus("센서 서비스 없음 · 일반 알람 유지") }
    }

    private fun listener(type: HealthTrackerType) = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: MutableList<DataPoint>) {
            if (stopped) return
            try {
                val samples = dataPoints.map { p ->
                    val at = if (p.timestamp > 1_000_000_000_000L) p.timestamp else
                        System.currentTimeMillis() - SystemClock.elapsedRealtime() + p.timestamp
                    if (type == HealthTrackerType.HEART_RATE_CONTINUOUS) {
                        val status = p.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS)
                        SensorSample(at, p.getValue(ValueKey.HeartRateSet.HEART_RATE)?.toDouble(),
                            offBody = status == -3, heartRateValid = status == 1)
                    } else {
                        // Samsung's linear acceleration excludes gravity; raw-to-g = 1 / (16383.75 * 4).
                        val x = p.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X).toDouble() / 4095.9375
                        val y = p.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y).toDouble() / 4095.9375
                        val z = p.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z).toDouble() / 4095.9375
                        SensorSample(at, accelerationG = sqrt(x * x + y * y + z * z))
                    }
                }
                // Screen-off batches carry all IBIs in the first point. Assign beats across the batch.
                val beats = if (type == HealthTrackerType.HEART_RATE_CONTINUOUS && samples.isNotEmpty()) {
                    val intervals = dataPoints.flatMap { p ->
                        val values = p.getValue(ValueKey.HeartRateSet.IBI_LIST) ?: emptyList()
                        val statuses = p.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST) ?: emptyList()
                        val validHr = p.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS) == 1
                        values.mapIndexed { i, value -> value to (validHr && statuses.getOrNull(i) == 0) }
                    }
                    distributeBeats(intervals, samples.maxOf { it.at })
                } else emptyList()
                onSamples(samples + beats)
            } catch (e: Exception) { onStatus("센서 데이터 해석 실패 · 일반 알람 유지") }
        }
        override fun onFlushCompleted() = Unit
        override fun onError(error: HealthTracker.TrackerError) {
            onStatus(when (error) {
                HealthTracker.TrackerError.PERMISSION_ERROR -> "센서 권한 없음 · 일반 알람 유지"
                HealthTracker.TrackerError.SDK_POLICY_ERROR -> "삼성 SDK 개발 모드를 확인해 주세요 · 일반 알람 유지"
                else -> "센서 오류 · 일반 알람 유지"
            })
        }
    }

    override fun flushHeartRate() {
        if (!stopped) heartTracker?.flush()
    }

    override fun stop() {
        stopped = true
        sensorManager?.unregisterListener(stageMotionListener)
        stageMotion.clear()
        trackers.forEach { runCatching { it.unsetEventListener() } }
        trackers.clear()
        heartTracker = null
        runCatching { service.disconnectService() }
    }
}
