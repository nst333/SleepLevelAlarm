package com.aestus.sleeplevelalarm

import android.app.AlarmManager
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aestus.sleep.core.*
import com.aestus.sleeplevelalarm.alarm.RingingService
import com.aestus.sleeplevelalarm.data.*
import com.aestus.sleeplevelalarm.tracking.SleepTrackingService
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.GZIPInputStream

@RunWith(AndroidJUnit4::class)
class WatchIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as SleepApplication

    @Test fun finalAlarmFiresWithoutSensorsAndDuplicateDeliveryDoesNotRingTwice() = runBlocking {
        assumeTrue("Run on an idle test watch", app.repository.active() == null)
        val now = System.currentTimeMillis()
        val session = AlarmPolicy.create(AlarmSettings(sound = false), now, java.time.ZoneId.systemDefault())
            .copy(window = WakeWindow(now, now + 3000, now + 3000))
        try {
            app.database.dao().save(session.record())
            app.scheduler.schedule(session)
            withTimeout(15_000) {
                while (app.repository.get(session.id)?.state != SessionState.RINGING) delay(100)
            }
            val fired = app.repository.get(session.id)!!
            assertEquals(AlarmReason.FINAL, fired.reason)
            assertNotNull(fired.firedAt)
            RingingService.start(context, session.id, AlarmReason.FINAL)
            delay(300)
            assertNull(app.repository.claimAlarm(session.id, AlarmReason.FINAL, System.currentTimeMillis()))
            assertEquals(fired.firedAt, app.repository.get(session.id)!!.firedAt)
            context.startService(Intent(context, RingingService::class.java).setAction(RingingService.STOP).putExtra("sessionId", session.id))
            withTimeout(5000) { while (app.repository.get(session.id)?.state != SessionState.COMPLETED) delay(100) }
        } finally {
            context.stopService(Intent(context, RingingService::class.java))
            app.repository.finish(session.id, true, "test cleanup", System.currentTimeMillis())
        }
    }

    @Test fun smartAlarmCanStartFromBackgroundWithoutCancellingFinalBeforeClaim() = runBlocking {
        assumeTrue(app.repository.active() == null)
        val now = System.currentTimeMillis()
        val session = AlarmPolicy.create(AlarmSettings(sound = false), now, java.time.ZoneId.systemDefault())
            .copy(window = WakeWindow(now, now + 10 * MINUTE, now + 10 * MINUTE))
        try {
            app.database.dao().save(session.record())
            app.scheduler.schedule(session)
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").use { }
            app.scheduler.scheduleWakeNow(session, AlarmReason.SMART, System.currentTimeMillis())
            withTimeout(15_000) { while (app.repository.get(session.id)?.state != SessionState.RINGING) delay(100) }
            assertEquals(AlarmReason.SMART, app.repository.get(session.id)!!.reason)
            context.startService(Intent(context, RingingService::class.java).setAction(RingingService.STOP).putExtra("sessionId", session.id))
            withTimeout(5000) { while (app.repository.get(session.id)?.state != SessionState.COMPLETED) delay(100) }
        } finally {
            context.stopService(Intent(context, RingingService::class.java))
            app.repository.finish(session.id, true, "test cleanup", System.currentTimeMillis())
        }
    }

    @Test fun restoreReinstatesFinalAlarmAndCancelledSessionCannotFire() = runBlocking {
        assumeTrue(app.repository.active() == null)
        val session = app.repository.start(AlarmSettings(mode = AlarmMode.CYCLES, cycles = 1, sound = false), System.currentTimeMillis())
        try {
            app.scheduler.cancel(session.id)
            assertEquals(session.id, app.repository.restore(System.currentTimeMillis())!!.id)
            assertEquals(session.window.end, context.getSystemService(AlarmManager::class.java).nextAlarmClock!!.triggerTime)
            app.repository.finish(session.id, true, "cancelled", System.currentTimeMillis())
            assertNull(app.repository.claimAlarm(session.id, AlarmReason.FINAL, session.window.end))
            assertNull(app.repository.active())
        } finally { app.repository.finish(session.id, true, "test cleanup", System.currentTimeMillis()) }
    }

    @Test fun resumingRawRecordingKeepsOldSegmentUntouched() {
        val id = "recording-test-${System.nanoTime()}"
        val now = System.currentTimeMillis()
        val file = File(context.filesDir, "recordings/$now-$id.csv.gz")
        try {
            RawRecorder(context, id, now).use { it.append(listOf(SensorSample(now, 70.0, heartRateValid = true)), now) }
            val first = file.readBytes()
            RawRecorder(context, id, now).use { it.append(listOf(SensorSample(now + 1000, accelerationG = 0.01)), now + 1000) }
            assertArrayEquals(first, file.readBytes())
            val segments = file.parentFile!!.listFiles()!!.filter { it.name.startsWith("$now-$id") }
            assertEquals(2, segments.size)
            val samples = segments.flatMap { part ->
                GZIPInputStream(part.inputStream()).bufferedReader().use { it.readLines().drop(1) }
            }.map(::parseRecordingLine)
            assertEquals(2, samples.size)
            assertTrue(samples.any { it.sample.heartRate == 70.0 })
            assertTrue(samples.any { it.sample.accelerationG == 0.01 })
        } finally { file.parentFile!!.listFiles()?.filter { it.name.startsWith("$now-$id") }?.forEach { it.delete() } }
    }

    @Test fun rawRecordingBatchesFlushesAndStillClosesWithAllSamples() {
        val id = "flush-test-${System.nanoTime()}"
        val now = System.currentTimeMillis()
        val file = File(context.filesDir, "recordings/$now-$id.csv.gz")
        try {
            RawRecorder(context, id, now).use { recorder ->
                val headerBytes = file.length()
                recorder.append(listOf(SensorSample(now, 70.0, heartRateValid = true)), now)
                recorder.append(listOf(SensorSample(now + 1000, accelerationG = 0.01)), now + 1000)
                assertEquals(headerBytes, file.length())
                recorder.append(listOf(SensorSample(now + 30_000, accelerationG = 0.02)), now + 30_000)
                assertTrue(file.length() > headerBytes)
            }
            val lines = GZIPInputStream(file.inputStream()).bufferedReader().readLines()
            assertEquals(4, lines.size)
            assertEquals(0.02, parseRecordingLine(lines.last()).sample.accelerationG!!, 0.00001)
        } finally { file.delete() }
    }

    @Test fun lowBatteryStopsSensorsWithoutCancellingFinalAlarm() = runBlocking {
        assumeTrue("Battery simulation is limited to an emulator", Build.HARDWARE in listOf("ranchu", "goldfish"))
        assumeTrue(app.repository.active() == null)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
        val heartPermission = if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else "android.permission.BODY_SENSORS"
        automation.grantRuntimePermission(context.packageName, heartPermission)
        automation.grantRuntimePermission(context.packageName, "android.permission.ACTIVITY_RECOGNITION")
        val session = app.repository.start(AlarmSettings(mode = AlarmMode.CYCLES, cycles = 1, sound = false), System.currentTimeMillis())
        try {
            shell("dumpsys battery unplug")
            shell("dumpsys battery set level 20")
            ActivityScenario.launch(MainActivity::class.java).use {
                ContextCompat.startForegroundService(context,
                    Intent(context, SleepTrackingService::class.java).putExtra("sessionId", session.id))
                withTimeout(10_000) {
                    while (app.repository.get(session.id)?.note?.contains("배터리 20%") != true) delay(100)
                }
                assertEquals(SessionState.TRACKING, app.repository.get(session.id)!!.state)
                assertEquals(session.window.end, context.getSystemService(AlarmManager::class.java).nextAlarmClock!!.triggerTime)
            }
        } finally {
            shell("dumpsys battery reset")
            context.stopService(Intent(context, SleepTrackingService::class.java))
            app.repository.finish(session.id, true, "test cleanup", System.currentTimeMillis())
        }
    }

    @Test fun retentionRemovesExpiredHistoryAndRecordingsButKeepsRecentData() = runBlocking {
        val now = System.currentTimeMillis()
        val day = 24 * 60 * MINUTE
        val old = AlarmPolicy.create(AlarmSettings(), now - 31 * day, java.time.ZoneId.systemDefault())
            .copy(state = SessionState.COMPLETED, endedAt = now - 30 * day)
        val fresh = AlarmPolicy.create(AlarmSettings(), now, java.time.ZoneId.systemDefault())
            .copy(state = SessionState.COMPLETED, endedAt = now)
        val dir = File(context.filesDir, "recordings").apply { mkdirs() }
        val expiredFile = File(dir, "${now - 8 * day}-retention-test.csv.gz")
        val recentFile = File(dir, "$now-retention-test.csv.gz")
        try {
            app.database.dao().save(old.record()); app.database.dao().save(fresh.record())
            expiredFile.writeText("old"); recentFile.writeText("recent")
            app.repository.prune(now)
            assertNull(app.repository.get(old.id))
            assertNotNull(app.repository.get(fresh.id))
            assertFalse(expiredFile.exists())
            assertTrue(recentFile.exists())
        } finally {
            expiredFile.delete(); recentFile.delete()
            app.database.openHelper.writableDatabase.delete("sessions", "id IN (?,?)", arrayOf(old.id, fresh.id))
        }
    }
}
