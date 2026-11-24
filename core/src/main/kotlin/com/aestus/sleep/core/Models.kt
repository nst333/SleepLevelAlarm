package com.aestus.sleep.core

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

const val MINUTE = 60_000L
const val EPOCH_MS = 30_000L
const val ALGORITHM_VERSION = "rules-v1-uncalibrated"

enum class AlarmMode { DEADLINE, CYCLES }
enum class SessionState { TRACKING, RINGING, COMPLETED, CANCELLED, FAILED }
enum class DataQuality { GOOD, INSUFFICIENT, OFF_BODY, STALE }
enum class SleepState { UNKNOWN, AWAKE, ASLEEP_CANDIDATE }
enum class SleepStage { WAKE, N1, N2, N3, REM }
enum class AlarmReason { SMART, FINAL, RECOVERED }

data class AlarmSettings(
    val mode: AlarmMode = AlarmMode.DEADLINE,
    val hour: Int = 7,
    val minute: Int = 0,
    val cycles: Int = 5,
    val sound: Boolean = true,
) {
    init { require(hour in 0..23 && minute in 0..59 && cycles in 1..6) }
}

data class WakeWindow(val start: Long, val target: Long, val end: Long) {
    init { require(start <= target && target <= end) }
}

data class SleepSession(
    val id: String = UUID.randomUUID().toString(),
    val startedAt: Long,
    val settings: AlarmSettings,
    val window: WakeWindow,
    val onset: Long? = null,
    val state: SessionState = SessionState.TRACKING,
    val firedAt: Long? = null,
    val reason: AlarmReason? = null,
    val endedAt: Long? = null,
    val note: String = "",
    val algorithmVersion: String = ALGORITHM_VERSION,
    val batteryStart: Int = -1,
    val batteryEnd: Int = -1,
)

// The timestamp is measurement time, never callback delivery time. Acceleration is linear g.
data class SensorSample(
    val at: Long,
    val heartRate: Double? = null,
    val ibiMs: List<Int> = emptyList(),
    val accelerationG: Double? = null,
    val offBody: Boolean = false,
    val heartRateValid: Boolean = false,
    val accelerationMagnitudeG: Double? = null,
)

data class EpochFeatures(
    val start: Long,
    val end: Long,
    val heartRate: Double?,
    val rmssdMs: Double?,
    val motionRmsG: Double?,
    val quality: DataQuality,
    val hrCount: Int,
    val motionCount: Int,
    val accelerationMagnitudeStdG: Double? = null,
)

data class SleepEstimate(
    val at: Long,
    val state: SleepState,
    val quality: DataQuality,
    val wakeSuitable: Boolean = false,
    val onsetCandidate: Long? = null,
    val baselineHr: Double? = null,
    val algorithmVersion: String = ALGORITHM_VERSION,
    val suitableEpochs: Int = 0,
)

interface AlarmScheduler {
    fun schedule(session: SleepSession)
    fun cancel(sessionId: String)
}

interface SensorSource {
    fun start(onSamples: (List<SensorSample>) -> Unit, onStatus: (String) -> Unit)
    fun flushHeartRate() = Unit
    fun stop()
}

object AlarmPolicy {
    fun create(settings: AlarmSettings, now: Long, zone: ZoneId): SleepSession {
        val window = if (settings.mode == AlarmMode.DEADLINE) {
            val localNow = Instant.ofEpochMilli(now).atZone(zone)
            var deadline = localNow.toLocalDate().atTime(LocalTime.of(settings.hour, settings.minute)).atZone(zone)
            if (deadline.toInstant().toEpochMilli() <= now) deadline = deadline.plusDays(1)
            val end = deadline.toInstant().toEpochMilli()
            WakeWindow(maxOf(now, end - 30 * MINUTE), end, end)
        } else cycleWindow(now + 15 * MINUTE, settings.cycles)
        return SleepSession(startedAt = now, settings = settings, window = window)
    }

    fun correctOnset(session: SleepSession, onset: Long, now: Long): SleepSession {
        if (session.state != SessionState.TRACKING || session.onset != null ||
            now > session.startedAt + 30 * MINUTE || now >= session.window.start ||
            onset < session.startedAt || onset > now) return session
        val window = if (session.settings.mode == AlarmMode.CYCLES) cycleWindow(onset, session.settings.cycles) else session.window
        return session.copy(onset = onset, window = window)
    }

    fun shouldWake(session: SleepSession, estimate: SleepEstimate, now: Long): Boolean {
        val eligible = session.state == SessionState.TRACKING && now >= session.window.start && now < session.window.end &&
            estimate.quality == DataQuality.GOOD && estimate.wakeSuitable &&
            now - estimate.at in 0..60_000
        if (!eligible) return false
        if (session.settings.mode == AlarmMode.CYCLES) return true
        val cycle = 90 * MINUTE
        val elapsed = (now - (session.onset ?: (session.startedAt + 15 * MINUTE))).coerceAtLeast(0)
        val phase = elapsed % cycle
        val nearBoundary = minOf(phase, cycle - phase) <= 10 * MINUTE
        // Stronger sensor evidence wins even away from the nominal cycle boundary.
        return nearBoundary || estimate.suitableEpochs >= 4 || session.window.end - now <= 5 * MINUTE
    }

    private fun cycleWindow(onset: Long, cycles: Int): WakeWindow {
        val target = onset + cycles * 90 * MINUTE
        return WakeWindow(target - 15 * MINUTE, target, target + 15 * MINUTE)
    }
}
