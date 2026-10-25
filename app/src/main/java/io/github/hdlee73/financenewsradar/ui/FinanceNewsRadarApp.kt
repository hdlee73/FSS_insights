package io.github.hdlee73.financenewsradar.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.ui.platform.LocalView
import io.github.hdlee73.financenewsradar.ui.theme.AppColors
import io.github.hdlee73.financenewsradar.ui.theme.HeroPalettes
import io.github.hdlee73.financenewsradar.ui.theme.LocalHeroPalette
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.hdlee73.financenewsradar.model.AgencyGroup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.min
import io.github.hdlee73.financenewsradar.data.UpdateStatus
import kotlinx.coroutines.launch
private class AppScreen(val label: String, val icon: ImageVector)

private const val SCREEN_BRIEFING = 0
private const val SCREEN_NEWS = 1
private const val SCREEN_MARKET = 2
private const val SCREEN_PRESS = 3
private const val SCREEN_RESEARCH = 4
private const val SCREEN_LIBRARY = 5
private const val SCREEN_SITES = 6
private const val SCREEN_ALERTS = 7
private const val SCREEN_INFO = 8
private const val SCREEN_FISIS = 9
private const val SCREEN_REGULATORY = 10
private const val SCREEN_SANCTION = 11
private const val SCREEN_LAW = 12

// 왼쪽 서랍 메뉴에 나오는 모든 화면(순서 = 화면 번호). 오늘의 브리핑·뉴스·시장동향의 번호는 브리핑 화면의 바로가기와 같다.
private val appScreens = listOf(
    AppScreen("오늘의 브리핑", Icons.Filled.WbSunny),
    AppScreen("뉴스 검색", Icons.Filled.Newspaper),
    AppScreen("금융시장 동향", Icons.Filled.ShowChart),
    AppScreen("보도자료", Icons.Filled.Description),
    AppScreen("금융관련 리포트", Icons.Filled.Science),
    AppScreen("참고 자료실", Icons.Filled.FolderOpen),
    AppScreen("주요사이트", Icons.Filled.Language),
    AppScreen("알림", Icons.Filled.Notifications),
    AppScreen("앱 정보", Icons.Filled.Info),
    AppScreen("금융회사 주요 현황", Icons.Filled.Business),
    AppScreen("유권해석·비조치의견서·행정지도", Icons.Filled.Gavel),
    AppScreen("금융회사 제재공시", Icons.Filled.Gavel),
    AppScreen("스마트 법령검색", Icons.Filled.AccountTree)
)

// 왼쪽 메뉴의 묶음: 소제목과 그 아래 화면 번호.
private val menuSections = listOf(
    null to listOf(SCREEN_BRIEFING, SCREEN_NEWS, SCREEN_MARKET, SCREEN_FISIS, SCREEN_SANCTION, SCREEN_LAW),
    "자료" to listOf(SCREEN_PRESS, SCREEN_REGULATORY, SCREEN_RESEARCH, SCREEN_LIBRARY),
    "더보기" to listOf(SCREEN_SITES, SCREEN_ALERTS, SCREEN_INFO)
)

/** 화면별 머리말 색. 왼쪽 메뉴의 아이콘 타일도 같은 색을 쓴다. */
private fun screenPalette(screen: Int) = when (screen) {
    SCREEN_BRIEFING -> HeroPalettes.Sky
    SCREEN_NEWS -> HeroPalettes.Indigo
    SCREEN_MARKET -> HeroPalettes.Teal
    SCREEN_PRESS -> HeroPalettes.Ocean
    SCREEN_RESEARCH -> HeroPalettes.Violet
    SCREEN_LIBRARY -> HeroPalettes.Amber
    SCREEN_SITES -> HeroPalettes.Slate
    SCREEN_ALERTS -> HeroPalettes.Rose
    SCREEN_FISIS -> HeroPalettes.Green
    SCREEN_REGULATORY -> HeroPalettes.Indigo
    SCREEN_SANCTION -> HeroPalettes.Coral
    SCREEN_LAW -> HeroPalettes.Violet
    else -> HeroPalettes.Slate
}

private val sideMenuSections = menuSections.map { (title, ids) ->
    SideMenuSection(title, ids.map { SideMenuItem(it, appScreens[it].label, appScreens[it].icon, screenPalette(it).end) })
}

/** 메뉴가 열렸을 때 본문이 줄어드는 비율과 모서리 둥글기. */
private const val MENU_CONTENT_SCALE = 0.88f
private val MENU_CONTENT_CORNER = 28.dp
/** 메뉴가 닫혀 있을 때 끌어서 열 수 있는 왼쪽 가장자리 폭(위 메뉴 바는 어디서나 가능). */
private val MENU_EDGE = 24.dp

