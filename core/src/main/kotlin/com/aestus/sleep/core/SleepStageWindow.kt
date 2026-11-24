package com.aestus.sleep.core

class SleepStageWindow {
    private val rows = ArrayDeque<FloatArray>()
    private var lastEnd: Long? = null

    fun add(epoch: EpochFeatures): Array<FloatArray>? {
        if (epoch.start != lastEnd) reset()
        val heartRate = epoch.heartRate
        val motion = epoch.accelerationMagnitudeStdG
        if (epoch.quality == DataQuality.OFF_BODY || heartRate == null || motion == null) {
            reset()
            return null
        }
        lastEnd = epoch.end
        if (rows.size == SIZE) rows.removeFirst()
        rows.addLast(floatArrayOf(heartRate.toFloat(), motion.toFloat()))
        return if (rows.size == SIZE) rows.toTypedArray() else null
    }

    fun reset() {
        rows.clear()
        lastEnd = null
    }

    companion object { const val SIZE = 30 }
}
