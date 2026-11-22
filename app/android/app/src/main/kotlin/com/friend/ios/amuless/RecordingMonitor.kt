package com.friend.ios.amuless

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Knows whether the pendant is recording. A recording pendant sends a storage update every few
 * seconds; a paused one (long press, LED off) sends none. So "connected but no update for
 * [STALL_MS]" means paused. If it stays paused for the configured time, a notification asks the
 * user to long-press the button again; it is withdrawn as soon as updates resume.
 */
object RecordingMonitor {
    private const val TAG = "Amuless.Monitor"
    private const val PREFS = "amuless_monitor"
    private const val CHANNEL = "amuless_alerts"
    private const val NOTIFICATION_ID = 4101
    const val STALL_MS = 120_000L
    const val DEFAULT_REMINDER_MIN = 60

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun onStorageUpdate(c: Context, now: Long = System.currentTimeMillis()) {
        prefs(c).edit().putLong("last_update", now).apply()
    }

    fun onConnected(c: Context, now: Long = System.currentTimeMillis()) {
        prefs(c).edit().putLong("connected_at", now).apply()
    }

    /** The latest sign of life from the recording side (a storage update, or the connection itself). */
    fun signalAt(c: Context): Long = maxOf(prefs(c).getLong("last_update", 0L), prefs(c).getLong("connected_at", 0L))

    fun reminderMinutes(c: Context): Int = prefs(c).getInt("reminder_min", DEFAULT_REMINDER_MIN)
    fun setReminderMinutes(c: Context, minutes: Int) {
        prefs(c).edit().putInt("reminder_min", minutes.coerceIn(0, 24 * 60)).apply()
    }

    /** Called once a minute while the pendant is connected and idle. */
    fun check(c: Context, now: Long = System.currentTimeMillis()) {
        val since = signalAt(c)
        val notified = prefs(c).getLong("notified_for", 0L)
        if (since == 0L || now - since <= STALL_MS) {
            if (notified != 0L) {
                cancel(c)
                prefs(c).edit().putLong("notified_for", 0L).apply()
            }
            return
        }
        val minutes = reminderMinutes(c)
        if (minutes <= 0 || notified == since || now - since < minutes * 60_000L) return
        notifyPaused(c, since, now)
        prefs(c).edit().putLong("notified_for", since).apply()
    }

    private fun manager(c: Context) = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun cancel(c: Context) = manager(c).cancel(NOTIFICATION_ID)

    private fun notifyPaused(c: Context, since: Long, now: Long) {
        try {
            val nm = manager(c)
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "録音の停止", NotificationManager.IMPORTANCE_HIGH))
            }
            val open = c.packageManager.getLaunchIntentForPackage(c.packageName)
            val pi = PendingIntent.getActivity(c, 0, open ?: Intent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val minutes = ((now - since) / 60_000L).toInt()
            val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CHANNEL) else Notification.Builder(c))
                .setSmallIcon(c.applicationInfo.icon)
                .setContentTitle("ペンダントの録音が止まっています")
                .setContentText("約${minutes}分間、録音されていません。再開するには、ボタンを長押ししてください。")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFICATION_ID, n)
            Log.i(TAG, "paused reminder posted ($minutes min)")
        } catch (e: Exception) {
            Log.w(TAG, "reminder failed: ${e.message}")
        }
    }
}
