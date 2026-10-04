package io.github.hdlee73.financenewsradar.data

import android.content.Context
import io.github.hdlee73.financenewsradar.model.BoardComment
import io.github.hdlee73.financenewsradar.model.BoardDetail
import io.github.hdlee73.financenewsradar.model.BoardFile
import io.github.hdlee73.financenewsradar.model.BoardPost
import io.github.hdlee73.financenewsradar.model.BoardSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.UUID

/** 기기마다 한 번 만들어 두는 익명 식별값과 별명. 내 글·댓글만 지울 수 있게 하는 데에만 쓴다. */
class BoardIdentity(context: Context) {
    private val prefs = context.getSharedPreferences("board_identity", Context.MODE_PRIVATE)

    val deviceId: String
        get() = prefs.getString("device", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device", it).apply()
        }

    var nick: String
        get() = prefs.getString("nick", "") ?: ""
        set(value) { prefs.edit().putString("nick", value.trim().take(16)).apply() }
}

/** "감독 및 검사 팁" 게시판 서버 호출. 서버는 뉴스 프록시와 같은 Worker(/board)다. */
class BoardApi(private val context: Context) {
    val identity = BoardIdentity(context)
    val isConfigured: Boolean get() = NewsProxy.isConfigured

    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8")

    private fun open(method: String, path: String): HttpURLConnection {
        val connection = URI(NewsProxy.url.trimEnd('/') + "/board" + path).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 12_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("X-App-Token", NewsProxy.token)
        connection.setRequestProperty("X-Device-Id", identity.deviceId)
        return connection
    }

    private fun send(connection: HttpURLConnection, body: ByteArray?, contentType: String): ByteArray {
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            if (code in 200..299) return connection.inputStream.use { it.readBytes() }
            val text = connection.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            val message = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
            error(message.ifBlank { "서버 오류 ($code)" })
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun json(method: String, path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val bytes = send(open(method, path), body?.toString()?.toByteArray(Charsets.UTF_8), "application/json; charset=utf-8")
        JSONObject(String(bytes, Charsets.UTF_8))
    }

    suspend fun list(category: String?, sort: BoardSort, query: String, before: Long = 0): List<BoardPost> {
        val path = buildString {
            append("/posts?sort=${sort.wire}&limit=30")
            if (!category.isNullOrBlank()) append("&category=${enc(category)}")
            if (query.isNotBlank()) append("&q=${enc(query.trim())}")
            if (before > 0) append("&before=$before")
        }
        return parsePosts(json("GET", path).optJSONArray("posts"))
    }

    suspend fun detail(id: Long): BoardDetail {
        val root = json("GET", "/posts/$id")
        val post = root.getJSONObject("post")
        val files = root.optJSONArray("files") ?: JSONArray()
        val comments = root.optJSONArray("comments") ?: JSONArray()
        return BoardDetail(
            post = BoardPost(
                id = post.getLong("id"), category = post.optString("category"), title = post.optString("title"),
                nick = post.optString("nick"), created = post.optLong("created"), views = post.optInt("views"),
                preview = "", comments = comments.length(), attachments = files.length(), thumb = null,
                mine = post.optBoolean("mine")
            ),
            body = post.optString("body"),
            files = (0 until files.length()).map {
                val f = files.getJSONObject(it)
                BoardFile(f.getString("key"), f.optString("name"), f.optString("type"), f.optLong("size"))
            },
            comments = (0 until comments.length()).map {
                val c = comments.getJSONObject(it)
                BoardComment(c.getLong("id"), c.optString("body"), c.optString("nick"), c.optLong("created"), c.optBoolean("mine"))
            }
        )
    }

    suspend fun create(category: String, title: String, body: String, nick: String, fileKeys: List<String>): Long =
        json(
            "POST", "/posts",
            JSONObject().put("category", category).put("title", title).put("body", body).put("nick", nick)
                .put("files", JSONArray(fileKeys))
        ).getLong("id")

    suspend fun delete(id: Long) { json("DELETE", "/posts/$id") }
    suspend fun report(id: Long) { json("POST", "/posts/$id/report", JSONObject()) }
    suspend fun comment(postId: Long, body: String, nick: String) {
        json("POST", "/posts/$postId/comments", JSONObject().put("body", body).put("nick", nick))
    }
    suspend fun deleteComment(id: Long) { json("DELETE", "/comments/$id") }

    /** 파일을 올리고 서버가 정한 키를 돌려받는다. */
    suspend fun upload(name: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val response = send(open("POST", "/upload?name=${enc(name)}"), bytes, "application/octet-stream")
        JSONObject(String(response, Charsets.UTF_8)).getString("key")
    }

    /** 첨부를 내려받아 앱 캐시에 저장한다(같은 파일은 다시 받지 않는다). */
    suspend fun download(file: BoardFile): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "article_pdfs/board").apply { mkdirs() }
        val target = File(dir, file.key)
        if (!target.exists() || target.length() == 0L) {
            val bytes = send(open("GET", "/files/${file.key}"), null, "")
            target.writeBytes(bytes)
        }
        target
    }

    private fun parsePosts(array: JSONArray?): List<BoardPost> {
        array ?: return emptyList()
        return (0 until array.length()).map {
            val p = array.getJSONObject(it)
            BoardPost(
                id = p.getLong("id"), category = p.optString("category"), title = p.optString("title"),
                nick = p.optString("nick"), created = p.optLong("created"), views = p.optInt("views"),
                preview = p.optString("preview"), comments = p.optInt("comments"),
                attachments = p.optInt("attachments"),
                thumb = if (p.isNull("thumb")) null else p.optString("thumb"),
                mine = p.optBoolean("mine")
            )
        }
    }
}
