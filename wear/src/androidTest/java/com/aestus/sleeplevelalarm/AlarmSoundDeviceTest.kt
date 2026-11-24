package com.aestus.sleeplevelalarm

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmSoundDeviceTest {
    @Test fun playAlarmSoundAndVibrationForFiveSeconds() = runBlocking {
        // Physical output is opt-in; never trigger a real session or change its alarm.
        assumeTrue(InstrumentationRegistry.getArguments().getString("playAlarmSound") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as SleepApplication
        val before = app.repository.active()
        val audio = context.getSystemService(AudioManager::class.java)
        val vibrator = context.getSystemService(Vibrator::class.java)
        val volume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        Log.i("AlarmSoundTest", "activeSound=${before?.settings?.sound}; alarmVolume=$volume/${audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)}")
        assertTrue("Watch alarm volume is zero", volume > 0)
        assertTrue("Watch has no vibrator", vibrator.hasVibrator())
        val ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        assertNotNull("No default alarm sound", ringtone)
        try {
            ringtone.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            ringtone.isLooping = true
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 350, 700, 1250), 0), ringtone.audioAttributes)
            ringtone.play()
            SystemClock.sleep(1_000)
            assertTrue("Alarm playback did not start", ringtone.isPlaying)
            Log.i("AlarmSoundTest", "Alarm playback active; vibration requested")
            SystemClock.sleep(4_000)
        } finally {
            ringtone.stop()
            vibrator.cancel()
        }
        assertFalse("Alarm playback did not stop", ringtone.isPlaying)
        val after = app.repository.active()
        assertEquals("Existing session changed during preview", before?.id, after?.id)
        assertEquals("Final alarm changed during preview", before?.window?.end, after?.window?.end)
        assertEquals("Alarm settings changed during preview", before?.settings, after?.settings)
    }
}
