package io.github.hdlee73.financenewsradar.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hdlee73.financenewsradar.ArticleReaderActivity
import io.github.hdlee73.financenewsradar.data.AgencySources
import io.github.hdlee73.financenewsradar.model.AgencyGroup
import io.github.hdlee73.financenewsradar.model.AgencyId
import io.github.hdlee73.financenewsradar.model.ReleaseItem
import io.github.hdlee73.financenewsradar.model.UsefulLink
import java.time.format.DateTimeFormatter

private val releaseDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")

/** PDF는 외부 앱으로, 그 외 웹 문서는 앱 안 보기 화면(PDF 저장·공유 지원)으로 연다. */
internal fun openRelease(context: Context, title: String, url: String) {
    val isPdf = url.substringBefore('?').endsWith(".pdf", ignoreCase = true)
    if (isPdf) openExternal(context, url) else ArticleReaderActivity.open(context, url, title)
}

internal fun openExternal(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { Toast.makeText(context, "링크를 열 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show() }
}

/** 금융당국 보도자료 / 연구소 최근자료 탭 공통 화면. */
@Composable
fun AgencyTab(group: AgencyGroup, viewModel: ReleasesViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val agencies = remember(group) { AgencyId.entries.filter { it.group == group } }
    LaunchedEffect(group) { agencies.forEach(viewModel::ensureLatest) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "intro") {
            Text(
                if (group == AgencyGroup.PRESS) "각 기관의 최근 보도자료 5건입니다. 제목을 누르면 원문이 열립니다."
                else "각 기관의 최근 보고서 5건입니다. 제목을 누르면 원문이 열립니다.",
                modifier = Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        items(agencies, key = { it.name }) { agency ->
            AgencySection(
                agency = agency,
                state = state.of(agency),
                onRefresh = { viewModel.refresh(agency) },
                onSearch = { viewModel.search(agency, it) },
                onLoadMore = { viewModel.loadMore(agency) },
                onClearSearch = { viewModel.clearSearch(agency) }
            )
        }
    }
}

@Composable
private fun AgencySection(
    agency: AgencyId,
    state: AgencyUiState,
    onRefresh: () -> Unit,
    onSearch: (String) -> Unit,
    onLoadMore: () -> Unit,
    onClearSearch: () -> Unit
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var text by rememberSaveable(agency.name) { mutableStateOf("") }
    val runSearch = {
        if (text.isNotBlank()) {
            keyboard?.hide()
            focus.clearFocus()
            onSearch(text)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${agency.label} ${agency.itemNoun}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(onClick = { openRelease(context, "${agency.label} ${agency.itemNoun}", agency.homeUrl) }) {
                    Text("사이트에서 보기", style = MaterialTheme.typography.labelMedium)
                }
                IconButton(onClick = onRefresh, enabled = !state.isLoading) {
                    Icon(Icons.Default.Refresh, contentDescription = "${agency.label} 새로고침")
                }
            }

            if (!state.inSearch) {
                when {
                    state.isLoading && state.latest.isEmpty() -> LoadingRow()
                    state.error != null && state.latest.isEmpty() -> Text(
                        state.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
                    )
                    else -> state.latest.forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider()
                        ReleaseRow(item)
                    }
                }
            }

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("과거 ${agency.itemNoun} 제목 검색", style = MaterialTheme.typography.bodyMedium) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                    shape = RoundedCornerShape(12.dp)
                )
                Button(onClick = runSearch, enabled = text.isNotBlank() && !state.isSearching, shape = RoundedCornerShape(14.dp)) {
                    Text("검색")
                }
            }

            if (state.inSearch) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "‘${state.searchQuery}’ 검색 결과 ${state.results.size}건",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = { text = ""; onClearSearch() }) {
                        Text("최근 5건 보기", style = MaterialTheme.typography.labelMedium)
                    }
                }
                state.results.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider()
                    ReleaseRow(item)
                }
                if (state.isSearching) LoadingRow()
                state.searchError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (!state.isSearching && state.searchError == null && state.results.isEmpty()) {
                    Text(
                        "앱이 읽은 범위에서 일치하는 항목이 없습니다. 아래 버튼으로 사이트에서 직접 검색해 보세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.canLoadMore && !state.isSearching) {
                        OutlinedButton(onClick = onLoadMore) { Text("더 오래된 항목 찾기") }
                    }
                    OutlinedButton(onClick = {
                        openRelease(context, "${agency.label} 검색", AgencySources.siteSearchUrl(agency, state.searchQuery))
                    }) { Text("사이트에서 직접 검색") }
                }
            }
        }
    }
}

