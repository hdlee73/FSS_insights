package io.github.hdlee73.financenewsradar.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hdlee73.financenewsradar.model.BOARD_CATEGORIES
import io.github.hdlee73.financenewsradar.model.BoardFile
import io.github.hdlee73.financenewsradar.model.BoardPost
import io.github.hdlee73.financenewsradar.model.BoardSort
import io.github.hdlee73.financenewsradar.ui.theme.AppColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private const val ROUTE_LIST = 0
private const val ROUTE_DETAIL = 1
private const val ROUTE_WRITE = 2
private const val MAX_FILES = 5

private val RULES = "• 업무상 비밀, 미공개 정보, 검사·제재 관련 비공개 자료, 개인정보를 올리지 마세요.\n" +
    "• 앱을 쓰는 누구나 글을 볼 수 있고, 글쓴이는 별명으로만 표시됩니다.\n" +
    "• 내가 쓴 글·댓글만 지울 수 있습니다. 부적절한 글은 신고해 주세요(신고 3건이면 가려집니다).\n" +
    "• 첨부는 글당 5개, 파일당 10MB까지(이미지·PDF·오피스·한글 문서·텍스트)."

/** "감독 및 검사 팁" 게시판: 목록 → 글 보기 → 글쓰기. */
@Composable
fun BoardScreen(viewModel: BoardViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var route by rememberSaveable { mutableIntStateOf(ROUTE_LIST) }
    var postId by rememberSaveable { mutableLongStateOf(0L) }

    LaunchedEffect(state.error, state.message) {
        (state.error ?: state.message)?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeNotice()
        }
    }
    BackHandler(enabled = route != ROUTE_LIST) {
        if (route == ROUTE_DETAIL) viewModel.closeDetail()
        route = ROUTE_LIST
    }

    Box(modifier.fillMaxSize()) {
        if (!viewModel.isConfigured) {
            Column(Modifier.fillMaxSize()) {
                LargeTitle("감독·검사 팁")
                CenterMessage("게시판 서버가 아직 연결되지 않았습니다.\n뉴스 서버(Cloudflare Worker) 설정 후 사용할 수 있습니다.")
            }
            return@Box
        }
        when (route) {
            ROUTE_DETAIL -> {
                LaunchedEffect(postId) { if (state.detail?.post?.id != postId) viewModel.open(postId) }
                BoardDetailScreen(viewModel, postId, onBack = { viewModel.closeDetail(); route = ROUTE_LIST })
            }
            ROUTE_WRITE -> BoardWriteScreen(viewModel, onBack = { route = ROUTE_LIST }, onPosted = { id -> postId = id; route = ROUTE_DETAIL })
            else -> BoardListScreen(
                viewModel,
                onOpen = { id -> postId = id; route = ROUTE_DETAIL },
                onWrite = { route = ROUTE_WRITE }
            )
        }
    }
}

@Composable
private fun BoardListScreen(viewModel: BoardViewModel, onOpen: (Long) -> Unit, onWrite: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var queryText by rememberSaveable { mutableStateOf("") }
    var rulesOpen by rememberSaveable { mutableStateOf(false) }

    if (rulesOpen) {
        AlertDialog(
            onDismissRequest = { rulesOpen = false },
            title = { Text("게시판 이용 안내") },
            text = { Text(RULES, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { rulesOpen = false }) { Text("확인") } }
        )
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
            item(key = "title") {
                LargeTitle("감독·검사 팁") {
                    IconButton(onClick = { searching = !searching; if (!searching) { queryText = ""; viewModel.search("") } }) {
                        Icon(if (searching) Icons.Default.Close else Icons.Default.Search, contentDescription = "글 검색")
                    }
                }
            }
            item(key = "chips") {
                Column(Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (searching) {
                        SearchPill(
                            value = queryText,
                            onValueChange = { queryText = it },
                            placeholder = "제목·내용 검색",
                            onSearch = { viewModel.search(queryText) },
                            modifier = Modifier.padding(horizontal = 16.dp),
                            containerColor = MaterialTheme.colorScheme.surface,
                            outlined = true
                        )
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { PillChip("전체", onPanel = true, selected = state.sort == BoardSort.NEW && state.category == null, onClick = { viewModel.setSort(BoardSort.NEW) }) }
                        item { PillChip("인기", onPanel = true, selected = state.sort == BoardSort.POPULAR, onClick = { viewModel.setSort(BoardSort.POPULAR) }) }
                        items(BOARD_CATEGORIES) { c ->
                            PillChip(c, onPanel = true, selected = state.category == c && state.sort == BoardSort.NEW, onClick = { viewModel.setCategory(c) })
                        }
                        item { PillChip("내글", onPanel = true, selected = state.sort == BoardSort.MINE, onClick = { viewModel.setSort(BoardSort.MINE) }) }
                    }
                }
            }
            item(key = "notice") {
                Row(
                    Modifier.fillMaxWidth().clickable { rulesOpen = true }.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(shape = RoundedCornerShape(6.dp), color = AppColors.header) {
                        Text("필독", Modifier.padding(horizontal = 7.dp, vertical = 2.dp), color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                    Text("비밀·개인정보는 올리지 마세요 (이용 안내)", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            when {
                state.isLoading && state.posts.isEmpty() -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp) }
                }
                state.posts.isEmpty() -> item(key = "empty") {
                    CenterMessage(
                        if (state.query.isNotBlank()) "‘${state.query}’에 해당하는 글이 없습니다."
                        else if (state.sort == BoardSort.MINE) "내가 쓴 글이 없습니다."
                        else "아직 글이 없습니다.\n첫 글을 남겨 주세요.",
                        actionLabel = "새로고침",
                        onAction = viewModel::reload
                    )
                }
                else -> {
                    items(state.posts, key = { it.id }) { post ->
                        PostRow(post, viewModel, onClick = { onOpen(post.id) })
                        HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    if (state.canLoadMore) item(key = "more") {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            OutlinedButton(onClick = viewModel::loadMore) { Text("더 보기") }
                        }
                    }
                }
            }
        }
        SmallFloatingActionButton(
            onClick = onWrite,
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp).size(56.dp),
            shape = androidx.compose.foundation.shape.CircleShape,
            containerColor = AppColors.accent,
            contentColor = Color.White
        ) { Icon(Icons.Default.Edit, contentDescription = "글쓰기") }
    }
}

