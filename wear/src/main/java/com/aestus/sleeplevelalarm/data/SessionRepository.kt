package com.aestus.sleeplevelalarm.data

import android.content.Context
import android.os.BatteryManager
import com.aestus.sleep.core.*
import com.aestus.sleeplevelalarm.alarm.WatchAlarmScheduler
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.ZoneId

class SessionRepository(
    private val context: Context,
    private val db: SleepDatabase,
    private val scheduler: WatchAlarmScheduler,
) {
    private val mutex = Mutex()
    private val dao get() = db.dao()
    val sessions = dao.observe().map { rows -> rows.map { it.domain() } }
    fun observeSession(id: String) = dao.observeSession(id).map { it?.domain() }
    suspend fun active() = mutex.withLock { dao.active()?.domain() }
    suspend fun get(id: String) = mutex.withLock { dao.session(id)?.domain() }

    suspend fun start(settings: AlarmSettings, now: Long): SleepSession = mutex.withLock {
        check(dao.active() == null) { "이미 진행 중인 알람이 있습니다." }
        check(scheduler.canSchedule()) { "정확한 알람 권한을 허용해 주세요." }
        prune(now)
        val session = AlarmPolicy.create(settings, now, ZoneId.systemDefault()).copy(batteryStart = battery())
        dao.save(session.record())
        try { scheduler.schedule(session) } catch (e: Exception) {
            dao.save(session.copy(state = SessionState.FAILED, endedAt = now, note = "알람 예약 실패: ${e.javaClass.simpleName}").record())
            throw e
        }
        session
    }

    suspend fun correctOnset(id: String, onset: Long, now: Long): SleepSession? = mutex.withLock {
        val old = dao.session(id)?.domain() ?: return@withLock null
        val updated = AlarmPolicy.correctOnset(old, onset, now)
        if (old != updated) {
            // Reusing the same PendingIntent replaces the final alarm without a cancellation gap.
            scheduler.schedule(updated)
            try { dao.save(updated.record()) } catch (e: Exception) { scheduler.schedule(old); throw e }
        }
        updated
    }

    suspend fun claimAlarm(id: String, reason: AlarmReason, now: Long): SleepSession? = mutex.withLock {
        val old = dao.session(id)?.domain() ?: return@withLock null
        if (old.state != SessionState.TRACKING) return@withLock null
        if (reason == AlarmReason.SMART && now !in old.window.start until old.window.end) return@withLock null
        if (reason != AlarmReason.SMART && now < old.window.end) {
            scheduler.schedule(old)
            return@withLock null
        }
        if (reason != AlarmReason.SMART && now - old.window.end > 5 * MINUTE) {
            dao.save(old.copy(state = SessionState.COMPLETED, endedAt = now,
                note = "알람 시각이 5분 이상 경과해 미실행", batteryEnd = battery()).record())
            scheduler.cancel(id)
            return@withLock null
        }
        val updated = old.copy(state = SessionState.RINGING, firedAt = now, reason = reason)
        dao.save(updated.record())
        scheduler.cancel(id)
        updated
    }

    suspend fun finish(id: String, cancelled: Boolean, note: String, now: Long) = mutex.withLock {
        val old = dao.session(id)?.domain() ?: return@withLock
        if (old.state !in listOf(SessionState.TRACKING, SessionState.RINGING)) return@withLock
        dao.save(old.copy(state = if (cancelled) SessionState.CANCELLED else SessionState.COMPLETED,
            endedAt = now, note = listOf(old.note, note).filter { it.isNotBlank() }.distinct().joinToString(" · "), batteryEnd = battery()).record())
        scheduler.cancel(id)
    }

    suspend fun note(id: String, message: String) = mutex.withLock {
        val old = dao.session(id)?.domain() ?: return@withLock
        dao.save(old.copy(note = message).record())
    }

    suspend fun record(id: String, features: EpochFeatures, estimate: SleepEstimate, now: Long) {
        dao.estimate(EstimateRecord(sessionId = id, at = estimate.at, state = estimate.state.name,
            quality = estimate.quality.name, wakeSuitable = estimate.wakeSuitable, baselineHr = estimate.baselineHr,
            heartRate = features.heartRate, motionRmsG = features.motionRmsG, rmssdMs = features.rmssdMs,
            hrCount = features.hrCount, motionCount = features.motionCount, deliveryLagMs = now - estimate.at,
            algorithmVersion = estimate.algorithmVersion))
    }

    suspend fun restore(now: Long): SleepSession? = mutex.withLock {
        val old = dao.active()?.domain()
        if (old?.state == SessionState.TRACKING && old.window.end > now) scheduler.schedule(old)
        runCatching { prune(now) }.onFailure { android.util.Log.w("SleepLevelAlarm", "Retention cleanup failed", it) }
        old
    }

    suspend fun prune(now: Long) {
        dao.deleteEstimates(now - 7 * 24 * 60 * MINUTE)
        dao.deleteSessions(now - 30 * 24 * 60 * MINUTE)
        File(context.filesDir, "recordings").listFiles()?.forEach {
            val started = it.name.substringBefore('-').toLongOrNull()
            if (started != null && started < now - 7 * 24 * 60 * MINUTE) it.delete()
        }
    }

    private fun battery() = context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
}
