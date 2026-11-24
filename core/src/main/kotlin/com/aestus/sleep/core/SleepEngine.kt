package com.aestus.sleep.core

import kotlin.math.sqrt

/** Buffered epochs permit cross-sensor batches to arrive out of order before the watermark. */
class EpochAggregator {
    private val pending = sortedMapOf<Long, MutableList<SensorSample>>()
    private var closedBefore = Long.MIN_VALUE

    fun add(samples: List<SensorSample>, now: Long) {
        samples.filter { it.at >= closedBefore && it.at <= now + 2_000 && it.at >= now - 10 * MINUTE }
            .forEach { sample ->
                val start = sample.at / EPOCH_MS * EPOCH_MS
                pending.getOrPut(start) { mutableListOf() }.add(sample)
            }
    }

    fun drain(now: Long): List<EpochFeatures> {
        val watermark = (now - 30_000) / EPOCH_MS * EPOCH_MS
        val ready = pending.keys.filter { it + EPOCH_MS <= watermark }
        val result = ready.map { start -> features(start, pending.remove(start)!!.distinct().sortedBy { it.at }) }
        closedBefore = maxOf(closedBefore, watermark)
        return result
    }

    private fun features(start: Long, samples: List<SensorSample>): EpochFeatures {
        val hrs = samples.filter { it.heartRateValid }.mapNotNull { it.heartRate }.filter { it in 25.0..240.0 }
        val motion = samples.mapNotNull { it.accelerationG }.filter { it.isFinite() && it >= 0 }
        val rawMotion = samples.mapNotNull { it.accelerationMagnitudeG }.filter { it.isFinite() && it >= 0 }
        val accelerationMagnitudeStdG = rawMotion.takeIf { it.size >= 2 }?.let { values ->
            val mean = values.average()
            sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        }
        val ibi = samples.filter { it.heartRateValid }.flatMap { it.ibiMs }.filter { it in 250..2400 }
        val rmssd = if (ibi.size >= 10) sqrt(ibi.zipWithNext { a, b -> (b - a).toDouble().let { it * it } }.average()) else null
        val quality = when {
            samples.any { it.offBody } -> DataQuality.OFF_BODY
            hrs.size < 15 || motion.size < 375 || ibi.size < 10 -> DataQuality.INSUFFICIENT
            else -> DataQuality.GOOD
        }
        return EpochFeatures(start, start + EPOCH_MS, hrs.takeIf { it.isNotEmpty() }?.average(), rmssd,
            motion.takeIf { it.size >= 2 }?.let { values -> val mean = values.average()
            sqrt(
               values.sumOf { v -> (v-mean) * (v-mean) } / values.size
             )}, quality, hrs.size, motion.size,
            accelerationMagnitudeStdG)
    }
}

/** Experimental wake-suitability rules; this does not classify light/deep/REM sleep. */
class SleepEngine {
    private val baseline = mutableListOf<Double>()
    private val recent = ArrayDeque<EpochFeatures>()
    private var onset: Long? = null

    fun update(epoch: EpochFeatures): SleepEstimate {
        if (recent.lastOrNull()?.end?.let { epoch.start != it } == true || epoch.quality != DataQuality.GOOD) recent.clear()
        if (epoch.quality != DataQuality.GOOD) return SleepEstimate(epoch.end, SleepState.UNKNOWN, epoch.quality)
        if (baseline.size < 10) baseline.add(epoch.heartRate!!)
        recent.addLast(epoch)
        while (recent.size > 10) recent.removeFirst()
        val restingHr = baseline.takeIf { it.size == 10 }?.sorted()?.get(5)
        // shortcut: provisional thresholds lack PSG calibration; replace after labeled hardware validation.
        val asleep = restingHr != null && recent.size == 10 &&
            recent.all {
                it.motionRmsG!! < 0.005 &&
              it.heartRate!! <= restingHr * 1.05 }
        if (asleep && onset == null) onset = recent.first().start
        val suitableEpochs = if (restingHr != null && onset != null) recent.reversed().takeWhile {
            it.motionRmsG!! in 0.015..0.15 && it.heartRate!! >= restingHr * 1.05
        }.size else 0
        val waking = suitableEpochs >= 4
        return SleepEstimate(epoch.end,
            if (asleep) SleepState.ASLEEP_CANDIDATE else SleepState.AWAKE,
            DataQuality.GOOD, waking, onset, restingHr, suitableEpochs = suitableEpochs)
    }
}

/** Same path as live collection, used by JVM replay tests and exported raw recordings. */
class ReplaySensorSource(private val batches: List<List<SensorSample>>) : SensorSource {
    override fun start(onSamples: (List<SensorSample>) -> Unit, onStatus: (String) -> Unit) {
        onStatus("replay")
        batches.forEach(onSamples)
    }
    override fun stop() = Unit
}

// shortcut: per-beat times are reconstructed from batch end; use native beat timestamps if the SDK adds them.
fun distributeBeats(intervals: List<Pair<Int, Boolean>>, lastAt: Long): List<SensorSample> {
    var at = lastAt
    return intervals.asReversed().mapNotNull { (interval, valid) ->
        val beatAt = at
        at -= interval.takeIf { it in 250..2400 } ?: 1000
        if (valid && interval in 250..2400) SensorSample(beatAt, ibiMs = listOf(interval), heartRateValid = true) else null
    }.asReversed()
}
