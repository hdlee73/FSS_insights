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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hdlee73.financenewsradar.ArticleReaderActivity
import io.github.hdlee73.financenewsradar.data.KOREA_INDEXES
import io.github.hdlee73.financenewsradar.model.AgencyId
import io.github.hdlee73.financenewsradar.model.NewsArticle
import io.github.hdlee73.financenewsradar.model.ReleaseItem
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import io.github.hdlee73.financenewsradar.data.Instrument
import io.github.hdlee73.financenewsradar.data.IntradaySeries
import io.github.hdlee73.financenewsradar.data.Quote
import io.github.hdlee73.financenewsradar.data.Greeting
import io.github.hdlee73.financenewsradar.data.STAT_CATALOG
import io.github.hdlee73.financenewsradar.data.StatItem
import io.github.hdlee73.financenewsradar.data.periodLabel
import io.github.hdlee73.financenewsradar.data.Weather
import io.github.hdlee73.financenewsradar.data.WeatherClient
import io.github.hdlee73.financenewsradar.ui.theme.AppColors
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val TAB_NEWS = 1
private const val TAB_MARKET = 2
private const val TAB_PRESS = 3   // 자료 탭(보도자료 구분 탭)
private val BRIEFING_PRESS = listOf(AgencyId.FSS, AgencyId.FSC)
private val BRIEFING_RESEARCH = listOf(AgencyId.KCMI, AgencyId.KIF, AgencyId.IOSCO)
private val SEOUL = ZoneId.of("Asia/Seoul")
private val dateFormat = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)
private val briefingTime = DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREAN).withZone(ZoneId.of("Asia/Seoul"))

/** 앱을 열면 보이는 첫 화면: 날짜·날씨·인사, 시장 지표, 주요 기사, 최근 보도자료, 최근 1주일 연구리포트. */
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
    var weather by remember { mutableStateOf<Weather?>(null) }
    var weatherTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        releasesViewModel.preload(BRIEFING_PRESS + BRIEFING_RESEARCH)
        marketViewModel.refresh()
    }
    LaunchedEffect(releases.institutes) { releases.institutes.forEach(releasesViewModel::ensureCustom) }
    LaunchedEffect(weatherTick) { WeatherClient.fetch()?.let { weather = it } }

    val pressItems = BRIEFING_PRESS.flatMap { releases.of(it).latest }
        .sortedByDescending { it.date }
        .take(5)
    val newLinks = BRIEFING_PRESS.flatMap { releases.of(it).newLinks }.toSet()
    val pressLoading = BRIEFING_PRESS.any { releases.of(it).isLoading } && pressItems.isEmpty()
    val articles = news.articles
        .filter { it.matchedKeywords.isNotEmpty() }
        .ifEmpty { news.articles }
        .sortedByDescending { it.publishedAt }
        .take(5)
    val weekAgo = LocalDate.now(SEOUL).minusDays(7)
    val researchItems = (BRIEFING_RESEARCH.flatMap { releases.of(it).latest } + releases.institutes.flatMap { releases.ofCustom(it.url).latest })
        .filter { item -> item.date?.let { !it.isBefore(weekAgo) } == true }
        .distinctBy { it.link }
        .sortedByDescending { it.date }
        .take(5)

    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            BriefingHeader(weather) {
                BRIEFING_PRESS.forEach(releasesViewModel::refresh)
                BRIEFING_RESEARCH.forEach(releasesViewModel::refresh)
                newsViewModel.refreshHome()
                marketViewModel.refresh()
                weatherTick++
            }

            SectionTitle("시장 지표", "한눈에 보는 오늘 시장") { onOpenTab(TAB_MARKET) }
            val tiles = market.indexes.briefingItems
                .mapNotNull { item -> market.quotes[item.symbol]?.let { item to it } }
            if (tiles.isEmpty()) {
                Hint(if (market.isRefreshing) "시세 연결 중…" else "시세를 불러오지 못했습니다.")
            } else {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    tiles.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { (item, quote) ->
                                MarketTile(item, quote, market.intraday[item.symbol], Modifier.weight(1f)) { onOpenTab(TAB_MARKET) }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            val keyStats = STAT_CATALOG.mapNotNull { def -> market.stats.firstOrNull { it.id == def.id && def.id in market.statSelection.briefing } }
            if (keyStats.isNotEmpty()) {
                SectionTitle("주요 금융 통계", "한국은행 ECOS") { onOpenTab(TAB_MARKET) }
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    keyStats.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { StatTile(it, Modifier.weight(1f)) { onOpenTab(TAB_MARKET) } }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            SectionTitle("주요 기사", "내 키워드의 새 소식") { onOpenTab(TAB_NEWS) }
            when {
                !news.isHome -> {
                    Hint("뉴스 탭에서 검색 중이라 맞춤 뉴스를 대신 불러와야 합니다.")
                    TextButton(onClick = newsViewModel::refreshHome, modifier = Modifier.padding(horizontal = 12.dp)) { Text("맞춤 뉴스 불러오기") }
                }
                articles.isNotEmpty() -> BriefingCard {
                    articles.forEachIndexed { i, article ->
                        if (i > 0) RowDivider(inset = 14.dp)
                        ArticleLine(article) { ArticleReaderActivity.open(context, article.link, article.title) }
                    }
                }
                news.isLoading -> Hint("불러오는 중…")
                else -> Hint("저장한 키워드의 새 기사가 없습니다.")
            }

            SectionTitle("최근 보도자료", if (newLinks.isEmpty()) "금융감독원 · 금융위원회" else "새 자료 ${newLinks.size}건", accent = newLinks.isNotEmpty()) { onOpenTab(TAB_PRESS) }
            when {
                pressItems.isNotEmpty() -> BriefingCard {
                    pressItems.forEachIndexed { i, item ->
                        if (i > 0) RowDivider(inset = 14.dp)
                        PressLine(item, item.link in newLinks) { openItem(context, item) }
                    }
                }
                pressLoading -> Hint("불러오는 중…")
                else -> Hint("보도자료를 불러오지 못했습니다. 새로고침을 눌러 주세요.")
            }

            if (researchItems.isNotEmpty()) {
                SectionTitle("이번 주 연구리포트", "최근 7일 내 업데이트") { onOpenTab(TAB_PRESS) }
                BriefingCard {
                    researchItems.forEachIndexed { i, item ->
                        if (i > 0) RowDivider(inset = 14.dp)
                        PressLine(item, false) { openItem(context, item) }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** 날짜·요일, 날씨, 인삿말을 얹은 그라데이션 머리말. */
@Composable
private fun BriefingHeader(weather: Weather?, onRefresh: () -> Unit) {
    val now = remember { ZonedDateTime.now(SEOUL) }
    val greeting = remember(weather) { Greeting.pick(now, weather) }
    Box(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(AppColors.header, Color(0xFF3B5A8C))))
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 22.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("TODAY'S BRIEFING", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), letterSpacing = 2.sp)
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "새로고침", tint = Color.White) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(dateFormat.format(now), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("오늘의 브리핑", Modifier.padding(top = 2.dp), style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.85f))
                }
                if (weather != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(weather.emoji, fontSize = 38.sp)
                        Text("${weather.temp}°", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
            if (weather != null) {
                val rain = weather.rainChance?.let { " · 강수 $it%" }.orEmpty()
                Text(
                    "서울 ${weather.summary} · ${weather.min}° / ${weather.max}°$rain",
                    Modifier.padding(top = 10.dp).background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge, color = Color.White
                )
            }
            Text(greeting, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.92f))
        }
    }
}

/** 색 막대가 붙은 구역 제목과 '더 보기'. */
@Composable
private fun SectionTitle(title: String, subtitle: String, accent: Boolean = false, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(34.dp).background(if (accent) AppColors.accent else MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = if (accent) AppColors.accent else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onMore) { Text("더 보기") }
    }
}

