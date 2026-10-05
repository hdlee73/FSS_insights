package io.github.hdlee73.financenewsradar.ui

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.hdlee73.financenewsradar.data.LibraryApi
import io.github.hdlee73.financenewsradar.data.LibraryEntry
import io.github.hdlee73.financenewsradar.ui.theme.AppColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class LibraryUiState(
    /** 들어가 있는 폴더 경로(이름, ID). 비어 있으면 최상위. */
    val path: List<Pair<String, String>> = emptyList(),
    val items: List<LibraryEntry> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val busyId: String? = null,
    val tags: List<String> = emptyList(),
    /** 비어 있지 않으면 검색 결과 보기. */
    val query: String = "",
    /** true면 폴더 구분 없이 모든 파일을 최근순으로 본다. */
    val showAll: Boolean = false,
    /** true면 폴더를 펼치고 접는 트리로 본다. */
    val tree: Boolean = false,
    val expanded: Set<String> = emptySet(),
    /** 트리에서 이미 읽은 폴더 내용(키: 폴더 ID, 최상위는 ""). */
    val children: Map<String, List<LibraryEntry>> = emptyMap(),
    val loadingFolders: Set<String> = emptySet()
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val api = LibraryApi(application)
    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()
    val isConfigured: Boolean get() = api.isConfigured

    init { if (api.isConfigured) load(emptyList()) }

    private fun load(path: List<Pair<String, String>>) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val all = _state.value.showAll
            val tree = _state.value.tree
            runCatching { if (all) api.all() else api.list(if (tree) null else path.lastOrNull()?.second) }
                .onSuccess { l ->
                    _state.update {
                        it.copy(
                            path = if (all || tree) emptyList() else path, items = l.items, tags = l.tags, query = "", isLoading = false,
                            expanded = emptySet(), children = if (tree) mapOf("" to l.items) else emptyMap()
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(isLoading = false, error = e.message ?: "목록을 불러오지 못했습니다.") } }
        }
    }

    fun refresh() = if (_state.value.query.isBlank()) load(_state.value.path) else search(_state.value.query)

    fun search(query: String) {
        val q = query.trim()
        if (q.isBlank()) { load(_state.value.path); return }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, query = q) }
            runCatching { api.search(q) }
                .onSuccess { l -> _state.update { it.copy(items = l.items, tags = l.tags.ifEmpty { it.tags }, isLoading = false) } }
                .onFailure { e -> _state.update { it.copy(isLoading = false, error = e.message ?: "검색하지 못했습니다.") } }
        }
    }

    /** 0 = 폴더별(들어가서 보기), 1 = 트리, 2 = 모든 파일. */
    fun setView(mode: Int) {
        _state.update { it.copy(showAll = mode == 2, tree = mode == 1, items = emptyList()) }
        load(emptyList())
    }

    /** 트리에서 폴더를 펼치거나 접는다. 처음 펼칠 때만 내용을 읽는다. */
    fun toggleFolder(folder: LibraryEntry) {
        val s = _state.value
        if (folder.id in s.expanded) {
            _state.update { it.copy(expanded = it.expanded - folder.id) }
            return
        }
        _state.update { it.copy(expanded = it.expanded + folder.id) }
        if (folder.id in s.children) return
        viewModelScope.launch {
            _state.update { it.copy(loadingFolders = it.loadingFolders + folder.id) }
            runCatching { api.list(folder.id) }
                .onSuccess { l -> _state.update { it.copy(children = it.children + (folder.id to l.items)) } }
                .onFailure { e -> _state.update { it.copy(expanded = it.expanded - folder.id, error = e.message ?: "폴더를 열지 못했습니다.") } }
            _state.update { it.copy(loadingFolders = it.loadingFolders - folder.id) }
        }
    }

    fun clearSearch() = load(_state.value.path)
    fun enter(folder: LibraryEntry) = load(_state.value.path + (folder.name to folder.id))
    fun up(): Boolean {
        val path = _state.value.path
        if (path.isEmpty()) return false
        load(path.dropLast(1))
        return true
    }
    fun goTo(depth: Int) = load(_state.value.path.take(depth))

    /** 파일을 받아 캐시 파일로 돌려준다. 실패하면 null. */
    suspend fun fetch(entry: LibraryEntry): File? {
        _state.update { it.copy(busyId = entry.id) }
        val file = runCatching { api.download(entry) }
            .onFailure { e -> _state.update { it.copy(error = e.message ?: "내려받지 못했습니다.") } }
            .getOrNull()
        _state.update { it.copy(busyId = null) }
        return file
    }

    fun consumeError() = _state.update { it.copy(error = null) }
}

