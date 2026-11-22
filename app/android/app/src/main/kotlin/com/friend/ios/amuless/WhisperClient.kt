package com.friend.ios.amuless

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One transcribed phrase; [start]/[end] are seconds from the start of the audio file. */
data class Segment(val start: Double, val end: Double, val text: String)

/**
 * OpenAI-compatible transcription client (`POST {base}/audio/transcriptions`), used against
 * mlx-audio's server. No authentication.
 */
class WhisperClient(private val baseUrl: String, private val model: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(45, TimeUnit.MINUTES) // long recordings on a laptop can take a while
        .build()

    /** Cheap reachability check (GET {base}/models). Returns null when ok, else a message. */
    fun ping(): String? = try {
        val req = Request.Builder().url("$baseUrl/models").get().build()
        OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
            .newCall(req).execute().use { if (it.code in 200..299) null else "HTTP ${it.code}" }
    } catch (e: IOException) {
        "接続できません(${e.javaClass.simpleName}): Tailscale がオンか確認してください"
    }

    @Throws(IOException::class)
    fun transcribe(wav: File): List<Segment> {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", wav.name, wav.asRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", model)
            .addFormDataPart("language", "ja")
            .addFormDataPart("response_format", "verbose_json")
            .build()
        val req = Request.Builder().url("$baseUrl/audio/transcriptions").post(body).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("whisper HTTP ${resp.code}: ${text.take(200)}")
            return parse(text)
        }
    }

    companion object {
        private val REPEAT = Regex("(.{1,30}?)\\1{3,}")
        private val CYRILLIC = Regex("[\\u0400-\\u04FF]")
        private val JAPANESE = Regex("[\\u3040-\\u30FF\\u4E00-\\u9FFF]")
        private val STOCK = listOf("ご視聴ありがとうございました", "チャンネル登録", "ご清聴ありがとうございました")

        /** "おやすみなさい。おやすみなさい。…" (4+ times in a row) becomes one. */
        fun collapseRepeats(t: String) = REPEAT.replace(t) { it.groupValues[1] }

        /** Whisper invents text on silence, TV noise and music. Drop what is almost certainly invented. */
        fun isHallucination(t: String, s: JSONObject): Boolean {
            if (CYRILLIC.containsMatchIn(t)) return true // language is fixed to Japanese
            if (!JAPANESE.containsMatchIn(t) && t.length > 3) return true
            if (STOCK.any { t.contains(it) } && t.length < 25) return true
            val noSpeech = s.optDouble("no_speech_prob", 0.0)
            val logprob = s.optDouble("avg_logprob", 0.0)
            val compression = s.optDouble("compression_ratio", 0.0)
            if (compression > 2.4) return true
            if (noSpeech > 0.6 && logprob < -0.8) return true
            return false
        }

        fun parse(json: String): List<Segment> {
            val root = JSONObject(json)
            val segs = root.optJSONArray("segments")
            val out = ArrayList<Segment>()
            if (segs != null) {
                for (i in 0 until segs.length()) {
                    val s = segs.getJSONObject(i)
                    val t = collapseRepeats(s.optString("text").trim())
                    if (t.isEmpty() || isHallucination(t, s)) continue
                    // identical text twice in a row is a decoder loop, keep the first
                    if (out.isNotEmpty() && out.last().text == t) continue
                    out.add(Segment(s.optDouble("start", 0.0), s.optDouble("end", 0.0), t))
                }
                return out
            }
            if (out.isEmpty()) {
                val t = root.optString("text").trim()
                if (t.isNotEmpty()) out.add(Segment(0.0, root.optDouble("duration", 0.0), t))
            }
            return out
        }
    }
}
