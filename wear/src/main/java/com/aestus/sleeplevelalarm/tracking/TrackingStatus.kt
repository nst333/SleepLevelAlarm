package com.aestus.sleeplevelalarm.tracking

import com.aestus.sleep.core.SleepEstimate
import com.aestus.sleep.core.SleepStage
import kotlinx.coroutines.flow.MutableStateFlow

data class TrackingStatus(
    val running: Boolean = false,
    val message: String = "추적 중지 · 일반 알람 유지",
    val estimate: SleepEstimate? = null,
    val heartRate: Double? = null,
    val lastDataAt: Long? = null,
    val sleepStage: SleepStage? = null,
)

object TrackingState { val status = MutableStateFlow(TrackingStatus()) }
