package io.github.hdlee73.financenewsradar.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** 자료실 항목(폴더 또는 파일). */
data class LibraryEntry(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val type: String,
    val downloadName: String,
    val size: Long,
    val modified: String,
    /** 이 항목이 들어 있는 폴더 ID(서버가 범위를 확인하는 데 쓴다). */
    val parent: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    /** 검색 결과일 때 파일이 들어 있는 폴더 이름. */
    val location: String = ""
)

data class LibraryListing(val folderId: String, val isRoot: Boolean, val items: List<LibraryEntry>, val tags: List<String> = emptyList())

/** 구글 드라이브 자료실(서버 /library)을 읽는다. 서버가 폴더 범위를 제한하고 키를 보관한다. */
class LibraryApi(private val context: Context) {
    val isConfigured: Boolean get() = NewsProxy.isConfigured

    private fun open(path: String): HttpURLConnection {
        val c = URI(NewsProxy.url.trimEnd('/') + "/library" + path).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 12_000
        c.readTimeout = 60_000
        c.setRequestProperty("X-App-Token", NewsProxy.token)
        return c
    }

    private fun read(c: HttpURLConnection): ByteArray {
        try {
            val code = c.responseCode
            if (code in 200..299) return c.inputStream.use { it.readBytes() }
            val text = c.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            val message = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
            error(message.ifBlank { "서버 오류 ($code)" })
        } finally {
            c.disconnect()
        }
    }

    private fun parse(root: JSONObject, folderId: String): LibraryListing {
        val array = root.optJSONArray("items")
        val items = (0 until (array?.length() ?: 0)).map {
            val o = array!!.getJSONObject(it)
            val tagArray = o.optJSONArray("tags")
            LibraryEntry(
                id = o.getString("id"), name = o.optString("name"), isFolder = o.optBoolean("folder"),
                type = o.optString("type"), downloadName = o.optString("downloadName", o.optString("name")),
                size = o.optLong("size"), modified = o.optString("modified"),
                parent = o.optString("parent", folderId),
                description = o.optString("description"),
                tags = (0 until (tagArray?.length() ?: 0)).map { i -> tagArray!!.getString(i) },
                location = o.optString("location")
            )
        }
        val allTags = root.optJSONArray("tags")
        return LibraryListing(
            root.optString("folder", folderId), root.optBoolean("root"), items,
            (0 until (allTags?.length() ?: 0)).map { allTags!!.getString(it) }
        )
    }

    suspend fun list(folderId: String? = null): LibraryListing = withContext(Dispatchers.IO) {
        val path = "/list" + if (folderId.isNullOrBlank()) "" else "?folder=" + URLEncoder.encode(folderId, "UTF-8")
        parse(JSONObject(String(read(open(path)), Charsets.UTF_8)), folderId.orEmpty())
    }

    /** 자료실 전체에서 파일명·설명·#태그로 검색. `#태그`로 시작하면 태그 일치. */
    suspend fun search(query: String): LibraryListing = withContext(Dispatchers.IO) {
        parse(JSONObject(String(read(open("/search?q=" + URLEncoder.encode(query, "UTF-8"))), Charsets.UTF_8)), "")
    }

    /** 파일을 앱 캐시에 받아 둔다(이미 있으면 재사용). 반환 파일 이름은 안전하게 바꾼 이름. */
    suspend fun download(entry: LibraryEntry): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "article_pdfs/library").apply { mkdirs() }
        val safe = entry.downloadName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val target = File(dir, "${entry.id.take(8)}_$safe")
        if (!target.exists() || target.length() == 0L) {
            val bytes = read(open("/file/${entry.id}?folder=${URLEncoder.encode(entry.parent, "UTF-8")}"))
            val tmp = File(dir, target.name + ".part")
            tmp.writeBytes(bytes)
            tmp.renameTo(target)
        }
        target
    }

    /**
     * 파일을 서버의 "업로드 대기" 폴더로 올린다. 관리자가 확인해 자료실로 옮기기 전에는 목록에 나오지 않는다.
     * 서버가 형식·용량(20MB)을 다시 확인한다.
     */
    suspend fun upload(
        name: String, mimeType: String, size: Long, description: String, uploader: String, source: () -> java.io.InputStream?
    ) = withContext(Dispatchers.IO) {
        if (size > MAX_UPLOAD_BYTES) error("파일은 20MB까지 올릴 수 있습니다.")
        val query = "?name=" + URLEncoder.encode(name, "UTF-8") +
            "&description=" + URLEncoder.encode(description, "UTF-8") +
            "&uploader=" + URLEncoder.encode(uploader, "UTF-8")
        val c = open("/upload$query")
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", mimeType.ifBlank { "application/octet-stream" })
        if (size > 0) c.setFixedLengthStreamingMode(size) else error("파일 크기를 알 수 없습니다.")
        c.readTimeout = 120_000
        (source() ?: error("파일을 열 수 없습니다.")).use { input -> c.outputStream.use { input.copyTo(it) } }
        read(c)
        Unit
    }

    companion object {
        const val MAX_UPLOAD_BYTES = 20L * 1024 * 1024
    }
}
