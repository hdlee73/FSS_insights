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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hdlee73.financenewsradar.data.AgencySources
import io.github.hdlee73.financenewsradar.model.AgencyGroup
import io.github.hdlee73.financenewsradar.model.AgencyId
import io.github.hdlee73.financenewsradar.model.CustomInstitute
import io.github.hdlee73.financenewsradar.model.ReleaseItem
import io.github.hdlee73.financenewsradar.model.UsefulLink
import java.time.format.DateTimeFormatter

private val releaseDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")

/** 보도자료·연구자료 모두 원문 사이트를 외부 브라우저로 바로 연다. */
internal fun openItem(context: Context, item: ReleaseItem) = openSite(context, item.agency, item.link, item.title)

internal fun openSite(context: Context, agency: AgencyId, url: String, title: String) = openSite(context, agency.group, url, title)

internal fun openSite(context: Context, group: AgencyGroup, url: String, title: String) {
    openExternal(context, url)
}

/** KIF처럼 "2026-09"(년-월)만 알 수 있는 항목은 일(日)을 빼고 보여 준다. */
internal fun ReleaseItem.dateText(): String? = date?.format(
    if (agency == AgencyId.KIF) DateTimeFormatter.ofPattern("yyyy.MM") else releaseDateFormat
)

internal fun openExternal(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { Toast.makeText(context, "링크를 열 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show() }
}

/** 메신저에 붙여 넣기 좋은 "[기관] 제목 (날짜)\n링크" 형식으로 공유한다. */
internal fun shareItem(context: Context, item: ReleaseItem) {
    val date = item.dateText()?.let { " ($it)" }.orEmpty()
    val text = "[${item.label}] ${item.title}$date\n${item.link}"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "공유")) }
}

/** 금융당국 보도자료 / 연구소 최근자료 탭 공통 화면: 기관별 밑줄 탭 + 검색 + 최근 5건 + 저장함. */
@Composable
fun ReleasesScreen(group: AgencyGroup, viewModel: ReleasesViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val agencies = remember(group) { AgencyId.entries.filter { it.group == group && it != AgencyId.CUSTOM } }
    val institutes = if (group == AgencyGroup.RESEARCH) state.institutes else emptyList()
    var selected by rememberSaveable(group.name) { mutableIntStateOf(0) }
    var clearTick by remember { mutableIntStateOf(0) }
    var showSaved by rememberSaveable(group.name + "-saved") { mutableStateOf(false) }
    var addOpen by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<CustomInstitute?>(null) }
    if (selected >= agencies.size + institutes.size) selected = 0
    val agency = if (showSaved) null else agencies.getOrNull(selected)
    val institute = if (showSaved) null else institutes.getOrNull(selected - agencies.size)
    val savedLinks = state.savedLinks
    LaunchedEffect(agency) { agency?.let(viewModel::ensureLatest) }
    LaunchedEffect(institute?.url) { institute?.let(viewModel::ensureCustom) }

    if (addOpen) {
        AddInstituteDialog(
            onDismiss = { addOpen = false },
            onAdd = { name, url ->
                viewModel.addInstitute(name, url)
                addOpen = false
                showSaved = false
                selected = agencies.size + institutes.size
            }
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("${target.name} 삭제") },
            text = { Text("이 연구소를 목록에서 뺍니다.") },
            confirmButton = {
                TextButton(onClick = { viewModel.removeInstitute(target); deleteTarget = null; selected = 0 }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("취소") } }
        )
    }

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "title") {
            LargeTitle(group.label) {
                if (group == AgencyGroup.RESEARCH) {
                    IconButton(onClick = { addOpen = true }) {
                        Icon(Icons.Default.Add, contentDescription = "연구소 추가")
                    }
                }
                IconButton(onClick = { showSaved = !showSaved }) {
                    Icon(
                        if (showSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = if (showSaved) "저장함 닫기" else "저장함 열기",
                        tint = if (showSaved) androidx.compose.ui.graphics.Color(0xFFFFB38F) else androidx.compose.ui.graphics.Color.White
                    )
                }
            }
        }
        item(key = "tabs") {
            UnderlineTabs(
                agencies.map { it.shortLabel } + institutes.map { it.name },
                if (showSaved) -1 else selected,
                { selected = it; showSaved = false }
            )
        }
        if (showSaved) {
            val saved = state.saved.filter { it.agency.group == group }
            item(key = "saved-label") { SectionLabel("저장한 ${if (group == AgencyGroup.PRESS) "보도자료" else "보고서"} ${saved.size}건") }
            if (saved.isEmpty()) {
                item(key = "saved-empty") { CenterMessage("저장한 자료가 없습니다.\n목록의 북마크 버튼을 눌러 담아 두세요.") }
            }
            items(saved, key = { "saved-${it.link}" }) { item ->
                ReleaseRow(
                    item = item, isNew = false, isSaved = true, showAgency = true,
                    onOpen = { openItem(context, item) },
                    onToggleSaved = { viewModel.toggleSaved(item) },
                    onShare = { shareItem(context, item) }
                )
                RowDivider()
            }
        } else if (agency != null) {
            agencyItems(
                key = agency.name,
                name = agency.label,
                shortName = agency.shortLabel,
                noun = agency.itemNoun,
                group = agency.group,
                homeUrl = agency.homeUrl,
                searchable = group == AgencyGroup.PRESS && AgencySources.of(agency).deepSearch,
                latestCount = agency.latestCount,
                siteSearchUrl = { AgencySources.siteSearchUrl(agency, it) },
                state = state.of(agency),
                savedLinks = savedLinks,
                clearTick = clearTick,
                context = context,
                onRefresh = { viewModel.refresh(agency) },
                onSearch = { viewModel.search(agency, it) },
                onLoadMore = { viewModel.loadMore(agency) },
                onClear = { clearTick++; viewModel.clearSearch(agency) },
                onToggleSaved = viewModel::toggleSaved,
                onDelete = null
            )
        } else if (institute != null) {
            agencyItems(
                key = "custom-${institute.url}",
                name = institute.name,
                shortName = institute.name,
                noun = "자료",
                group = AgencyGroup.RESEARCH,
                homeUrl = institute.url,
                searchable = false,
                latestCount = 20,
                siteSearchUrl = { query ->
                    val host = runCatching { java.net.URI(institute.url).host }.getOrNull().orEmpty()
                    "https://www.google.com/search?q=site%3A$host+" + java.net.URLEncoder.encode(query, "UTF-8")
                },
                state = state.ofCustom(institute.url),
                savedLinks = savedLinks,
                clearTick = clearTick,
                context = context,
                onRefresh = { viewModel.refreshCustom(institute) },
                onSearch = { viewModel.searchCustom(institute, it) },
                onLoadMore = {},
                onClear = { clearTick++; viewModel.clearCustomSearch(institute) },
                onToggleSaved = viewModel::toggleSaved,
                onDelete = { deleteTarget = institute }
            )
        }
    }
}

