package com.friend.ios.amuless

import android.content.Context
import android.os.PowerManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Naming of drained recordings: `audio_<marker>_opus_fs320_16000_1_fs320_<startUnixSec>.bin`. */
object RecordingFiles {
    private val pattern = Regex("""^audio_(?:amulessbatchlimitless|omibatchlimitless)_opus_fs320_16000_1_fs320_(\d+)\.bin$""")
    const val DONE_SUFFIX = ".done"

    fun startSec(file: File): Long? = pattern.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()

    /** Finalized `.bin` files waiting to be transcribed, oldest first. */
    fun pending(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray()).filter { startSec(it) != null && it.length() > 0 }.sortedBy { startSec(it) }

    fun transcriptCache(bin: File) = File(bin.parentFile, bin.name + ".json")
}

/**
 * Turns drained recordings into one Memoria note per run:
 * decode (.bin -> WAV) -> Whisper -> merge -> Memoria. Each file's transcript is cached next to it so
 * a Memoria/network failure only repeats the failed step. Processed `.bin` files are renamed
 * `.bin.done` and deleted after [KEEP_DONE_DAYS] days.
 */
object Transcriber {
    private const val TAG = "Amuless.Transcribe"
    private const val PREFS = "amuless_transcribe"
    private const val RETRY_MS = 10 * 60_000L
    private const val KEEP_DONE_DAYS = 7L
    private const val WAKELOCK_MS = 90 * 60_000L

    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "amuless-transcribe").apply { isDaemon = true } }

    data class State(val running: Boolean, val result: String, val error: String, val at: Long, val pending: Int)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun audioDir(context: Context): File? =
        context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            .getString("flutter.batchAudioDir", null)?.takeIf { it.isNotEmpty() }?.let(::File)

    fun state(context: Context): State {
        val p = prefs(context)
        val dir = audioDir(context)
        return State(
            running.get(), p.getString("result", "").orEmpty(), p.getString("error", "").orEmpty(),
            p.getLong("at", 0L), if (dir == null) 0 else RecordingFiles.pending(dir).size,
        )
    }

    private fun record(context: Context, result: String, error: String = "", retryable: Boolean = false) {
        prefs(context).edit().putString("result", result).putString("error", error).putLong("at", System.currentTimeMillis())
            .putLong("next_attempt_at", if (retryable) System.currentTimeMillis() + RETRY_MS else 0L).apply()
    }

    /** True when there is work, nothing is running, and any back-off has passed. */
    fun shouldRetry(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (running.get() || !AmulessSettings.isConfigured(context)) return false
        val dir = audioDir(context) ?: return false
        if (RecordingFiles.pending(dir).isEmpty()) return false
        return nowMs >= prefs(context).getLong("next_attempt_at", 0L)
    }

    /** Starts a run in the background unless one is already running. [manual] ignores the back-off. */
    fun kick(context: Context, manual: Boolean = false) {
        val app = context.applicationContext
        if (manual) prefs(app).edit().putLong("next_attempt_at", 0L).apply()
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            val lock = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "amuless:transcribe")
            try {
                lock.acquire(WAKELOCK_MS)
                run(app)
            } catch (e: Throwable) {
                Log.e(TAG, "run crashed", e)
                record(app, "error", "予期しないエラー: ${e.javaClass.simpleName}", retryable = true)
            } finally {
                if (lock.isHeld) lock.release()
                running.set(false)
            }
        }
    }

    private fun run(context: Context) {
        val dir = audioDir(context) ?: return
        pruneDone(dir)
        val files = RecordingFiles.pending(dir)
        if (files.isEmpty()) return
        if (!AmulessSettings.isConfigured(context)) {
            record(context, "not_configured", "設定(Whisper の URL、memoria の URL・トークン・ノートブック)が未入力です")
            return
        }
        val whisper = WhisperClient(AmulessSettings.whisperUrl(context), AmulessSettings.whisperModel(context))
        val memoria = MemoriaClient(AmulessSettings.memoriaUrl(context), AmulessSettings.token(context)!!, AmulessSettings.notebookId(context))

        // 1) transcribe every file (cached per file)
        val all = ArrayList<Pair<Long, List<Segment>>>() // startSec to segments
        for (bin in files) {
            val start = RecordingFiles.startSec(bin)!!
            val cache = RecordingFiles.transcriptCache(bin)
            val segments = try {
                if (cache.exists()) readCache(cache) else transcribeFile(context, bin, whisper).also { writeCache(cache, it) }
            } catch (e: IOException) {
                Log.w(TAG, "transcribe failed for ${bin.name}: ${e.message}")
                record(context, "whisper_failed", e.message ?: e.javaClass.simpleName, retryable = true)
                return
            }
            all.add(start to segments)
        }

        // 2) one note for the whole run
        val withText = all.filter { it.second.isNotEmpty() }
        if (withText.isNotEmpty()) {
            val first = withText.first().first
            val zone = ZoneId.systemDefault()
            val title = ZonedDateTime.ofInstant(Instant.ofEpochSecond(first), zone)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " 文字起こし"
            val createdAt = ZonedDateTime.ofInstant(Instant.ofEpochSecond(first), zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            try {
                memoria.upsertNote(title, NoteHtml.build(withText, zone), createdAt)
            } catch (e: MemoriaException) {
                Log.w(TAG, "memoria failed: ${e.message}")
                record(context, "memoria_failed", e.message.orEmpty(), retryable = e.retryable)
                return
            }
        }

        // 3) done: keep the audio for a few days, drop the transcript caches
        for (bin in files) {
            RecordingFiles.transcriptCache(bin).delete()
            bin.renameTo(File(bin.parentFile, bin.name + RecordingFiles.DONE_SUFFIX))
        }
        record(context, if (withText.isEmpty()) "no_speech" else "ok")
        Log.i(TAG, "done: ${files.size} file(s), ${withText.size} with speech")
    }

    private fun transcribeFile(context: Context, bin: File, whisper: WhisperClient): List<Segment> {
        val wav = File(context.cacheDir, bin.name + ".wav")
        try {
            val ms = try {
                OpusBinDecoder.decodeToWav(bin, wav)
            } catch (e: Exception) {
                throw IOException("音声のデコードに失敗: ${e.javaClass.simpleName} ${e.message}")
            }
            Log.i(TAG, "decoded ${bin.name}: ${ms / 1000}s")
            if (ms < 1000) return emptyList() // nothing worth sending
            return whisper.transcribe(wav)
        } finally {
            wav.delete()
        }
    }

    private fun writeCache(file: File, segments: List<Segment>) {
        val arr = JSONArray()
        segments.forEach { arr.put(JSONObject().put("s", it.start).put("e", it.end).put("t", it.text)) }
        file.writeText(arr.toString())
    }

    private fun readCache(file: File): List<Segment> {
        val arr = JSONArray(file.readText())
        return (0 until arr.length()).map { val o = arr.getJSONObject(it); Segment(o.getDouble("s"), o.getDouble("e"), o.getString("t")) }
    }

    private fun pruneDone(dir: File) {
        val cutoff = System.currentTimeMillis() - KEEP_DONE_DAYS * 24 * 3600 * 1000
        (dir.listFiles() ?: emptyArray()).filter { it.name.endsWith(RecordingFiles.DONE_SUFFIX) && it.lastModified() < cutoff }
            .forEach { it.delete() }
    }
}