/**
 * 앱의 뼈대: 아이폰 앱처럼 왼쪽 메뉴로 모든 화면을 오간다. 메뉴는 본문 뒤에 있고, 상단 ☰ 버튼·위 메뉴 바나
 * 왼쪽 가장자리를 오른쪽으로 밀면 본문이 오른쪽으로 밀려나며 메뉴가 드러난다. 뒤로가기는 열린 메뉴를 먼저 닫는다.
 */
@Composable
fun FinanceNewsRadarApp(
    newsViewModel: NewsViewModel = viewModel(),
    releasesViewModel: ReleasesViewModel = viewModel(),
    libraryViewModel: LibraryViewModel = viewModel(),
    marketViewModel: MarketViewModel = viewModel(),
    fisisViewModel: FisisViewModel = viewModel(),
    regulatoryViewModel: RegulatoryViewModel = viewModel()
) {
    val newsState by newsViewModel.state.collectAsStateWithLifecycle()
    val releases by releasesViewModel.state.collectAsStateWithLifecycle()
    val updateAvailable by UpdateStatus.available.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val stateHolder = rememberSaveableStateHolder()
    val view = LocalView.current
    val darkIcons = !isSystemInDarkTheme()
    SideEffect {
        // 밝은 테마는 연한 헤더 위에서 어두운 아이콘, 어두운 테마는 밝은 아이콘으로.
        (view.context as? android.app.Activity)?.window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = darkIcons
        }
    }
    var screen by rememberSaveable { mutableIntStateOf(SCREEN_BRIEFING) }
    // 앱 안에서 거쳐 온 화면(뒤로가기 시 이전 화면으로). "1,2" 형태의 문자열로 저장해 화면 회전에도 유지한다.
    var history by rememberSaveable { mutableStateOf("") }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    // 메뉴가 열린 정도(0 = 닫힘, 1 = 열림). 손가락을 따라 움직이고, 떼면 가까운 쪽으로 스프링 애니메이션.
    val menu = remember { Animatable(0f) }
    val menuVisible by remember { derivedStateOf { menu.value > 0f } }
    val scope = rememberCoroutineScope()
    val menuSpring = spring<Float>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)

    fun openMenu() {
        keyboard?.hide()
        focusManager.clearFocus()
        scope.launch { menu.animateTo(1f, menuSpring) }
    }
    fun closeMenu() {
        scope.launch { menu.animateTo(0f, menuSpring) }
    }
    fun go(target: Int) {
        keyboard?.hide()
        focusManager.clearFocus()
        if (target != screen) {
            history = (history.split(',').filter { it.isNotEmpty() } + screen.toString()).takeLast(30).joinToString(",")
            screen = target
        }
        closeMenu()
    }
    BackHandler(enabled = history.isNotEmpty() && !menuVisible) {
        val stack = history.split(',').filter { it.isNotEmpty() }
        screen = stack.last().toInt()
        history = stack.dropLast(1).joinToString(",")
    }
    BackHandler(enabled = menuVisible) { closeMenu() }

    LaunchedEffect(newsState.error) {
        newsState.error?.let {
            snackbarHost.showSnackbar(it)
            newsViewModel.dismissError()
        }
    }

    val dark = isSystemInDarkTheme()
    val density = LocalDensity.current
    val topBarBottomPx = WindowInsets.statusBars.getTop(density) + with(density) { 44.dp.toPx() }
    val edgePx = with(density) { MENU_EDGE.toPx() }
    // 메뉴 폭은 화면 폭의 78%(최대 320dp). 화면 회전 등으로 폭이 바뀌면 끌기 계산도 새로 한다.
    val menuWidth = min(LocalConfiguration.current.screenWidthDp * 0.78f, 320f).dp
    val menuWidthPx = with(density) { menuWidth.toPx() }
    Box(
        Modifier.fillMaxSize()
            .background(if (dark) Color(0xFF07090C) else MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(menuWidthPx, topBarBottomPx) {
                detectSideMenuDrag(
                    canStart = { p: Offset -> menu.value > 0f || p.x <= edgePx || p.y <= topBarBottomPx },
                    onDrag = { dx -> scope.launch { menu.snapTo((menu.value + dx / menuWidthPx).coerceIn(0f, 1f)) } },
                    onDragEnd = { vx ->
                        val target = when {
                            vx > 600f -> 1f
                            vx < -600f -> 0f
                            menu.value > 0.5f -> 1f
                            else -> 0f
                        }
                        if (target == 1f) { keyboard?.hide(); focusManager.clearFocus() }
                        scope.launch { menu.animateTo(target, menuSpring) }
                    }
                )
            }
    ) {

        SideMenu(
            sections = sideMenuSections,
            selected = screen,
            badgeId = if (updateAvailable != null) SCREEN_INFO else null,
            onSelect = ::go,
            modifier = Modifier.width(menuWidth).graphicsLayer {
                // 메뉴는 본문보다 조금 느리게 따라 들어온다(시차 효과).
                translationX = -(1f - menu.value) * menuWidthPx * 0.25f
                alpha = menu.value
            }
        )

        val corner = with(density) { MENU_CONTENT_CORNER.toPx() }
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    val p = menu.value
                    val scale = 1f - (1f - MENU_CONTENT_SCALE) * p
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    scaleX = scale
                    scaleY = scale
                    translationX = p * menuWidthPx
                    shape = RoundedCornerShape(corner * p)
                    clip = p > 0f
                    shadowElevation = 24.dp.toPx() * p
                }
        ) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        CompositionLocalProvider(LocalHeroPalette provides screenPalette(screen)) {
        Column(Modifier.fillMaxSize().padding(padding).navigationBarsPadding()) {
        // 상태 표시줄 영역과 상단 메뉴 바를 같은 머리말 색으로 칠한다.
        Spacer(Modifier.fillMaxWidth().background(AppColors.heroStart).windowInsetsTopHeight(WindowInsets.statusBars))
        Row(Modifier.fillMaxWidth().background(AppColors.heroStart).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::openMenu) {
                Icon(Icons.Filled.Menu, contentDescription = "메뉴 열기", tint = AppColors.onHero)
            }
            Text("FSS Insights", color = AppColors.onHero, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // 화면을 오가도 각 화면의 검색어·스크롤 위치가 유지되도록 화면별로 상태를 보관한다.
            stateHolder.SaveableStateProvider(screen) {
                when (screen) {
                    SCREEN_BRIEFING -> BriefingScreen(newsViewModel, releasesViewModel, marketViewModel, onOpenTab = ::go)
                    SCREEN_NEWS -> NewsScreen(newsViewModel, onOpenSettings = { settingsOpen = true })
                    SCREEN_MARKET -> MarketScreen(marketViewModel)
                    SCREEN_FISIS -> FisisScreen(fisisViewModel)
                    SCREEN_REGULATORY -> RegulatoryScreen(regulatoryViewModel)
                    SCREEN_SANCTION -> RegulatoryScreen(regulatoryViewModel, title = "금융회사 제재공시", sources = RegulatoryMenus.sanction)
                    SCREEN_LAW -> io.github.hdlee73.financenewsradar.law.LawScreen()
                    SCREEN_PRESS -> ReleasesScreen(AgencyGroup.PRESS, releasesViewModel)
                    SCREEN_RESEARCH -> ReleasesScreen(AgencyGroup.RESEARCH, releasesViewModel)
                    SCREEN_LIBRARY -> LibraryScreen(libraryViewModel)
                    SCREEN_SITES -> SitesScreen(
                        links = releases.links,
                        onSave = releasesViewModel::saveLinks,
                        onReset = releasesViewModel::resetLinks
                    )
                    SCREEN_ALERTS -> AlertSettingsScreen(newsState.settings.keywords)
                    else -> AppInfoScreen()
                }
            }
        }
        }
        }
    }
            // 메뉴가 열려 있으면 본문은 눌러도 반응하지 않고, 누르면 메뉴가 닫힌다.
            if (menuVisible) {
                Box(
                    Modifier.fillMaxSize().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "메뉴 닫기",
                        onClick = ::closeMenu
                    )
                )
            }
        }
    }

    if (settingsOpen) {
        SettingsDialog(
            current = newsState.settings,
            credentials = newsState.credentials,
            onDismiss = { settingsOpen = false },
            onSave = { settings, credentials ->
                newsViewModel.saveSettings(settings, credentials)
                settingsOpen = false
            }
        )
    }

    // 새 버전이 있으면 앱을 열 때마다(설치 전까지) 팝업으로 알린다.
    UpdatePrompt(onOpenAppInfo = { screen = SCREEN_INFO })

    // 최초 실행 1회: 비공식 앱 안내(확인해야 닫힘).
    DisclaimerDialog()

    // 업데이트 설치 후 처음 열 때 1회: 이번 버전 변경 내용.
    WhatsNewDialog()
}