/** 연구소 이름과 목록 주소를 받아 추가한다. 목록은 앱이 해당 페이지에서 직접 읽는다. */
@Composable
private fun AddInstituteDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("연구소 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("연구소 이름") }, singleLine = true)
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("보고서 목록 주소(URL)") }, singleLine = true)
                Text(
                    "보고서·간행물 목록이 보이는 페이지 주소를 넣으면, 앱이 그 페이지에서 최근 자료를 읽어 옵니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && url.isNotBlank(), onClick = { onAdd(name, url) }) { Text("추가") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

private fun LazyListScope.agencyItems(
    key: String,
    name: String,
    shortName: String,
    noun: String,
    group: AgencyGroup,
    homeUrl: String,
    searchable: Boolean,
    latestCount: Int,
    siteSearchUrl: (String) -> String,
    state: AgencyUiState,
    savedLinks: Set<String>,
    clearTick: Int,
    context: Context,
    onRefresh: () -> Unit,
    onSearch: (String) -> Unit,
    onLoadMore: () -> Unit,
    onClear: () -> Unit,
    onToggleSaved: (ReleaseItem) -> Unit,
    onDelete: (() -> Unit)?
) {
    item(key = "site-link-$key") {
        Surface(
            onClick = { openSite(context, group, homeUrl, "$name $noun") },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp)
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (searchable) "$name 사이트에서 전체 목록 보기" else "$name 사이트에서 찾기",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
    if (searchable) item(key = "search-$key") {
        AgencySearchBar(key = key, shortName = shortName, noun = noun, clearTick = clearTick, enabled = !state.isSearching, onSearch = onSearch)
    }

    if (!state.inSearch) {
        item(key = "latest-label") {
            SectionLabel("최근 ${latestCount}건") {
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "$name 삭제", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onRefresh, enabled = !state.isLoading) {
                    Icon(Icons.Default.Refresh, contentDescription = "$name 새로고침", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        when {
            state.isLoading && state.latest.isEmpty() -> item(key = "latest-loading") { LoadingBlock() }
            state.error != null && state.latest.isEmpty() -> item(key = "latest-error") {
                CenterMessage(
                    state.error, isError = true, actionLabel = "사이트에서 보기",
                    onAction = { openSite(context, group, homeUrl, "$name $noun") }
                )
            }
            else -> items(state.latest, key = { "latest-${it.link}" }) { item ->
                ReleaseRow(
                    item = item, isNew = item.link in state.newLinks, isSaved = item.link in savedLinks, showAgency = false,
                    onOpen = { openItem(context, item) },
                    onToggleSaved = { onToggleSaved(item) },
                    onShare = { shareItem(context, item) }
                )
                RowDivider()
            }
        }
    } else {
        item(key = "result-label") {
            SectionLabel("‘${state.searchQuery}’ 검색 결과 ${state.results.size}건") {
                TextButton(onClick = onClear) { Text("지우기") }
            }
        }
        items(state.results, key = { "result-${it.link}" }) { item ->
            ReleaseRow(
                item = item, isNew = false, isSaved = item.link in savedLinks, showAgency = false,
                onOpen = { openItem(context, item) },
                onToggleSaved = { onToggleSaved(item) },
                onShare = { shareItem(context, item) }
            )
            RowDivider()
        }
        if (state.isSearching) item(key = "result-loading") { LoadingBlock() }
        state.searchError?.let { message -> item(key = "result-error") { CenterMessage(message, isError = true) } }
        if (!state.isSearching && state.searchError == null && state.results.isEmpty()) {
            item(key = "result-empty") { CenterMessage("앱이 읽은 범위에서 일치하는 ${noun}이 없습니다.") }
        }
        item(key = "result-actions") {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.canLoadMore && !state.isSearching) {
                    OutlinedButton(onClick = onLoadMore) { Text("더 오래된 항목 찾기") }
                }
                TextButton(onClick = {
                    openSite(context, group, siteSearchUrl(state.searchQuery), "$name 검색")
                }) { Text("사이트에서 직접 검색") }
            }
        }
    }
}

@Composable
private fun AgencySearchBar(key: String, shortName: String, noun: String, clearTick: Int, enabled: Boolean, onSearch: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var text by rememberSaveable(key, clearTick) { mutableStateOf("") }
    val run = {
        if (text.isNotBlank() && enabled) {
            keyboard?.hide()
            focus.clearFocus()
            onSearch(text)
        }
    }
    SearchPill(
        value = text,
        onValueChange = { text = it },
        placeholder = "$shortName 과거 $noun 제목 검색",
        onSearch = { run() },
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        description = "$shortName $noun 검색어"
    )
}

@Composable
private fun LoadingBlock() {
    Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
    }
}

@Composable
private fun ReleaseRow(
    item: ReleaseItem,
    isNew: Boolean,
    isSaved: Boolean,
    showAgency: Boolean,
    onOpen: () -> Unit,
    onToggleSaved: () -> Unit,
    onShare: () -> Unit
) {
    val meta = listOfNotNull(item.label.takeIf { showAgency }, item.dateText()).joinToString(" · ")
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 20.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isNew) NewBadge()
                if (meta.isNotBlank()) {
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        IconButton(onClick = onToggleSaved, modifier = Modifier.size(40.dp)) {
            Icon(
                if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                contentDescription = if (isSaved) "저장함에서 빼기" else "저장함에 담기",
                modifier = Modifier.size(22.dp),
                tint = if (isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onShare, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Share, contentDescription = "제목과 링크 공유", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 참고사이트 탭: 사용자가 추가·수정·삭제·복원할 수 있는 금융 사이트 링크 목록. */
@Composable
fun SitesScreen(links: List<UsefulLink>, onSave: (List<UsefulLink>) -> Unit, onReset: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var editMode by rememberSaveable { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }   // -1 = 새 항목
    var deleteIndex by remember { mutableStateOf<Int?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "title") {
            LargeTitle("금융관련 주요사이트") {
                TextButton(onClick = { editMode = !editMode }) { Text(if (editMode) "완료" else "편집", fontWeight = FontWeight.SemiBold) }
                IconButton(onClick = { editingIndex = -1 }) { Icon(Icons.Default.Add, contentDescription = "사이트 추가") }
            }
        }
        item(key = "hint") {
            Text(
                if (editMode) "오른쪽 아이콘으로 수정·삭제하세요." else "누르면 브라우저로 열립니다. ‘편집’에서 수정·삭제할 수 있습니다.",
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (links.isEmpty()) {
            item(key = "empty") { CenterMessage("등록된 사이트가 없습니다.\n+ 버튼으로 추가하거나 기본값을 복원하세요.") }
        }
        items(links.size, key = { "site-$it-${links[it].url}" }) { index ->
            val link = links[index]
            SiteRow(
                link = link,
                editMode = editMode,
                onOpen = { openExternal(context, link.url) },
                onEdit = { editingIndex = index },
                onDelete = { deleteIndex = index }
            )
            RowDivider(inset = 76.dp)
        }
        item(key = "reset") {
            TextButton(onClick = { confirmReset = true }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("기본 사이트로 복원")
            }
        }
    }

    editingIndex?.let { index ->
        val existing = links.getOrNull(index)
        LinkEditDialog(
            initial = existing,
            onDismiss = { editingIndex = null },
            onConfirm = { edited ->
                onSave(if (existing == null) links + edited else links.toMutableList().also { it[index] = edited })
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
            text = { Text("지금까지 추가·수정·삭제한 내용이 사라지고 기본 사이트로 돌아갑니다.") },
            confirmButton = { TextButton(onClick = { onReset(); confirmReset = false }) { Text("복원") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("취소") } }
        )
    }
}

@Composable
private fun SiteRow(link: UsefulLink, editMode: Boolean, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val host = remember(link.url) { runCatching { Uri.parse(link.url).host.orEmpty().removePrefix("www.") }.getOrDefault("") }
    val initial = remember(link.name) { link.name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•" }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !editMode, onClick = onOpen).padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(initial, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(link.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(host.ifBlank { link.url }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (editMode) {
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "${link.name} 수정", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "${link.name} 삭제", tint = MaterialTheme.colorScheme.error) }
        } else {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
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
