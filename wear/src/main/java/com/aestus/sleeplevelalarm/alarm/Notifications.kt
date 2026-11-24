package com.aestus.sleeplevelalarm.alarm

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.aestus.sleeplevelalarm.MainActivity
import com.aestus.sleeplevelalarm.R

object Notifications {
    const val TRACKING_ID = 10
    const val ALARM_ID = 11
    private const val TRACKING = "tracking"
    private const val ALARM = "wake_alarm"
    private fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(TRACKING, "수면 추적", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(ALARM, "기상 알람", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
    }
    fun open(context: Context, alarm: Boolean = false): PendingIntent = PendingIntent.getActivity(context, if (alarm) 2 else 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        if (alarm && Build.VERSION.SDK_INT >= 35) ActivityOptions.makeBasic().apply {
            setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }.toBundle() else null)

    fun tracking(context: Context, text: String): Notification {
        channels(context)
        return NotificationCompat.Builder(context, TRACKING).setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("수면 추적 중").setContentText(text).setContentIntent(open(context))
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
    }
    fun alarm(context: Context, sessionId: String): Notification {
        channels(context)
        val stop = PendingIntent.getService(context, 1,
            Intent(context, RingingService::class.java).setAction(RingingService.STOP).putExtra("sessionId", sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, ALARM).setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("일어날 시간이에요").setContentText("워치에서 알람을 종료해 주세요")
            .setContentIntent(open(context)).setFullScreenIntent(open(context, alarm = true), true)
            .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "알람 종료", stop).build()
    }
}
