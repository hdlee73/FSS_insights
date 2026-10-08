package io.github.hdlee73.financenewsradar.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hdlee73.financenewsradar.ArticleReaderActivity
import io.github.hdlee73.financenewsradar.data.KOREA_INDEXES
import io.github.hdlee73.financenewsradar.model.AgencyId
import io.github.hdlee73.financenewsradar.model.NewsArticle
import io.github.hdlee73.financenewsradar.model.ReleaseItem
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val TAB_NEWS = 1
private const val TAB_MARKET = 2
private const val TAB_PRESS = 3   // 자료 탭(보도자료 구분 탭)
private val BRIEFING_PRESS = listOf(AgencyId.FSS, AgencyId.FSC)
private val briefingTime = DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREAN).withZone(ZoneId.of("Asia/Seoul"))

/** 앱을 열면 보이는 첫 화면: 새 보도자료, 저장한 키워드의 새 기사, 주요 시장 지표를 한 화면에 모은다. */
@Composable
fun BriefingScreen(
    newsViewModel: NewsViewModel,
    releasesViewModel: ReleasesViewModel,
    marketViewModel: MarketViewModel,
    onOpenTab: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val news by newsViewModel.state.collectAsStateWithLifecycle()
    val releases by releasesViewModel.state.collectAsStateWithLifecycle()
    val market by marketViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        releasesViewModel.preload(BRIEFING_PRESS)
        marketViewModel.refresh()
    }

    val pressItems = BRIEFING_PRESS.flatMap { releases.of(it).latest }
        .sortedByDescending { it.date }
        .take(5)
    val newLinks = BRIEFING_PRESS.flatMap { releases.of(it).newLinks }.toSet()
    val pressLoading = BRIEFING_PRESS.any { releases.of(it).isLoading } && pressItems.isEmpty()
    val keywordArticles = news.articles
        .filter { it.matchedKeywords.isNotEmpty() }
        .ifEmpty { news.articles }
        .sortedByDescending { it.publishedAt }
        .take(6)

    Column(modifier.fillMaxSize()) {
        LargeTitle("오늘의 브리핑") {
            IconButton(onClick = {
                BRIEFING_PRESS.forEach(releasesViewModel::refresh)
                newsViewModel.refreshHome()
                marketViewModel.refresh()
            }) { Icon(Icons.Default.Refresh, contentDescription = "새로고침") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            BlockHeader(
                if (newLinks.isEmpty()) "새 보도자료" else "새 보도자료 · ${newLinks.size}건 NEW",
                accent = newLinks.isNotEmpty()
            ) { TextButton(onClick = { onOpenTab(TAB_PRESS) }) { Text("더 보기") } }
            when {
                pressItems.isNotEmpty() -> pressItems.forEachIndexed { i, item ->
                    if (i > 0) RowDivider()
                    PressLine(item, item.link in newLinks) { openItem(context, item) }
                }
                pressLoading -> Hint("불러오는 중…")
                else -> Hint("보도자료를 불러오지 못했습니다. 새로고침을 눌러 주세요.")
            }
            SectionBand()

            BlockHeader("저장한 키워드 새 기사") { TextButton(onClick = { onOpenTab(TAB_NEWS) }) { Text("더 보기") } }
            Text(
                news.settings.keywords.joinToString(" · ") { "#$it" },
                modifier = Modifier.padding(horizontal = 20.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            when {
                !news.isHome -> {
                    Hint("뉴스 탭에서 검색 중이라 맞춤 뉴스를 대신 불러와야 합니다.")
                    TextButton(onClick = newsViewModel::refreshHome, modifier = Modifier.padding(horizontal = 12.dp)) { Text("맞춤 뉴스 불러오기") }
                }
                keywordArticles.isNotEmpty() -> keywordArticles.forEachIndexed { i, article ->
                    if (i > 0) RowDivider()
                    ArticleLine(article) { ArticleReaderActivity.open(context, article.link, article.title) }
                }
                news.isLoading -> Hint("불러오는 중…")
                else -> Hint("저장한 키워드의 새 기사가 없습니다.")
            }
            SectionBand()

            BlockHeader("주요 시장 지표") { TextButton(onClick = { onOpenTab(TAB_MARKET) }) { Text("더 보기") } }
            val instruments = (KOREA_INDEXES + market.panel).distinctBy { it.symbol }
            val rows = instruments.mapNotNull { item -> market.quotes[item.symbol]?.let { item to it } }
            if (rows.isEmpty()) {
                Hint(if (market.isRefreshing) "시세 연결 중…" else "시세를 불러오지 못했습니다.")
            } else {
                rows.forEachIndexed { i, (item, quote) ->
                    if (i > 0) RowDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenTab(TAB_MARKET) }.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(item.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Column(horizontalAlignment = Alignment.End) {
                            Text(priceText(item, quote.price), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            deltaText(item, quote)?.let {
                                Text(it, style = MaterialTheme.typography.labelMedium, color = changeColor(quote.change))
                            }
                        }
                    }
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PressLine(item: ReleaseItem, isNew: Boolean, onOpen: () -> Unit) {
    val meta = listOfNotNull(item.label, item.dateText()).joinToString(" · ")
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (isNew) NewBadge()
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ArticleLine(article: NewsArticle, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TagLabel(article.source.take(12), accent = article.isPriority)
            Text("  ·  ${briefingTime.format(article.publishedAt)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(article.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (article.matchedKeywords.isNotEmpty()) {
            Text(article.matchedKeywords.joinToString(" · ") { "#$it" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
