package io.github.hdlee73.financenewsradar.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.hdlee73.financenewsradar.data.BoardApi
import io.github.hdlee73.financenewsradar.model.BoardDetail
import io.github.hdlee73.financenewsradar.model.BoardFile
import io.github.hdlee73.financenewsradar.model.BoardPost
import io.github.hdlee73.financenewsradar.model.BoardSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** 글쓰기 화면에서 고른 첨부(아직 올리기 전). */
data class PendingFile(val uri: Uri, val name: String, val size: Long)

data class BoardUiState(
    val category: String? = null,
    val sort: BoardSort = BoardSort.NEW,
    val query: String = "",
    val posts: List<BoardPost> = emptyList(),
    val isLoading: Boolean = false,
    val canLoadMore: Boolean = false,
    val detail: BoardDetail? = null,
    val detailLoading: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val nick: String = ""
)

class BoardViewModel(application: Application) : AndroidViewModel(application) {
    private val api = BoardApi(application)
    private val _state = MutableStateFlow(BoardUiState(nick = api.identity.nick))
    val state: StateFlow<BoardUiState> = _state.asStateFlow()
    private var listJob: Job? = null

    val isConfigured: Boolean get() = api.isConfigured

    init { if (api.isConfigured) reload() }

    fun reload() {
        listJob?.cancel()
        listJob = viewModelScope.launch {
            val s = _state.value
            _state.update { it.copy(isLoading = true, error = null) }
            runCatching { api.list(s.category, s.sort, s.query) }
                .onSuccess { posts -> _state.update { it.copy(posts = posts, isLoading = false, canLoadMore = posts.size >= 30) } }
                .onFailure { e -> _state.update { it.copy(isLoading = false, error = friendly(e)) } }
        }
    }

    fun loadMore() {
        val s = _state.value
        val last = s.posts.lastOrNull() ?: return
        if (s.isLoading || s.sort == BoardSort.POPULAR) return
        viewModelScope.launch {
            runCatching { api.list(s.category, s.sort, s.query, before = last.created) }
                .onSuccess { more ->
                    _state.update { it.copy(posts = it.posts + more.filter { m -> it.posts.none { p -> p.id == m.id } }, canLoadMore = more.size >= 30) }
                }
        }
    }

    fun setCategory(category: String?) { _state.update { it.copy(category = category, sort = if (it.sort == BoardSort.POPULAR || it.sort == BoardSort.MINE) BoardSort.NEW else it.sort) }; reload() }
    fun setSort(sort: BoardSort) { _state.update { it.copy(sort = sort, category = null) }; reload() }
    fun search(query: String) { _state.update { it.copy(query = query.trim()) }; reload() }

    fun open(id: Long) {
        _state.update { it.copy(detail = null, detailLoading = true, error = null) }
        viewModelScope.launch {
            runCatching { api.detail(id) }
                .onSuccess { d -> _state.update { it.copy(detail = d, detailLoading = false) } }
                .onFailure { e -> _state.update { it.copy(detailLoading = false, error = friendly(e)) } }
        }
    }

    fun closeDetail() { _state.update { it.copy(detail = null) }; reload() }

    fun setNick(nick: String) { api.identity.nick = nick; _state.update { it.copy(nick = api.identity.nick) } }

    fun submit(category: String, title: String, body: String, nick: String, files: List<PendingFile>, onDone: (Long) -> Unit) {
        if (_state.value.submitting) return
        setNick(nick)
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            runCatching {
                val keys = files.map { f ->
                    val (name, bytes) = withContext(Dispatchers.IO) { readUpload(f) }
                    api.upload(name, bytes)
                }
                api.create(category, title.trim(), body.trim(), api.identity.nick, keys)
            }.onSuccess { id ->
                _state.update { it.copy(submitting = false, message = "글을 올렸습니다.") }
                reload()
                onDone(id)
            }.onFailure { e -> _state.update { it.copy(submitting = false, error = friendly(e)) } }
        }
    }

    fun comment(postId: Long, text: String, onDone: () -> Unit) {
        if (text.isBlank()) return
        viewModelScope.launch {
            runCatching { api.comment(postId, text.trim(), api.identity.nick) }
                .onSuccess { onDone(); runCatching { api.detail(postId) }.onSuccess { d -> _state.update { it.copy(detail = d) } } }
                .onFailure { e -> _state.update { it.copy(error = friendly(e)) } }
        }
    }

    fun deleteComment(postId: Long, id: Long) {
        viewModelScope.launch {
            runCatching { api.deleteComment(id); api.detail(postId) }
                .onSuccess { d -> _state.update { it.copy(detail = d) } }
                .onFailure { e -> _state.update { it.copy(error = friendly(e)) } }
        }
    }

    fun deletePost(id: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { api.delete(id) }
                .onSuccess { _state.update { it.copy(detail = null, message = "글을 삭제했습니다.") }; reload(); onDone() }
                .onFailure { e -> _state.update { it.copy(error = friendly(e)) } }
        }
    }

    fun report(id: Long) {
        viewModelScope.launch {
            runCatching { api.report(id) }
                .onSuccess { _state.update { it.copy(message = "신고했습니다. 신고가 쌓이면 글이 가려집니다.") } }
                .onFailure { e -> _state.update { it.copy(error = friendly(e)) } }
        }
    }

    fun consumeNotice() { _state.update { it.copy(error = null, message = null) } }

    /** 첨부 하나를 내려받아 캐시 파일로. 실패하면 null(오류 문구는 상태에 남긴다). */
    suspend fun fetchFile(file: BoardFile): File? =
        runCatching { api.download(file) }.onFailure { e -> _state.update { it.copy(error = friendly(e)) } }.getOrNull()

    // --- 이미지 미리보기 ---
    private val thumbs = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun image(file: String, name: String, maxPx: Int): Bitmap? {
        val cacheKey = "$file@$maxPx"
        thumbs.get(cacheKey)?.let { return it }
        val target = runCatching { api.download(BoardFile(file, name, "image/*", 0)) }.getOrNull() ?: return null
        return withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
            BitmapFactory.decodeFile(target.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }?.also { thumbs.put(cacheKey, it) }
    }

    private fun readUpload(file: PendingFile): Pair<String, ByteArray> {
        val resolver = getApplication<Application>().contentResolver
        val raw = resolver.openInputStream(file.uri)?.use { it.readBytes() } ?: error("파일을 읽지 못했습니다: ${file.name}")
        val lower = file.name.lowercase()
        val isPhoto = lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")
        // 큰 사진은 가로·세로 1600px 이하 JPEG로 줄여 올린다(10MB 한도, 데이터 절약).
        if (isPhoto && raw.size > 1_500_000) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 1600 || bounds.outHeight / (sample * 2) >= 1600) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
            if (bitmap != null) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                return (file.name.substringBeforeLast('.') + ".jpg") to out.toByteArray()
            }
        }
        return file.name to raw
    }

    fun describe(uri: Uri): PendingFile? {
        val resolver = getApplication<Application>().contentResolver
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            PendingFile(uri, c.getString(0) ?: "file", if (c.isNull(1)) 0L else c.getLong(1))
        }
    }

    private fun friendly(e: Throwable): String = when (e) {
        is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException -> "서버에 연결하지 못했습니다. 네트워크를 확인해 주세요."
        else -> e.message ?: "요청에 실패했습니다."
    }
}