@Composable
private fun BriefingCard(content: @Composable () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) { Column(Modifier.padding(vertical = 4.dp)) { content() } }
}

/** 지표 한 칸: 이름, 현재가(오른쪽 정렬)와 괄호 안 작은 글자의 등락률, 당일 흐름 그래프. 상승 빨강·하락 파랑. */
@Composable
private fun MarketTile(item: Instrument, quote: Quote, series: IntradaySeries?, modifier: Modifier, onClick: () -> Unit) {
    val color = changeColor(quote.change)
    Surface(modifier.clickable(onClick = onClick), shape = RoundedCornerShape(16.dp), color = color.copy(alpha = 0.09f)) {
        Column(Modifier.padding(12.dp)) {
            Text(item.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ValueWithDelta(priceText(item, quote.price), quote.changePercent?.let { arrowPercent(it) }, color, Modifier.padding(top = 2.dp))
            IntradayChart(series, color, Modifier.fillMaxWidth().padding(top = 6.dp).height(30.dp))
        }
    }
}

/** ECOS 통계 한 칸: 이름, 값(오른쪽 정렬)과 괄호 안 작은 글자의 증감, 기준일. */
@Composable
private fun StatTile(item: StatItem, modifier: Modifier, onClick: () -> Unit) {
    val color = changeColor(item.change)
    Surface(modifier.clickable(onClick = onClick), shape = RoundedCornerShape(16.dp), color = color.copy(alpha = 0.09f)) {
        Column(Modifier.padding(12.dp)) {
            Text(item.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ValueWithDelta(statText(item, item.value), statDelta(item), color, Modifier.padding(top = 2.dp))
            Text(periodLabel(item.period) + " 기준", Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
        }
    }
}

/** 오른쪽 끝에 맞춘 "21,345.10 (▲0.52%)": 수치는 크게, 등락은 괄호에 넣어 작은 글자로. */
@Composable
private fun ValueWithDelta(value: String, delta: String?, color: Color, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Bottom) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(" (${delta ?: "—"})", Modifier.padding(bottom = 1.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
    }
}

private fun arrowPercent(value: Double): String =
    (if (value > 0) "▲" else if (value < 0) "▼" else "") + String.format(Locale.KOREA, "%.2f%%", kotlin.math.abs(value))

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PressLine(item: ReleaseItem, isNew: Boolean, onOpen: () -> Unit) {
    val meta = listOfNotNull(item.label, item.dateText()).joinToString(" · ")
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (isNew) NewBadge()
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ArticleLine(article: NewsArticle, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
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