/** Builds the Memoria note body. Only tags Memoria keeps: h2, p, strong. Text is always escaped. */
object NoteHtml {
    private const val GAP_SEC = 5.0
    private const val MAX_PARAGRAPH_CHARS = 240

    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun build(files: List<Pair<Long, List<Segment>>>, zone: ZoneId): String {
        val hm = DateTimeFormatter.ofPattern("HH:mm")
        val hms = DateTimeFormatter.ofPattern("HH:mm:ss")
        fun at(startSec: Long, offset: Double) = ZonedDateTime.ofInstant(Instant.ofEpochMilli(startSec * 1000 + (offset * 1000).toLong()), zone)
        val sb = StringBuilder()
        for ((startSec, segments) in files) {
            sb.append("<h2>").append(escape(at(startSec, 0.0).format(hm))).append("</h2>")
            var para = StringBuilder()
            var paraStart = -1.0
            var lastEnd = -100.0
            fun flush() {
                if (para.isNotEmpty()) {
                    sb.append("<p><strong>").append(at(startSec, paraStart).format(hms)).append("</strong> ")
                        .append(escape(para.toString())).append("</p>")
                }
                para = StringBuilder(); paraStart = -1.0
            }
            for (seg in segments) {
                if (para.isNotEmpty() && (seg.start - lastEnd > GAP_SEC || para.length > MAX_PARAGRAPH_CHARS)) flush()
                if (para.isEmpty()) paraStart = seg.start
                para.append(seg.text)
                lastEnd = seg.end
            }
            flush()
        }
        return sb.toString()
    }
}
