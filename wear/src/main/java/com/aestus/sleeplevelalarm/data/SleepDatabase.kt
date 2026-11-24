package com.aestus.sleeplevelalarm.data

import androidx.room.*
import com.aestus.sleep.core.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sessions")
data class SessionRecord(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val mode: String,
    val hour: Int,
    val minute: Int,
    val cycles: Int,
    val sound: Boolean,
    val windowStart: Long,
    val target: Long,
    val windowEnd: Long,
    val onset: Long?,
    val state: String,
    val firedAt: Long?,
    val reason: String?,
    val endedAt: Long?,
    val note: String,
    val algorithmVersion: String,
    val batteryStart: Int,
    val batteryEnd: Int,
) {
    fun domain() = SleepSession(id, startedAt, AlarmSettings(AlarmMode.valueOf(mode), hour, minute, cycles, sound),
        WakeWindow(windowStart, target, windowEnd), onset, SessionState.valueOf(state), firedAt,
        reason?.let(AlarmReason::valueOf), endedAt, note, algorithmVersion, batteryStart, batteryEnd)
}

fun SleepSession.record() = SessionRecord(id, startedAt, settings.mode.name, settings.hour, settings.minute,
    settings.cycles, settings.sound, window.start, window.target, window.end, onset, state.name,
    firedAt, reason?.name, endedAt, note, algorithmVersion, batteryStart, batteryEnd)

@Entity(tableName = "estimates", indices = [Index("sessionId"), Index("at")])
data class EstimateRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val at: Long,
    val state: String,
    val quality: String,
    val wakeSuitable: Boolean,
    val baselineHr: Double?,
    val heartRate: Double?,
    val motionRmsG: Double?,
    val rmssdMs: Double?,
    val hrCount: Int,
    val motionCount: Int,
    val deliveryLagMs: Long,
    val algorithmVersion: String,
)

@Dao
interface SleepDao {
    @Query("SELECT * FROM sessions WHERE state IN ('TRACKING','RINGING') ORDER BY startedAt DESC LIMIT 1")
    suspend fun active(): SessionRecord?
    @Query("SELECT * FROM sessions WHERE id=:id") suspend fun session(id: String): SessionRecord?
    @Query("SELECT * FROM sessions WHERE id=:id") fun observeSession(id: String): Flow<SessionRecord?>
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT 60") fun observe(): Flow<List<SessionRecord>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(session: SessionRecord)
    @Insert suspend fun estimate(estimate: EstimateRecord)
    @Query("DELETE FROM estimates WHERE at < :cutoff") suspend fun deleteEstimates(cutoff: Long)
    @Query("DELETE FROM sessions WHERE startedAt < :cutoff AND state NOT IN ('TRACKING','RINGING')")
    suspend fun deleteSessions(cutoff: Long)
}

@Database(entities = [SessionRecord::class, EstimateRecord::class], version = 1, exportSchema = true)
abstract class SleepDatabase : RoomDatabase() { abstract fun dao(): SleepDao }
