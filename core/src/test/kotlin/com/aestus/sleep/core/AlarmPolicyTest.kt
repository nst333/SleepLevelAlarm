package com.aestus.sleep.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class AlarmPolicyTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(value: String) = Instant.parse(value).toEpochMilli()

    @Test fun deadlineCrossesMidnight() {
        val session = AlarmPolicy.create(AlarmSettings(), at("2026-11-21T14:00:00Z"), zone)
        assertEquals(at("2026-11-21T22:00:00Z"), session.window.end)
        assertEquals(at("2026-11-21T21:30:00Z"), session.window.start)
    }
    @Test fun passedDeadlineMeansTomorrow() {
        val now = at("2026-11-21T22:00:00Z")
        assertEquals(now + 24 * 60 * MINUTE, AlarmPolicy.create(AlarmSettings(), now, zone).window.end)
    }
    @Test fun closeDeadlineClampsWindowToStart() {
        val now = at("2026-11-21T21:55:00Z")
        assertEquals(now, AlarmPolicy.create(AlarmSettings(), now, zone).window.start)
    }
    @Test fun cyclesUseTemporaryOnsetAndBothSidesOfWindow() {
        val s = AlarmPolicy.create(AlarmSettings(mode = AlarmMode.CYCLES, cycles = 1), 0, zone)
        assertEquals(90 * MINUTE, s.window.start)
        assertEquals(105 * MINUTE, s.window.target)
        assertEquals(120 * MINUTE, s.window.end)
    }
    @Test fun onsetCorrectsOnlyOnceAndOnlyWithinThirtyMinutes() {
        val original = AlarmPolicy.create(AlarmSettings(mode = AlarmMode.CYCLES, cycles = 2), 0, zone)
        val corrected = AlarmPolicy.correctOnset(original, 10 * MINUTE, 20 * MINUTE)
        assertEquals(190 * MINUTE, corrected.window.target)
        assertEquals(corrected, AlarmPolicy.correctOnset(corrected, 12 * MINUTE, 25 * MINUTE))
        assertEquals(original, AlarmPolicy.correctOnset(original, 20 * MINUTE, 31 * MINUTE))
        assertEquals(original, AlarmPolicy.correctOnset(original, -1, 20 * MINUTE))
        assertEquals(original, AlarmPolicy.correctOnset(original, 21 * MINUTE, 20 * MINUTE))
    }
    @Test fun deadlineOnsetDoesNotChangeAlarm() {
        val s = AlarmPolicy.create(AlarmSettings(), at("2026-11-21T14:00:00Z"), zone)
        assertEquals(s.window, AlarmPolicy.correctOnset(s, s.startedAt + MINUTE, s.startedAt + 10 * MINUTE).window)
    }
    @Test fun smartWakeRequiresFreshGoodDataInsideWindowAndActiveSession() {
        val s = AlarmPolicy.create(AlarmSettings(mode = AlarmMode.CYCLES, cycles = 1), 0, zone)
        val now = s.window.start
        val estimate = SleepEstimate(now, SleepState.AWAKE, DataQuality.GOOD, true)
        assertTrue(AlarmPolicy.shouldWake(s, estimate, now))
        assertFalse(AlarmPolicy.shouldWake(s, estimate, now - 1))
        assertFalse(AlarmPolicy.shouldWake(s, estimate.copy(at = s.window.end), s.window.end))
        assertFalse(AlarmPolicy.shouldWake(s, estimate.copy(at = now - MINUTE - 1), now))
        assertFalse(AlarmPolicy.shouldWake(s, estimate.copy(at = now + 1), now))
        DataQuality.entries.filter { it != DataQuality.GOOD }.forEach {
            assertFalse(AlarmPolicy.shouldWake(s, estimate.copy(quality = it), now))
        }
        assertFalse(AlarmPolicy.shouldWake(s.copy(state = SessionState.RINGING), estimate, now))
        assertFalse(AlarmPolicy.shouldWake(s.copy(state = SessionState.CANCELLED), estimate, now))
    }
    @Test fun deadlineUsesLocalDateAcrossDaylightSaving() {
        val now = at("2026-03-07T13:00:00Z")
        val s = AlarmPolicy.create(AlarmSettings(), now, ZoneId.of("America/New_York"))
        assertEquals(at("2026-03-08T11:00:00Z"), s.window.end)
    }
    @Test fun invalidSettingsAreRejected() {
        listOf<() -> Unit>({ AlarmSettings(cycles = 0) }, { AlarmSettings(cycles = 7) },
            { AlarmSettings(hour = 24) }, { AlarmSettings(minute = -1) }).forEach {
            try { it(); fail("must reject invalid settings") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun deadlineUsesCycleAsSecondaryEvidenceAndStrongEvidenceCanOverride() {
        val start = 0L
        val target = 140 * MINUTE
        val session = SleepSession(startedAt = start, settings = AlarmSettings(), window = WakeWindow(110 * MINUTE, target, target), onset = 0)
        val now = 120 * MINUTE
        val weak = SleepEstimate(now, SleepState.AWAKE, DataQuality.GOOD, true, suitableEpochs = 2)
        assertFalse(AlarmPolicy.shouldWake(session, weak, now))
        assertTrue(AlarmPolicy.shouldWake(session, weak.copy(suitableEpochs = 4), now))
        assertTrue(AlarmPolicy.shouldWake(session, weak.copy(at = 136 * MINUTE), 136 * MINUTE))
        val boundary = session.copy(onset = 30 * MINUTE)
        assertTrue(AlarmPolicy.shouldWake(boundary, weak, now))
    }
}
