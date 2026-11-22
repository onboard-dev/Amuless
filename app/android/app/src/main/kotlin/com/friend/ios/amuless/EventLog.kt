package com.friend.ios.amuless

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small persistent log of things that explain why monitoring stopped or resumed: service start/stop,
 * BLE connect/disconnect, memory warnings, drain results, and (on the next start) why Android
 * ended the previous process. Shown in the app under Settings.
 */
object EventLog {
    private const val FILE = "amuless_events.log"
    private const val MAX_LINES = 300
    private val lock = Any()
    private val fmt get() = SimpleDateFormat("MM/dd HH:mm:ss", Locale.US)

    fun add(c: Context, text: String, at: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            try {
                val f = java.io.File(c.filesDir, FILE)
                val old = if (f.exists()) f.readLines() else emptyList()
                f.writeText((old + "${fmt.format(Date(at))} $text").takeLast(MAX_LINES).joinToString("\n") + "\n")
            } catch (_: Exception) {
            }
        }
    }

    fun lines(c: Context): List<String> = synchronized(lock) {
        try {
            java.io.File(c.filesDir, FILE).takeIf { it.exists() }?.readLines().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clear(c: Context) = synchronized(lock) { java.io.File(c.filesDir, FILE).delete() }

    private val REASONS = mapOf(
        1 to "自分で終了", 2 to "シグナルで強制終了(SIGKILL など)", 3 to "メモリ不足", 4 to "クラッシュ", 5 to "ネイティブのクラッシュ",
        6 to "応答なし(ANR)", 7 to "初期化の失敗", 8 to "権限の変更", 9 to "リソースの使いすぎ", 10 to "ユーザーが終了",
        11 to "ユーザーが停止", 12 to "依存先の終了", 13 to "その他", 14 to "フリーザー(凍結)", 15 to "パッケージの状態変更", 16 to "アプリの更新",
    )

    private fun importanceName(i: Int) = when {
        i <= 100 -> "前面"
        i <= 125 -> "前面サービス"
        i <= 230 -> "目に見える/知覚できる"
        i <= 300 -> "サービス"
        else -> "キャッシュ"
    } + "($i)"

    /** Logs why earlier processes of this app ended, once each (Android 11+). */
    fun recordPastExits(c: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val prefs = c.getSharedPreferences("amuless_events", Context.MODE_PRIVATE)
            val seen = prefs.getLong("last_exit_ts", 0L)
            val exits = am.getHistoricalProcessExitReasons(c.packageName, 0, 10).filter { it.timestamp > seen }.sortedBy { it.timestamp }
            for (e in exits) {
                add(c, "前回のプロセスが終了: ${REASONS[e.reason] ?: "理由 ${e.reason}"} / 終了時の重要度 ${importanceName(e.importance)} / 状態コード ${e.status}", e.timestamp)
            }
            if (exits.isNotEmpty()) prefs.edit().putLong("last_exit_ts", exits.last().timestamp).apply()
        } catch (_: Exception) {
        }
    }
}