/** 자료실 탭: 구글 드라이브 폴더를 목록으로 보여 주고, 눌러서 열거나 기기에 저장한다. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(viewModel: LibraryViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingSave by remember { mutableStateOf<LibraryEntry?>(null) }
    var cachedForSave by remember { mutableStateOf<File?>(null) }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val file = cachedForSave
        if (uri != null && file != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            }.onSuccess { Toast.makeText(context, "저장했습니다.", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "저장하지 못했습니다.", Toast.LENGTH_SHORT).show() }
        }
        pendingSave = null
        cachedForSave = null
    }

    // 누르면 '다운로드' 폴더에 저장한다(안드로이드 10+는 바로, 그 아래 버전은 저장 위치를 묻는다).
    val download: (File, LibraryEntry) -> Unit = { file, entry ->
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val ok = runCatching { saveToDownloads(context, file, entry) }.getOrDefault(false)
            Toast.makeText(context, if (ok) "다운로드 폴더에 저장했습니다: ${entry.downloadName}" else "저장하지 못했습니다.", Toast.LENGTH_LONG).show()
        } else {
            cachedForSave = file; pendingSave = entry; saver.launch(entry.downloadName)
        }
    }

    androidx.compose.runtime.LaunchedEffect(state.error) {
        state.error?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); viewModel.consumeError() }
    }
    var queryText by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = state.query.isNotBlank() || state.path.isNotEmpty()) {
        if (state.query.isNotBlank()) { queryText = ""; viewModel.clearSearch() } else viewModel.up()
    }

    Column(modifier.fillMaxSize()) {
        LargeTitle("참고자료 모음") {
            IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, contentDescription = "새로고침") }
        }
        if (!viewModel.isConfigured) {
            CenterMessage("자료실 서버가 아직 연결되지 않았습니다.\n구글 드라이브 연결 설정 후 사용할 수 있습니다.")
            return@Column
        }
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SearchPill(
                value = queryText,
                onValueChange = { queryText = it; if (it.isBlank() && state.query.isNotBlank()) viewModel.clearSearch() },
                placeholder = "제목·설명·#태그 검색",
                onSearch = { viewModel.search(queryText) },
                modifier = Modifier.padding(horizontal = 16.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                outlined = true
            )
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillChip("폴더별", onPanel = true, selected = !state.showAll && !state.tree, onClick = { viewModel.setView(0) })
                PillChip("트리", onPanel = true, selected = state.tree, onClick = { viewModel.setView(1) })
                PillChip("모든 파일", onPanel = true, selected = state.showAll, onClick = { viewModel.setView(2) })
            }
            if (state.tags.isNotEmpty()) {
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.tags) { tag ->
                        PillChip("#$tag", onPanel = true, selected = state.query == "#$tag", onClick = {
                            queryText = "#$tag"; viewModel.search("#$tag")
                        })
                    }
                }
            }
        }
        // 현재 위치(폴더 경로)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.showAll && state.query.isBlank()) Text("모든 파일 ${state.items.size}건 · 최근순", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = AppColors.accent)
            else if (state.tree && state.query.isBlank()) Text("트리 보기 · 폴더를 눌러 펼치기", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = AppColors.accent)
            else if (state.query.isNotBlank()) Text("검색 결과 ${state.items.size}건 · ‘${state.query}’", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = AppColors.accent)
            else Text("전체", Modifier.clickable { viewModel.goTo(0) }, style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold, color = if (state.path.isEmpty()) AppColors.accent else MaterialTheme.colorScheme.secondary)
            if (state.query.isBlank() && !state.showAll && !state.tree) state.path.forEachIndexed { index, (name, _) ->
                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(name, Modifier.clickable { viewModel.goTo(index + 1) }, style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (index == state.path.lastIndex) AppColors.accent else MaterialTheme.colorScheme.secondary)
            }
        }
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = state.isLoading && state.items.isNotEmpty(),
            onRefresh = viewModel::refresh,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
        when {
            state.isLoading && state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
            }
            state.items.isEmpty() -> CenterMessage(if (state.query.isNotBlank()) "검색 결과가 없습니다." else "이 폴더에는 자료가 없습니다.", actionLabel = "새로고침", onAction = viewModel::refresh)
            else -> {
                val treeMode = state.tree && state.query.isBlank()
                val rows = remember(state.items, state.children, state.expanded, treeMode) {
                    if (!treeMode) state.items.map { it to 0 } else {
                        val out = ArrayList<Pair<LibraryEntry, Int>>()
                        fun walk(parent: String, depth: Int) {
                            for (e in state.children[parent].orEmpty()) {
                                out += e to depth
                                if (e.isFolder && e.id in state.expanded) walk(e.id, depth + 1)
                            }
                        }
                        walk("", 0)
                        out
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(rows, key = { it.first.id }) { (entry, depth) ->
                    Row(
                        Modifier.fillMaxWidth().then(
                            if (entry.isFolder) Modifier.clickable { if (treeMode) viewModel.toggleFolder(entry) else viewModel.enter(entry) } else Modifier
                        ).padding(start = (18 + depth * 22).dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            if (entry.isFolder) (if (treeMode && entry.id in state.expanded) Icons.Default.FolderOpen else Icons.Default.Folder) else Icons.Default.InsertDriveFile,
                            contentDescription = null,
                            tint = if (entry.isFolder) AppColors.accent else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (entry.description.isNotBlank()) Text(
                                entry.description, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            if (entry.tags.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                entry.tags.take(4).forEach { TagLabel(it) }
                            }
                            if (!entry.isFolder) Text(
                                listOf(entry.location, sizeText(entry.size), entry.modified.take(10)).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (entry.isFolder && treeMode) {
                            if (entry.id in state.loadingFolders) CircularProgressIndicator(Modifier.padding(horizontal = 14.dp).size(20.dp), strokeWidth = 2.dp)
                            else Icon(
                                if (entry.id in state.expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                        if (!entry.isFolder) {
                            if (state.busyId == entry.id) {
                                CircularProgressIndicator(Modifier.padding(horizontal = 18.dp).size(22.dp), strokeWidth = 2.dp)
                            } else {
                                val tint = MaterialTheme.colorScheme.onSurfaceVariant
                                IconButton(onClick = { scope.launch { viewModel.fetch(entry)?.let { download(it, entry) } } }, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Default.Download, contentDescription = "다운로드", tint = tint)
                                }
                                IconButton(onClick = { scope.launch { viewModel.fetch(entry)?.let { openFile(context, it, entry) } } }, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Default.Visibility, contentDescription = "보기", tint = tint)
                                }
                                IconButton(onClick = { scope.launch { viewModel.fetch(entry)?.let { shareFile(context, it, entry) } } }, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Default.Share, contentDescription = "공유", tint = tint)
                                }
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
                }
            }
        }
    }
        // 게시 문의(작고 눈에 띄지 않게, 누르면 메일 작성)
        Text(
            "파일게시 문의  hdlee73@gmail.com",
            modifier = Modifier.fillMaxWidth()
                .clickable {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:hdlee73@gmail.com"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
                .padding(horizontal = 18.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@androidx.annotation.RequiresApi(29)
private fun saveToDownloads(context: android.content.Context, file: File, entry: LibraryEntry): Boolean {
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, entry.downloadName)
        put(android.provider.MediaStore.Downloads.MIME_TYPE, entry.type.ifBlank { "application/octet-stream" })
        put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/FSS Insights")
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
    resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: return false
    return true
}

private fun openFile(context: android.content.Context, file: File, entry: LibraryEntry) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.articlefiles", file)
    // 새 작업(NEW_TASK)으로 열어 기본 앱이 이 앱 안이 아니라 자기 화면에서 뜨게 한다.
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, entry.type.ifBlank { "*/*" })
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "이 파일을 열 수 있는 앱이 없습니다.", Toast.LENGTH_LONG).show()
    }
}

private fun shareFile(context: android.content.Context, file: File, entry: LibraryEntry) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.articlefiles", file)
    val send = Intent(Intent.ACTION_SEND).setType(entry.type.ifBlank { "*/*" })
        .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, entry.name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(chooser) }
        .onFailure { Toast.makeText(context, "공유할 수 있는 앱이 없습니다.", Toast.LENGTH_LONG).show() }
}

private fun sizeText(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 * 1024 -> "${bytes / 1024 + 1}KB"
    else -> String.format(java.util.Locale.US, "%.1fMB", bytes / 1048576.0)
}
