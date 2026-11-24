package com.aestus.sleeplevelalarm.data

import android.content.Context
import com.aestus.sleep.core.SensorSample
import com.aestus.sleep.core.TrackingPowerPolicy
import java.io.BufferedWriter
import java.io.File
import java.util.zip.GZIPOutputStream

class RawRecorder(context: Context, sessionId: String, startedAt: Long) : AutoCloseable {
    private val directory = File(context.filesDir, "recordings").apply { mkdirs() }
    // A killed process can leave unfinished gzip; keep each resumed segment separate.
    private val file = File(directory, "$startedAt-$sessionId.csv.gz").let {
        if (it.exists()) File.createTempFile("$startedAt-$sessionId-", ".csv.gz", directory) else it
    }
    private val writer: BufferedWriter
    private var lastFlushAt: Long? = null
    init {
        writer = GZIPOutputStream(file.outputStream(), 8192, true).bufferedWriter()
        writer.appendLine("at,receivedAt,heartRate,ibiMs,linearAccelerationG,offBody,heartRateValid")
    }
    fun append(samples: List<SensorSample>, receivedAt: Long) {
        samples.forEach { s -> writer.appendLine("${s.at},$receivedAt,${s.heartRate ?: ""},${s.ibiMs.joinToString(";")},${s.accelerationG ?: ""},${s.offBody},${s.heartRateValid}") }
        val last = lastFlushAt
        if (last == null) lastFlushAt = receivedAt
        else if (receivedAt - last >= TrackingPowerPolicy.IDLE_PROCESSING_MS) {
            writer.flush()
            lastFlushAt = receivedAt
        }
    }
    override fun close() = writer.close()
}