@Composable
private fun LoadingRow() {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp))
    }
}

@Composable
private fun ReleaseRow(item: ReleaseItem) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { openRelease(context, item.title, item.link) }
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
        item.date?.let {
            Text(it.format(releaseDateFormat), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 금융 사이트 링크 모음. 추가·수정·삭제·기본값 복원을 지원한다. */
@Composable
fun UsefulLinksDialog(
    links: List<UsefulLink>,
    onDismiss: () -> Unit,
    onSave: (List<UsefulLink>) -> Unit,
    onReset: () -> Unit
) {
    val context = LocalContext.current
    var editingIndex by remember { mutableStateOf<Int?>(null) }   // -1 = 새 항목
    var deleteIndex by remember { mutableStateOf<Int?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "닫기") }
                    Text("금융 사이트", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { editingIndex = -1 }) { Icon(Icons.Default.Add, contentDescription = "사이트 추가") }
                }
                Text(
                    "누르면 브라우저로 열립니다. 오른쪽 아이콘으로 수정·삭제할 수 있습니다.",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                    if (links.isEmpty()) {
                        item {
                            Text(
                                "등록된 사이트가 없습니다. 오른쪽 위 + 버튼으로 추가하거나 기본값을 복원하세요.",
                                modifier = Modifier.padding(24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(links.size, key = { "${it}_${links[it].url}" }) { index ->
                        val link = links[index]
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { openExternal(context, link.url) }.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(link.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(link.url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { editingIndex = index }) { Icon(Icons.Default.Edit, contentDescription = "${link.name} 수정") }
                            IconButton(onClick = { deleteIndex = index }) { Icon(Icons.Default.Delete, contentDescription = "${link.name} 삭제") }
                        }
                        HorizontalDivider()
                    }
                    item {
                        TextButton(onClick = { confirmReset = true }, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                            Text("기본 10개 사이트로 복원")
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    editingIndex?.let { index ->
        val existing = links.getOrNull(index)
        LinkEditDialog(
            initial = existing,
            onDismiss = { editingIndex = null },
            onConfirm = { edited ->
                val next = if (existing == null) links + edited else links.toMutableList().also { it[index] = edited }
                onSave(next)
                editingIndex = null
            }
        )
    }
    deleteIndex?.let { index ->
        val target = links.getOrNull(index)
        if (target == null) deleteIndex = null else AlertDialog(
            onDismissRequest = { deleteIndex = null },
            title = { Text("사이트 삭제") },
            text = { Text("‘${target.name}’을(를) 목록에서 삭제할까요?") },
            confirmButton = { TextButton(onClick = { onSave(links.filterIndexed { i, _ -> i != index }); deleteIndex = null }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { deleteIndex = null }) { Text("취소") } }
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("기본값 복원") },
            text = { Text("지금까지 추가·수정·삭제한 내용이 사라지고 기본 10개 사이트로 돌아갑니다.") },
            confirmButton = { TextButton(onClick = { onReset(); confirmReset = false }) { Text("복원") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("취소") } }
        )
    }
}

@Composable
private fun LinkEditDialog(initial: UsefulLink?, onDismiss: () -> Unit, onConfirm: (UsefulLink) -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    val normalized = ReleasesViewModel.normalizeUrl(url)
    val valid = name.isNotBlank() && runCatching { Uri.parse(normalized).host?.contains('.') == true }.getOrDefault(false)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "사이트 추가" else "사이트 수정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("이름") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("주소 (예: www.fss.or.kr)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onConfirm(UsefulLink(name.trim(), normalized)) }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}
