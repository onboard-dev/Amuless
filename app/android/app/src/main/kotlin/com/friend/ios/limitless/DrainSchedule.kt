package com.friend.ios.limitless

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.friend.ios.ble.OmiBleForegroundService
import java.util.Calendar

/**
 * Amuless: when the pendant's flash is drained.
 *
 * omi drained every 90 s. Amuless drains once per day at [hour]:[minute], plus on demand
 * ("drain now"). State lives in its own preferences file so it never mixes with omi's keys.
 *
 * A drain is *due* when the last completed drain is older than the most recent scheduled
 * moment (so a phone that was off/out of range at 02:00 catches up as soon as the pendant
 * connects), or when the user asked for one after the last completed drain.
 */
object DrainSchedule {
    private const val TAG = "Amuless.Schedule"
    private const val PREFS = "amuless_schedule"
    private const val KEY_HOUR = "hour"
    private const val KEY_MINUTE = "minute"
    private const val KEY_LAST_DONE_AT = "last_done_at"
    private const val KEY_REQUESTED_AT = "requested_at"
    private const val KEY_LAST_RESULT = "last_result"
    private const val KEY_PROGRESS_DONE = "progress_done"
    private const val KEY_PROGRESS_TOTAL = "progress_total"
    const val ACTION_ALARM = "com.friend.ios.limitless.DRAIN_ALARM"

    const val DEFAULT_HOUR = 2
    const val DEFAULT_MINUTE = 0

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hour(context: Context): Int = prefs(context).getInt(KEY_HOUR, DEFAULT_HOUR)
    fun minute(context: Context): Int = prefs(context).getInt(KEY_MINUTE, DEFAULT_MINUTE)
    fun lastDoneAt(context: Context): Long = prefs(context).getLong(KEY_LAST_DONE_AT, 0L)
    fun requestedAt(context: Context): Long = prefs(context).getLong(KEY_REQUESTED_AT, 0L)
    fun lastResult(context: Context): String = prefs(context).getString(KEY_LAST_RESULT, "") ?: ""

    /** Pages read so far / pages to read in the drain that is running (0/0 when idle). */
    fun progress(context: Context): Pair<Int, Int> =
        prefs(context).getInt(KEY_PROGRESS_DONE, 0) to prefs(context).getInt(KEY_PROGRESS_TOTAL, 0)

    fun setProgress(context: Context, done: Int, total: Int) {
        prefs(context).edit().putInt(KEY_PROGRESS_DONE, done).putInt(KEY_PROGRESS_TOTAL, total).apply()
    }

    fun setTime(context: Context, hour: Int, minute: Int) {
        prefs(context).edit().putInt(KEY_HOUR, hour.coerceIn(0, 23)).putInt(KEY_MINUTE, minute.coerceIn(0, 59)).apply()
        arm(context)
    }

    fun requestNow(context: Context, nowMs: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_REQUESTED_AT, nowMs).apply()
    }

    fun markDone(context: Context, result: String, nowMs: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_LAST_DONE_AT, nowMs).putString(KEY_LAST_RESULT, result).apply()
    }

    fun markFailed(context: Context, result: String) {
        prefs(context).edit().putString(KEY_LAST_RESULT, result).apply()
    }

    /** The latest scheduled moment that is <= [nowMs]. */
    fun latestOccurrence(context: Context, nowMs: Long): Long = latestOccurrence(hour(context), minute(context), nowMs)

    fun latestOccurrence(hour: Int, minute: Int, nowMs: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis > nowMs) c.add(Calendar.DAY_OF_YEAR, -1)
        return c.timeInMillis
    }

    fun nextOccurrence(hour: Int, minute: Int, nowMs: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = latestOccurrence(hour, minute, nowMs); add(Calendar.DAY_OF_YEAR, 1) }
        return c.timeInMillis
    }

    fun isDue(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val done = lastDoneAt(context)
        return requestedAt(context) > done || done < latestOccurrence(context, nowMs)
    }

    /** Arms the next daily alarm. Inexact + allow-while-idle: no special permission, may slip a few minutes. */
    fun arm(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val whenMs = nextOccurrence(hour(context), minute(context), System.currentTimeMillis())
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, alarmIntent(context))
            Log.i(TAG, "armed for ${java.util.Date(whenMs)}")
        } catch (e: SecurityException) {
            Log.w(TAG, "could not arm alarm: ${e.message}")
        }
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, DrainAlarmReceiver::class.java).setAction(ACTION_ALARM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** Fires at the scheduled time: re-arm tomorrow, then wake the drain engine. */
class DrainAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        DrainSchedule.arm(context)
        val service = OmiBleForegroundService.instance
        if (service != null) {
            service.drainTick()
        } else {
            // The BLE service is not running; the engine catches up as soon as the pendant connects
            // (isDue stays true), so there is nothing to start from the background here.
            Log.i("Amuless.Schedule", "alarm fired but BLE service is not running; will catch up on connect")
        }
    }
}
