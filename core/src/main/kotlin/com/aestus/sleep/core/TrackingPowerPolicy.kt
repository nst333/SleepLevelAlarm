package com.aestus.sleep.core

object TrackingPowerPolicy {
    const val ALARM_RESERVE_PERCENT = 20
    const val IDLE_PROCESSING_MS = 30_000L

    fun preserveAlarmBattery(level: Int, charging: Boolean): Boolean =
        !charging && level in 0..ALARM_RESERVE_PERCENT

    fun heartRateFlushDue(lastFlushAt: Long, now: Long): Boolean =
        now - lastFlushAt >= IDLE_PROCESSING_MS
}
