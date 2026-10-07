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
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.sp
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
    val isUploading: Boolean = false,
    val tags: List<String> = emptyList(),
    /** 비어 있지 않으면 검색 결과 보기. */
    val query: String = ""
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
            runCatching { api.list(path.lastOrNull()?.second) }
                .onSuccess { l -> _state.update { it.copy(path = path, items = l.items, tags = l.tags, query = "", isLoading = false) } }
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

    /** 선택한 파일을 업로드 대기 폴더로 올린다. 끝나면 안내 문구를 돌려준다. */
    fun upload(uri: android.net.Uri, description: String, onDone: (String) -> Unit) {
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            _state.update { it.copy(isUploading = true) }
            val result = runCatching {
                var name = "파일"
                var size = -1L
                resolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                        c.getColumnIndex(android.provider.OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
                    }
                }
                api.upload(name, resolver.getType(uri).orEmpty(), size, description) { resolver.openInputStream(uri) }
            }
            _state.update { it.copy(isUploading = false) }
            onDone(result.fold({ "올렸습니다. 관리자가 확인한 뒤 자료실에 게시됩니다." }, { it.message ?: "올리지 못했습니다." }))
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }
}

/** 참고자료 탭: 구글 드라이브 폴더를 목록으로 보여 주고, 눌러서 열거나 기기에 저장한다. */
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

    var pickedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) pickedUri = uri }
    pickedUri?.let { uri ->
        UploadDialog(
            onDismiss = { pickedUri = null },
            onSubmit = { description ->
                pickedUri = null
                viewModel.upload(uri, description) { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            }
        )
    }

    androidx.compose.runtime.LaunchedEffect(state.error) {
        state.error?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); viewModel.consumeError() }
    }
    var queryText by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = state.query.isNotBlank() || state.path.isNotEmpty()) {
        if (state.query.isNotBlank()) { queryText = ""; viewModel.clearSearch() } else viewModel.up()
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        LargeTitle("참고자료") {
            if (viewModel.isConfigured) {
                if (state.isUploading) CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), strokeWidth = 2.dp, color = androidx.compose.ui.graphics.Color.White)
                else IconButton(onClick = { picker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Upload, contentDescription = "자료 올리기") }
            }
            IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, contentDescription = "새로고침") }
        }
        if (!viewModel.isConfigured) {
            CenterMessage("자료실 서버가 아직 연결되지 않았습니다.\n구글 드라이브 연결 설정 후 사용할 수 있습니다.")
            return@Column
        }
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(top = 10.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SearchPill(
                value = queryText,
                onValueChange = { queryText = it; if (it.isBlank() && state.query.isNotBlank()) viewModel.clearSearch() },
                placeholder = "제목·설명 검색",
                onSearch = { viewModel.search(queryText) },
                modifier = Modifier.padding(horizontal = 16.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                outlined = true
            )
            ContactNote(Modifier.padding(horizontal = 20.dp).padding(top = 0.dp))
        }
        // 현재 위치(폴더 경로)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.query.isNotBlank()) Text("검색 결과 ${state.items.size}건 · ‘${state.query}’", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = AppColors.accent)
            else Text("전체", Modifier.clickable { viewModel.goTo(0) }, style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold, color = if (state.path.isEmpty()) AppColors.accent else MaterialTheme.colorScheme.secondary)
            if (state.query.isBlank()) state.path.forEachIndexed { index, (name, _) ->
                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(name, Modifier.clickable { viewModel.goTo(index + 1) }, style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (index == state.path.lastIndex) AppColors.accent else MaterialTheme.colorScheme.secondary)
            }
        }
        when {
            state.isLoading && state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
            }
            state.items.isEmpty() -> CenterMessage(if (state.query.isNotBlank()) "검색 결과가 없습니다." else "이 폴더에는 자료가 없습니다.", actionLabel = "새로고침", onAction = viewModel::refresh)
            else -> LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                items(state.items, key = { it.id }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (entry.isFolder) viewModel.enter(entry)
                            else scope.launch { viewModel.fetch(entry)?.let { openFile(context, it, entry) } }
                        }.padding(start = 18.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            if (entry.isFolder) Icons.Default.Folder else Icons.Default.InsertDriveFile,
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
                        if (!entry.isFolder) {
                            if (state.busyId == entry.id) {
                                CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), strokeWidth = 2.dp)
                            } else {
                                IconButton(onClick = {
                                    scope.launch {
                                        viewModel.fetch(entry)?.let { cachedForSave = it; pendingSave = entry; saver.launch(entry.downloadName) }
                                    }
                                }) { Icon(Icons.Default.Download, contentDescription = "기기에 저장", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
    ScrollToTopButton(listState)
    }
}

private fun openFile(context: android.content.Context, file: File, entry: LibraryEntry) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.articlefiles", file)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, entry.type.ifBlank { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "이 파일을 열 수 있는 앱이 없습니다. 오른쪽 저장 버튼을 이용해 주세요.", Toast.LENGTH_LONG).show()
    }
}

private fun sizeText(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 * 1024 -> "${bytes / 1024 + 1}KB"
    else -> String.format(java.util.Locale.US, "%.1fMB", bytes / 1048576.0)
}

/** 검색창 바로 밑에 아주 작게 보이는 자료 게시 문의 안내(누르면 메일 작성). */
@Composable
private fun ContactNote(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Text(
        "자료 게시 문의 : hdlee73@gmail.com",
        modifier = modifier.clickable {
            runCatching {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:hdlee73@gmail.com")))
            }
        },
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 올릴 파일의 설명을 받는다. */
@Composable
private fun UploadDialog(onDismiss: () -> Unit, onSubmit: (description: String) -> Unit) {
    var description by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("자료 올리기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "올린 파일은 관리자가 확인한 뒤 참고자료에 게시됩니다. 20MB까지, PDF·문서·엑셀·파워포인트·한글·텍스트·이미지 파일을 올릴 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(description, { description = it.take(300) }, label = { Text("설명·#태그 (선택)") }, minLines = 2)
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(description.trim()) }) { Text("올리기") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}
