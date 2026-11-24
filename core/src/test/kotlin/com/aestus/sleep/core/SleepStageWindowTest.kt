package com.aestus.sleep.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SleepStageWindowTest {
    @Test fun usesOrderedHeartRateAndMotionAcrossContiguousEpochs() {
        val start = 120_000L
        val aggregator = EpochAggregator()
        aggregator.add(listOf(
            SensorSample(start, heartRate = 60.0, heartRateValid = true),
            SensorSample(start + 1, heartRate = 64.0, heartRateValid = true),
            SensorSample(start + 2, accelerationMagnitudeG = 0.75),
            SensorSample(start + 3, accelerationMagnitudeG = 1.25),
        ), start + EPOCH_MS)
        val first = aggregator.drain(start + 2 * EPOCH_MS).single()
        assertEquals(0.25, requireNotNull(first.accelerationMagnitudeStdG), 0.000001)

        val window = SleepStageWindow()
        fun epoch(index: Int) = first.copy(
            start = start + index * EPOCH_MS,
            end = start + (index + 1) * EPOCH_MS,
            heartRate = 62.0 + index,
        )
        repeat(SleepStageWindow.SIZE - 1) { assertNull(window.add(epoch(it))) }
        val ready = requireNotNull(window.add(epoch(SleepStageWindow.SIZE - 1)))
        assertArrayEquals(floatArrayOf(62f, 0.25f), ready.first(), 0.000001f)
        assertArrayEquals(floatArrayOf(91f, 0.25f), ready.last(), 0.000001f)
        assertNotNull(window.add(epoch(SleepStageWindow.SIZE)))
        assertNull(window.add(epoch(SleepStageWindow.SIZE + 2)))
        repeat(SleepStageWindow.SIZE - 2) { assertNull(window.add(epoch(SleepStageWindow.SIZE + 3 + it))) }
        val offBody = epoch(2 * SleepStageWindow.SIZE + 1).copy(quality = DataQuality.OFF_BODY)
        assertNull(window.add(offBody))
        assertNull(window.add(epoch(2 * SleepStageWindow.SIZE + 2)))
    }
}
