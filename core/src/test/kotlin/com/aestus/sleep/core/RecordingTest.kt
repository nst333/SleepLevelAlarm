package com.aestus.sleep.core

import org.junit.Assert.*
import org.junit.Test

class RecordingTest {
    @Test fun parsesRecordingWithDeliveryTimeAndOptionalFields() {
        val row = parseRecordingLine("1000,2000,70.0,850;860,,false,true")
        assertEquals(2000L, row.receivedAt)
        assertEquals(listOf(850, 860), row.sample.ibiMs)
        assertNull(row.sample.accelerationG)
        assertEquals(0.01, parseRecordingLine("1000,2000,,,0.01,false,false").sample.accelerationG!!, 0.00001)
    }
    @Test fun rejectsMalformedOrNonfiniteRecordingValues() {
        listOf("bad", "-1,2,70,,,false,true", "1,2,NaN,,,false,true",
            "1,2,,0,,false,true", "1,2,,,Infinity,false,false", "1,2,,,0.01,maybe,false").forEach {
            try { parseRecordingLine(it); fail("must reject $it") } catch (_: IllegalArgumentException) { }
        }
    }
}
