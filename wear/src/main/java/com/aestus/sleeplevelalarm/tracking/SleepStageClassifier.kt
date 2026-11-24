package com.aestus.sleeplevelalarm.tracking

import android.content.Context
import com.aestus.sleep.core.SleepStage
import com.aestus.sleep.core.SleepStageWindow
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class SleepStageClassifier(context: Context) : AutoCloseable {
    private val interpreter: Interpreter

    init {
        val bytes = context.assets.open("sleep_stage.tflite").use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes).rewind()
        val model = Interpreter(buffer, Interpreter.Options().setNumThreads(1))
        try {
            require(model.getInputTensor(0).let {
                it.dataType() == DataType.FLOAT32 && it.shape().contentEquals(intArrayOf(1, SleepStageWindow.SIZE, 2))
            })
            require(model.getOutputTensor(0).let {
                it.dataType() == DataType.FLOAT32 && it.shape().contentEquals(intArrayOf(1, SleepStage.entries.size))
            })
        } catch (e: Exception) {
            model.close()
            throw e
        }
        interpreter = model
    }

    fun predict(window: Array<FloatArray>): SleepStage? {
        val scores = FloatArray(SleepStage.entries.size)
        interpreter.run(arrayOf(window), arrayOf(scores))
        if (!scores.all { it.isFinite() }) return null
        return SleepStage.entries[scores.indices.maxBy { scores[it] }]
    }

    override fun close() = interpreter.close()
}
