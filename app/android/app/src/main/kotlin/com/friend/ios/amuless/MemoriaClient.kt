package com.friend.ios.amuless

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Failure talking to Memoria. [retryable] is false for problems only the user can fix (token, missing notebook). */
class MemoriaException(message: String, val retryable: Boolean) : IOException(message)

/**
 * Posts transcripts to Memoria: the notebook chosen in the settings screen > section "文字起こし".
 *
 * The token is only ever put in the Authorization header; it is not logged and does not appear in
 * exception messages.
 */
class MemoriaClient(baseUrl: String, private val token: String, private val notebookId: String = "") {
    private val base = baseUrl.trimEnd('/')
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    private data class Node(
        val id: String,
        val kind: String,
        val title: String,
        val parentId: String?,
        val revision: String,
        val createdAt: String = "",
    )

    /** A note in the transcript section, for the in-app viewer. */
    data class NoteInfo(val id: String, val title: String, val createdAt: String)

    /** Verifies token + notebook + section. Returns null when ok, else a message for the user. */
    fun check(): String? = try {
        sectionId(); null
    } catch (e: MemoriaException) {
        e.message
    }

    private fun call(builder: Request.Builder, ifMatch: String? = null): Pair<Int, String> {
        builder.header("Authorization", "Bearer $token")
        if (ifMatch != null) builder.header("If-Match", "\"$ifMatch\"")
        try {
            http.newCall(builder.build()).execute().use { return Pair(it.code, it.body?.string().orEmpty()) }
        } catch (e: IOException) {
            throw MemoriaException("memoria に接続できません(${e.javaClass.simpleName}): Tailscale がオンか確認してください", true)
        }
    }

    private fun failFor(code: Int, what: String): Nothing = when (code) {
        401 -> throw MemoriaException("memoria: トークンが違うか無効です($what)", false)
        403 -> throw MemoriaException("memoria: このトークンでは使えない操作です($what)", false)
        429 -> throw MemoriaException("memoria: 一時的に拒否されました(429)。しばらく待ちます", true)
        else -> throw MemoriaException("memoria: $what が失敗しました(HTTP $code)", code >= 500 || code == 409)
    }

    private fun tree(): List<Node> {
        val (code, body) = call(Request.Builder().url("$base/api/tree").get())
        if (code != 200) failFor(code, "ツリー取得")
        val arr = JSONObject(body).getJSONArray("nodes")
        return (0 until arr.length()).map {
            val n = arr.getJSONObject(it)
            Node(n.getString("id"), n.optString("kind"), n.optString("title"), n.optString("parentId").takeIf { p -> p.isNotEmpty() && p != "null" }, n.opt("revision")?.toString().orEmpty(), n.optString("createdAt"))
        }
    }

    /** Newest notes in the chosen notebook's transcript section, newest first. */
    fun recentNotes(limit: Int = 40): List<NoteInfo> {
        val nodes = tree()
        val section = sectionId(nodes)
        return nodes.filter { it.kind == "note" && it.parentId == section }
            .map { NoteInfo(it.id, it.title, it.createdAt) }
            .sortedWith(compareByDescending<NoteInfo> { it.createdAt }.thenByDescending { it.title })
            .take(limit)
    }

    /** The note body as stored (HTML). */
    fun noteHtml(id: String): String {
        val (code, body) = call(Request.Builder().url("$base/api/notes/$id").get())
        if (code != 200) failFor(code, "ノート取得")
        return JSONObject(body).optString("html")
    }

    /** All notebooks as (id, title), for the picker in the settings screen. */
    fun notebooks(): List<Pair<String, String>> =
        tree().filter { it.kind == "notebook" }.map { it.id to it.title }.sortedBy { it.second.lowercase() }

    private fun sectionId(nodes: List<Node> = tree()): String {
        if (notebookId.isEmpty()) throw MemoriaException("memoria のノートブックが未選択です(設定画面で選んでください)", false)
        val notebook = nodes.firstOrNull { it.kind == "notebook" && it.id == notebookId }
            ?: throw MemoriaException("選択したノートブックが memoria にありません(削除された場合は、設定画面で選び直してください)", false)
        return nodes.firstOrNull { it.kind == "section" && it.title == SECTION && it.parentId == notebook.id }?.id
            ?: throw MemoriaException("ノートブック「${notebook.title}」にセクション「$SECTION」がありません", false)
    }

    /**
     * Creates the note, or replaces its body when a note with the same title already exists in the
     * section (the server has no duplicate detection). [createdAtIso] sets the creation date.
     */
    fun upsertNote(title: String, html: String, createdAtIso: String) {
        val nodes = tree()
        val section = sectionId(nodes)
        val safeTitle = title.take(200)
        val existing = nodes.firstOrNull { it.kind == "note" && it.parentId == section && it.title == safeTitle }
        if (existing != null) {
            patch(existing.id, existing.revision, JSONObject().put("html", html))
            return
        }
        val create = JSONObject().put("kind", "note").put("title", safeTitle).put("parentId", section).put("html", html)
        val (code, body) = call(Request.Builder().url("$base/api/nodes").post(create.toString().toRequestBody(json)))
        if (code != 201) failFor(code, "ノート作成")
        val node = JSONObject(body).getJSONObject("node")
        // Backdate to the recording time. Failing here must not lose the note that already exists.
        try {
            patch(node.getString("id"), node.opt("revision").toString(), JSONObject().put("createdAt", createdAtIso))
        } catch (_: MemoriaException) {
        }
    }

    private fun patch(id: String, revision: String, body: JSONObject) {
        var rev = revision
        repeat(3) {
            val (code, resp) = call(
                Request.Builder().url("$base/api/nodes/$id").patch(body.toString().toRequestBody(json)),
                ifMatch = rev,
            )
            when (code) {
                200 -> return
                409 -> rev = JSONObject(resp).optJSONObject("node")?.opt("revision")?.toString() ?: refetchRevision(id)
                else -> failFor(code, "ノート更新")
            }
        }
        throw MemoriaException("memoria: 更新が競合し続けました(409)", true)
    }

    private fun refetchRevision(id: String): String {
        val (code, body) = call(Request.Builder().url("$base/api/notes/$id").get())
        if (code != 200) failFor(code, "ノート取得")
        return JSONObject(body).getJSONObject("node").opt("revision").toString()
    }

    companion object {
        const val SECTION = "文字起こし"
    }
}
