package com.aestus.sleep.core

import org.junit.Assert.*
import org.junit.Test

class TrackingPowerPolicyTest {
    @Test fun reserveIncludesTwentyPercentAndStopsAtTwentyOne() {
        assertTrue(TrackingPowerPolicy.preserveAlarmBattery(0, false))
        assertTrue(TrackingPowerPolicy.preserveAlarmBattery(20, false))
        assertFalse(TrackingPowerPolicy.preserveAlarmBattery(21, false))
        assertFalse(TrackingPowerPolicy.preserveAlarmBattery(100, false))
    }

    @Test fun chargingDoesNotRequireReserveAndUnknownLevelIsNotTreatedAsEmpty() {
        assertFalse(TrackingPowerPolicy.preserveAlarmBattery(10, true))
        assertFalse(TrackingPowerPolicy.preserveAlarmBattery(-1, false))
        assertFalse(TrackingPowerPolicy.preserveAlarmBattery(101, false))
    }

    @Test fun denseSensorEventsCannotRequestMoreThanOneHeartFlushPerThirtySeconds() {
        var last = 0L
        val flushes = mutableListOf<Long>()
        for (now in 0L..120_000L step 40L) {
            if (TrackingPowerPolicy.heartRateFlushDue(last, now)) {
                flushes.add(now)
                last = now
            }
        }
        assertEquals(listOf(30_000L, 60_000L, 90_000L, 120_000L), flushes)
        assertFalse(TrackingPowerPolicy.heartRateFlushDue(30_000L, 29_000L))
    }
}