@Composable
private fun PostRow(post: BoardPost, viewModel: BoardViewModel, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TagLabel(post.category, Modifier.padding(top = 3.dp))
                Text(post.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                buildString {
                    append(post.nick).append(" · ").append(boardTime(post.created)).append(" · 조회 ").append(post.views)
                    if (post.attachments > 0) append(" · 첨부 ").append(post.attachments)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        post.thumb?.let { key ->
            val bitmap by produceState<android.graphics.Bitmap?>(null, key) { value = viewModel.image(key, "", 240) }
            Box(Modifier.size(58.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
        }
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.width(48.dp).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    post.comments.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (post.comments > 0) AppColors.accent else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("댓글", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun BackHeader(title: String, onBack: () -> Unit, trailing: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().background(AppColors.header).height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로", tint = Color.White) }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1)
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White) { trailing() }
    }
}

@Composable
private fun BoardDetailScreen(viewModel: BoardViewModel, postId: Long, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val detail = state.detail?.takeIf { it.post.id == postId }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var commentText by rememberSaveable { mutableStateOf("") }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmReport by rememberSaveable { mutableStateOf(false) }

    if (detail != null && confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("글을 삭제할까요?") },
            text = { Text("댓글과 첨부 파일도 함께 삭제됩니다.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.deletePost(detail.post.id, onBack) }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } }
        )
    }
    if (detail != null && confirmReport) {
        AlertDialog(
            onDismissRequest = { confirmReport = false },
            title = { Text("이 글을 신고할까요?") },
            text = { Text("비밀·개인정보 노출, 광고, 욕설 등 부적절한 글이면 신고해 주세요. 신고가 3건 쌓이면 글이 가려집니다.") },
            confirmButton = { TextButton(onClick = { confirmReport = false; viewModel.report(detail.post.id) }) { Text("신고") } },
            dismissButton = { TextButton(onClick = { confirmReport = false }) { Text("취소") } }
        )
    }

    Column(Modifier.fillMaxSize()) {
        BackHeader("게시글", onBack) {
            if (detail != null) {
                if (detail.post.mine) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "글 삭제") }
                else IconButton(onClick = { confirmReport = true }) { Icon(Icons.Default.Flag, contentDescription = "신고") }
            }
        }
        if (detail == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (state.detailLoading) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                else CenterMessage("글을 불러오지 못했습니다.", actionLabel = "목록으로", onAction = onBack)
            }
            return@Column
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "head") {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                        TagLabel(detail.post.category, Modifier.padding(top = 4.dp))
                        Text(detail.post.title, style = MaterialTheme.typography.titleLarge)
                    }
                    Text(
                        "${detail.post.nick} · ${boardTime(detail.post.created)} · 조회 ${detail.post.views}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SelectionContainer {
                    Text(detail.body, Modifier.fillMaxWidth().padding(18.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
            items(detail.files, key = { it.key }) { file ->
                if (file.isImage) {
                    val bitmap by produceState<android.graphics.Bitmap?>(null, file.key) { value = viewModel.image(file.key, file.name, 1200) }
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { scope.launch { viewModel.fetchFile(file)?.let { openFile(context, it, file) } } }
                    ) {
                        val b = bitmap
                        if (b != null) Image(b.asImageBitmap(), contentDescription = file.name, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                        else Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                    }
                } else {
                    Surface(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp).clickable {
                            scope.launch { viewModel.fetchFile(file)?.let { openFile(context, it, file) } }
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(sizeText(file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("열기", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            item(key = "band") { Spacer(Modifier.height(12.dp)); SectionBand() }
            item(key = "comments-title") { BlockHeader("댓글 ${detail.comments.size}", accent = true) }
            if (detail.comments.isEmpty()) item(key = "no-comments") {
                Text("첫 댓글을 남겨 보세요.", Modifier.padding(horizontal = 18.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            items(detail.comments, key = { "c${it.id}" }) { c ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.nick, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                        Text("  ${boardTime(c.created)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (c.mine) IconButton(onClick = { viewModel.deleteComment(detail.post.id, c.id) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "댓글 삭제", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    SelectionContainer { Text(c.body, style = MaterialTheme.typography.bodyMedium) }
                }
                HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = commentText,
                onValueChange = { commentText = it.take(1000) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("댓글을 입력하세요") },
                maxLines = 4,
                shape = RoundedCornerShape(22.dp)
            )
            IconButton(
                onClick = { viewModel.comment(detail.post.id, commentText) { commentText = "" } },
                enabled = commentText.isNotBlank()
            ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "댓글 등록", tint = if (commentText.isNotBlank()) AppColors.accent else MaterialTheme.colorScheme.outline) }
        }
    }
}

@Composable
private fun BoardWriteScreen(viewModel: BoardViewModel, onBack: () -> Unit, onPosted: (Long) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var category by rememberSaveable { mutableStateOf(BOARD_CATEGORIES.first()) }
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var nick by rememberSaveable { mutableStateOf(state.nick) }
    val files = remember { mutableStateListOf<PendingFile>() }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        for (uri in uris) {
            if (files.size >= MAX_FILES) { Toast.makeText(context, "첨부는 ${MAX_FILES}개까지입니다.", Toast.LENGTH_SHORT).show(); break }
            val file = viewModel.describe(uri) ?: continue
            val photo = file.name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp") }
            if (!photo && file.size > 10L * 1024 * 1024) { Toast.makeText(context, "${file.name}: 10MB를 넘어 올릴 수 없습니다.", Toast.LENGTH_SHORT).show(); continue }
            files += file
        }
    }
    val canSubmit = title.trim().length >= 2 && body.trim().length >= 2 && !state.submitting

    Column(Modifier.fillMaxSize()) {
        BackHeader("글쓰기", onBack) {
            TextButton(
                onClick = { viewModel.submit(category, title, body, nick, files.toList(), onPosted) },
                enabled = canSubmit
            ) { Text(if (state.submitting) "올리는 중…" else "등록", color = if (canSubmit) Color.White else Color(0x80FFFFFF), fontWeight = FontWeight.Bold) }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                Text(
                    "업무상 비밀·미공개 정보·개인정보는 올리지 마세요. 앱을 쓰는 누구나 볼 수 있습니다.",
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(BOARD_CATEGORIES) { c -> PillChip(c, selected = category == c, onClick = { category = c }) }
            }
            OutlinedTextField(nick, { nick = it.take(16) }, Modifier.fillMaxWidth(), label = { Text("별명 (비우면 익명)") }, singleLine = true)
            OutlinedTextField(title, { title = it.take(100) }, Modifier.fillMaxWidth(), label = { Text("제목") }, singleLine = true)
            OutlinedTextField(body, { body = it.take(5000) }, Modifier.fillMaxWidth(), label = { Text("내용") }, minLines = 8)
            files.forEachIndexed { index, f ->
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("  ${f.name}" + if (f.size > 0) "  (${sizeText(f.size)})" else "", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { files.removeAt(index) }) { Icon(Icons.Default.Close, contentDescription = "첨부 빼기", modifier = Modifier.size(18.dp)) }
                    }
                }
            }
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = files.size < MAX_FILES) {
                Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  사진·문서 첨부 (${files.size}/$MAX_FILES)")
            }
            if (state.submitting) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
        }
    }
}

private fun openFile(context: android.content.Context, file: java.io.File, meta: BoardFile) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.articlefiles", file)
    val mime = meta.type.substringBefore(';').ifBlank { "*/*" }
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "이 파일을 열 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show()
    }
}

private fun sizeText(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 * 1024 -> "${bytes / 1024 + 1}KB"
    else -> String.format(java.util.Locale.US, "%.1fMB", bytes / 1048576.0)
}

private val boardZone: ZoneId = ZoneId.of("Asia/Seoul")
private val boardDate: DateTimeFormatter = DateTimeFormatter.ofPattern("M.d HH:mm")

internal fun boardTime(millis: Long): String {
    val ago = System.currentTimeMillis() - millis
    return when {
        ago < 60_000 -> "방금"
        ago < 3_600_000 -> "${ago / 60_000}분 전"
        ago < 86_400_000 -> "${ago / 3_600_000}시간 전"
        else -> Instant.ofEpochMilli(millis).atZone(boardZone).format(boardDate)
    }
}
