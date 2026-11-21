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
        fun parse(json: String): List<Segment> {
            val root = JSONObject(json)
            val segs = root.optJSONArray("segments")
            val out = ArrayList<Segment>()
            if (segs != null) {
                for (i in 0 until segs.length()) {
                    val s = segs.getJSONObject(i)
                    val t = s.optString("text").trim()
                    if (t.isNotEmpty()) out.add(Segment(s.optDouble("start", 0.0), s.optDouble("end", 0.0), t))
                }
            }
            if (out.isEmpty()) {
                val t = root.optString("text").trim()
                if (t.isNotEmpty()) out.add(Segment(0.0, root.optDouble("duration", 0.0), t))
            }
            return out
        }
    }
}
