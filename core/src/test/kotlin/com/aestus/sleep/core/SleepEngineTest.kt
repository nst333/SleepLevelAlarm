package com.aestus.sleep.core

import org.junit.Assert.*
import org.junit.Test

class SleepEngineTest {
    private fun samples(epoch: Int, hr: Double = 70.0, motion: Double = 0.005): List<SensorSample> {
        val start = epoch * EPOCH_MS
        return (0 until 30).map { i -> SensorSample(start + i * 1000, hr, listOf(850, 860), heartRateValid = true) } +
            (0 until 750).map { i -> SensorSample(start + i * 40, accelerationG = motion) }
    }
    private fun epoch(i: Int, hr: Double = 70.0, motion: Double = 0.005) =
        EpochFeatures(i * EPOCH_MS, (i + 1) * EPOCH_MS, hr, 30.0, motion, DataQuality.GOOD, 30, 750)

    @Test fun aggregatorSortsBatchesAndComputesFeatures() {
        val aggregator = EpochAggregator()
        val data = samples(0)
        aggregator.add(data.reversed(), 30_000)
        assertTrue(aggregator.drain(50_000).isEmpty())
        val f = aggregator.drain(60_000).single()
        assertEquals(DataQuality.GOOD, f.quality)
        assertEquals(70.0, f.heartRate!!, 0.001)
        assertEquals(0.005, f.motionRmsG!!, 0.00001)
        assertEquals(10.0, f.rmssdMs!!, 0.0001)
        assertEquals(30, f.hrCount)
        assertEquals(750, f.motionCount)
    }
    @Test fun delayedAndDuplicateInputCannotRewriteClosedEpoch() {
        val a = EpochAggregator()
        a.add(samples(0) + samples(0), 30_000)
        assertEquals(30, a.drain(60_000).single().hrCount)
        a.add(samples(0), 60_000)
        assertTrue(a.drain(90_000).isEmpty())
    }
    @Test fun offBodyAndMissingSensorCannotBeGood() {
        val a = EpochAggregator()
        a.add(samples(0) + SensorSample(10_000, offBody = true), 30_000)
        assertEquals(DataQuality.OFF_BODY, a.drain(60_000).single().quality)
        val b = EpochAggregator()
        b.add(samples(0).filter { it.accelerationG == null }, 30_000)
        assertEquals(DataQuality.INSUFFICIENT, b.drain(60_000).single().quality)
    }
    @Test fun invalidHeartRateAndIbiAreExcluded() {
        val a = EpochAggregator()
        a.add(samples(0).map { if (it.heartRate != null) it.copy(heartRateValid = false) else it }, 30_000)
        val f = a.drain(60_000).single()
        assertNull(f.heartRate)
        assertNull(f.rmssdMs)
        assertEquals(DataQuality.INSUFFICIENT, f.quality)
    }
    @Test fun stableSleepThenTwoWakingEpochsCanBeSuitable() {
        val engine = SleepEngine()
        repeat(10) { engine.update(epoch(it)) }
        var estimate: SleepEstimate? = null
        repeat(10) { estimate = engine.update(epoch(10 + it, hr = 60.0)) }
        assertEquals(SleepState.ASLEEP_CANDIDATE, estimate!!.state)
        assertEquals(10 * EPOCH_MS, estimate!!.onsetCandidate)
        assertFalse(engine.update(epoch(20, motion = 0.04)).wakeSuitable)
        assertTrue(engine.update(epoch(21, motion = 0.04)).wakeSuitable)
    }
    @Test fun missingEpochOrOffBodyResetsConsecutiveEvidence() {
        val engine = SleepEngine()
        repeat(10) { engine.update(epoch(it)) }
        repeat(9) { engine.update(epoch(it + 10, 60.0)) }
        assertNull(engine.update(epoch(20, 60.0)).onsetCandidate)
        assertEquals(DataQuality.OFF_BODY, engine.update(epoch(21).copy(quality = DataQuality.OFF_BODY)).quality)
        assertFalse(engine.update(epoch(22, motion = 0.04)).wakeSuitable)
    }
    @Test fun violentMovementAndElapsedCyclesAreNotWakeEvidence() {
        val engine = SleepEngine()
        repeat(10) { engine.update(epoch(it)) }
        repeat(10) { engine.update(epoch(it + 10, 60.0)) }
        assertFalse(engine.update(epoch(20, motion = 0.5)).wakeSuitable)
        assertFalse(engine.update(epoch(21, motion = 0.5)).wakeSuitable)
        assertFalse(engine.update(epoch(180, 60.0)).wakeSuitable)
    }
    @Test fun replayGoesThroughTheLiveAggregatorAndEngine() {
        val a = EpochAggregator()
        val engine = SleepEngine()
        val estimates = mutableListOf<SleepEstimate>()
        var now = 30_000L
        ReplaySensorSource((0 until 22).map { i -> samples(i, if (i in 10..19) 60.0 else 70.0, if (i >= 20) 0.04 else 0.005) })
            .start({ batch ->
                a.add(batch, now)
                a.drain(now).mapTo(estimates, engine::update)
                now += EPOCH_MS
            }, {})
        a.drain(now + MINUTE).mapTo(estimates, engine::update)
        assertEquals(22, estimates.size)
        assertTrue(estimates.last().wakeSuitable)
        assertTrue(estimates.any { it.state == SleepState.ASLEEP_CANDIDATE })
    }
    @Test fun batchedIbiUsesMeasurementTimesAndPreservesInvalidGaps() {
        val beats = distributeBeats(listOf(1000 to true, 1000 to false, 1000 to true), 30_000)
        assertEquals(listOf(28_000L, 30_000L), beats.map { it.at })
        assertEquals(listOf(1000), beats.first().ibiMs)
        assertTrue(distributeBeats(listOf(0 to true, 3000 to true), 30_000).isEmpty())
    }
    @Test fun futureAndVeryOldSamplesAreDiscarded() {
        val a = EpochAggregator()
        a.add(samples(0), 30 * EPOCH_MS)
        assertTrue(a.drain(30 * EPOCH_MS).isEmpty())
        a.add(samples(33), 30 * EPOCH_MS)
        assertTrue(a.drain(34 * EPOCH_MS).isEmpty())
    }
}
