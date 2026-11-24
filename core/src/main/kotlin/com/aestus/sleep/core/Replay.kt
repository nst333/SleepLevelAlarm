package com.aestus.sleep.core

import java.io.File
import java.util.zip.GZIPInputStream

data class RecordedSample(val receivedAt: Long, val sample: SensorSample)

fun parseRecordingLine(line: String): RecordedSample {
    val c = line.split(',')
    require(c.size == 7) { "Expected seven recording columns" }
    val at = c[0].toLong().also { require(it >= 0) }
    val received = c[1].toLong().also { require(it >= 0) }
    val hr = c[2].takeIf { it.isNotEmpty() }?.toDouble()?.also { require(it.isFinite()) }
    val ibi = c[3].takeIf { it.isNotEmpty() }?.split(';')?.map { it.toInt().also { value -> require(value in 250..2400) } } ?: emptyList()
    val acceleration = c[4].takeIf { it.isNotEmpty() }?.toDouble()?.also { require(it.isFinite() && it >= 0) }
    return RecordedSample(received, SensorSample(at, hr, ibi, acceleration, c[5].toBooleanStrict(), c[6].toBooleanStrict()))
}

fun main(args: Array<String>) {
    require(args.size == 1) { "Usage: :core:run --args=<recording.csv or recording.csv.gz>" }
    val file = File(args[0])
    val aggregator = EpochAggregator()
    val engine = SleepEngine()
    var receivedAt = -1L
    val batch = mutableListOf<SensorSample>()
    fun drain(now: Long) {
        aggregator.drain(now).forEach { f ->
            val estimate = engine.update(f)
            val quality = if (now - estimate.at > MINUTE) DataQuality.STALE else estimate.quality
            println("${f.end},${estimate.state},$quality,${estimate.wakeSuitable && quality == DataQuality.GOOD},${f.heartRate ?: ""},${f.motionRmsG ?: ""},${f.rmssdMs ?: ""}")
        }
    }
    fun flush() {
        if (batch.isEmpty()) return
        aggregator.add(batch, receivedAt)
        drain(receivedAt)
        batch.clear()
    }
    println("at,state,quality,wakeSuitable,heartRate,motionRmsG,rmssdMs")
    val input = file.inputStream().let { if (file.extension == "gz") GZIPInputStream(it) else it }
    input.bufferedReader().useLines { lines ->
        lines.forEachIndexed { index, line ->
            if (index == 0) require(line == "at,receivedAt,heartRate,ibiMs,linearAccelerationG,offBody,heartRateValid") { "Unknown recording header" }
            else {
                val row = try { parseRecordingLine(line) } catch (e: Exception) { throw IllegalArgumentException("Invalid recording line ${index + 1}", e) }
                require(row.receivedAt >= receivedAt) { "Delivery timestamps moved backwards at line ${index + 1}" }
                if (row.receivedAt != receivedAt) flush()
                receivedAt = row.receivedAt
                batch.add(row.sample)
            }
        }
    }
    flush()
    if (receivedAt >= 0) drain(receivedAt + MINUTE)
}
