package io.github.hdlee73.financenewsradar.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.CurrencyBitcoin
import io.github.hdlee73.financenewsradar.accounting.AccountingViewModel
import io.github.hdlee73.financenewsradar.accounting.ui.screens.AuditScreen
import io.github.hdlee73.financenewsradar.accounting.ui.screens.DisclosureScreen
import io.github.hdlee73.financenewsradar.accounting.ui.screens.SearchScreen as AccountingSearchScreen
import io.github.hdlee73.financenewsradar.accounting.ui.screens.SimulatorScreen
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
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.imePadding
import io.github.hdlee73.financenewsradar.BuildConfig
import io.github.hdlee73.financenewsradar.data.UpdateStatus
import io.github.hdlee73.financenewsradar.data.UsageStats
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
private const val SCREEN_DART = 13
private const val SCREEN_DISCLOSURE = 14
private const val SCREEN_AUDIT = 15
private const val SCREEN_ACC_SEARCH = 16
private const val SCREEN_SIMULATOR = 17
private const val SCREEN_CONSUMER = 18
private const val SCREEN_FINSIM = 19
private const val SCREEN_CRYPTO = 20
private const val SCREEN_RISK = 21

// 서랍 메뉴에 나오는 모든 화면(순서 = 화면 번호). 오늘의 브리핑·뉴스·시장동향의 번호는 브리핑 화면의 바로가기와 같다.
// 라벨은 익명 사용 통계의 메뉴 이름으로도 쓰인다.
private val appScreens = listOf(
    AppScreen("오늘의 브리핑", Icons.Filled.WbSunny),
    AppScreen("뉴스검색", Icons.Filled.Newspaper),
    AppScreen("금융시장 동향 및 거시경제 지표", Icons.Filled.ShowChart),
    AppScreen("금융당국 등 보도자료", Icons.Filled.Description),
    AppScreen("금융관련 리포트", Icons.Filled.Science),
    AppScreen("기타 참고자료", Icons.Filled.FolderOpen),
    AppScreen("주요사이트", Icons.Filled.Language),
    AppScreen("알림", Icons.Filled.Notifications),
    AppScreen("앱정보", Icons.Filled.Info),
    AppScreen("금융회사 주요 현황", Icons.Filled.Business),
    AppScreen("유권해석·비조치의견서·행정지도", Icons.Filled.Gavel),
    AppScreen("금융회사 제재 공시", Icons.Filled.Gavel),
    AppScreen("스마트 법령검색", Icons.Filled.AccountTree),
    AppScreen("기업 개황 및 공시(DART)", Icons.Filled.Apartment),
    AppScreen("공시조사결과제재", Icons.Filled.Policy),
    AppScreen("회계감리 조치현황", Icons.Filled.FactCheck),
    AppScreen("회계기준서·질의회신·지적사례", Icons.Filled.MenuBook),
    AppScreen("(베타) 회계 양정 및 과징금 시뮬레이터", Icons.Filled.Calculate),
    AppScreen("금융소비자 지킴이", Icons.Filled.VerifiedUser),
    AppScreen("(베타)제재 양정 시뮬레이터", Icons.Filled.Calculate),
    AppScreen("가상자산 시장 동향", Icons.Filled.CurrencyBitcoin),
    AppScreen("금융사 리스크 대시보드", Icons.Filled.Analytics)
)

/** 서랍 메뉴의 묶음. [title]이 없으면 맨 위의 낱개 메뉴, 있으면 접고 펼 수 있는 묶음이다. */
private fun sectionIcon(title: String) = when (title) {
    "금융회사 현황 및 검사" -> Icons.Filled.AccountBalance
    "기업공시 및 회계" -> Icons.Filled.BusinessCenter
    "금융관련 자료" -> Icons.Filled.LibraryBooks
    else -> Icons.Filled.MoreHoriz
}

private class DrawerSection(val title: String?, val ids: List<Int>)

private val drawerSections = listOf(
    DrawerSection(null, listOf(SCREEN_BRIEFING, SCREEN_NEWS, SCREEN_MARKET, SCREEN_LAW, SCREEN_CONSUMER)),
    DrawerSection("금융회사 현황 및 검사", listOf(SCREEN_FISIS, SCREEN_SANCTION, SCREEN_RISK, SCREEN_FINSIM)),
    DrawerSection("기업공시 및 회계", listOf(SCREEN_DART, SCREEN_DISCLOSURE, SCREEN_AUDIT, SCREEN_ACC_SEARCH, SCREEN_SIMULATOR)),
    DrawerSection(null, listOf(SCREEN_CRYPTO)),
    DrawerSection("금융관련 자료", listOf(SCREEN_PRESS, SCREEN_REGULATORY, SCREEN_RESEARCH, SCREEN_LIBRARY)),
    DrawerSection("더보기", listOf(SCREEN_SITES, SCREEN_ALERTS, SCREEN_INFO))
)

/** 접어 둔 묶음 이름들을 기기에 기억한다(앱을 다시 열어도 유지). */
private object MenuFolds {
    private const val PREFS = "menu_folds"
    private const val KEY = "collapsed"
    fun load(context: android.content.Context): Set<String> =
        runCatching { context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty().toSet() }.getOrDefault(emptySet())

    fun save(context: android.content.Context, collapsed: Set<String>) {
        runCatching { context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putStringSet(KEY, collapsed).apply() }
    }
}

/** 앱의 뼈대: 왼쪽 서랍 메뉴(상단 메뉴 버튼 또는 화면 왼쪽 가장자리 밀기)로 모든 화면을 오간다. */
@Composable
fun FinanceNewsRadarApp(
    newsViewModel: NewsViewModel = viewModel(),
    releasesViewModel: ReleasesViewModel = viewModel(),
    libraryViewModel: LibraryViewModel = viewModel(),
    marketViewModel: MarketViewModel = viewModel(),
    fisisViewModel: FisisViewModel = viewModel(),
    dartViewModel: DartViewModel = viewModel(),
    regulatoryViewModel: RegulatoryViewModel = viewModel(),
    sanctionViewModel: SanctionViewModel = viewModel(),
    accountingViewModel: AccountingViewModel = viewModel()
) {
    val newsState by newsViewModel.state.collectAsStateWithLifecycle()
    val releases by releasesViewModel.state.collectAsStateWithLifecycle()
    val accountingState by accountingViewModel.data.collectAsStateWithLifecycle()
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
    val menuContext = LocalContext.current.applicationContext
    var collapsed by remember { mutableStateOf(MenuFolds.load(menuContext)) }
    fun setCollapsed(title: String, value: Boolean) {
        collapsed = if (value) collapsed + title else collapsed - title
        MenuFolds.save(menuContext, collapsed)
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val usageContext = LocalContext.current.applicationContext
    // 익명 사용 통계: 어느 메뉴가 얼마나 열리는지 횟수만 기기에 쌓는다(앱 정보에서 끌 수 있음).
    LaunchedEffect(screen) { UsageStats.recordMenu(usageContext, appScreens[screen].label) }

    fun openMenu() {
        keyboard?.hide()
        focusManager.clearFocus()
        scope.launch { drawerState.open() }
    }
    // 제재공시의 위반 조문을 누르면 스마트 법령검색을 그 조문으로 연다. 이때 뒤로가기는 제재공시로 바로 돌아간다.
    var lawLaunch by remember { mutableStateOf<io.github.hdlee73.financenewsradar.law.LawLaunch?>(null) }
    var lawFromLink by rememberSaveable { mutableStateOf(false) }
    fun go(target: Int) {
        keyboard?.hide()
        focusManager.clearFocus()
        lawFromLink = false
        drawerSections.firstOrNull { it.title != null && target in it.ids && it.title in collapsed }?.title?.let { setCollapsed(it, false) }
        if (target != screen) {
            history = (history.split(',').filter { it.isNotEmpty() } + screen.toString()).takeLast(30).joinToString(",")
            screen = target
        }
        scope.launch { drawerState.close() }
    }
    fun goBack() {
        val stack = history.split(',').filter { it.isNotEmpty() }
        if (stack.isEmpty()) return
        lawFromLink = false
        screen = stack.last().toInt()
        history = stack.dropLast(1).joinToString(",")
    }
    // 회계감리·공시조사결과제재의 회사명을 누르면 「기업 개황 및 공시(DART)」에서 그 회사를 찾는다.
    var dartRequest by remember { mutableStateOf<String?>(null) }
    // 회계감리·공시조사결과제재에서 열어 둔 상세 항목(뒤로가기로 목록으로 돌아온다)
    var auditSelected by rememberSaveable { mutableStateOf<String?>(null) }
    var disclosureSelected by rememberSaveable { mutableStateOf<String?>(null) }
    fun openCompany(name: String) {
        dartRequest = name
        go(SCREEN_DART)
    }
    fun openLaw(query: String) {
        lawLaunch = io.github.hdlee73.financenewsradar.law.LawLaunch(query)
        go(SCREEN_LAW)
        lawFromLink = true
    }
    BackHandler(enabled = history.isNotEmpty() && !drawerState.isOpen) { goBack() }
    BackHandler(enabled = screen == SCREEN_AUDIT && auditSelected != null && !drawerState.isOpen) { auditSelected = null }
    BackHandler(enabled = screen == SCREEN_DISCLOSURE && disclosureSelected != null && !drawerState.isOpen) { disclosureSelected = null }
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    LaunchedEffect(newsState.error) {
        newsState.error?.let {
            snackbarHost.showSnackbar(it)
            newsViewModel.dismissError()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                    Column(Modifier.padding(start = 28.dp, end = 16.dp, top = 28.dp, bottom = 12.dp)) {
                        Text("FSS Insights", style = MaterialTheme.typography.headlineSmall)
                        Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    drawerSections.forEach { section ->
                        val title = section.title
                        val folded = title != null && title in collapsed
                        if (title != null) {
                            // 묶음 제목도 다른 최상위 메뉴와 같은 크기·아이콘의 항목이다. 누르면 하위 메뉴를 접고 펼친다.
                            // 접힌 상태에서도 현재 화면이 그 묶음에 있으면 선택 표시로 알려 준다.
                            NavigationDrawerItem(
                                icon = { Icon(sectionIcon(title), contentDescription = null) },
                                label = { Text(title) },
                                badge = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (folded) Text("${section.ids.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 6.dp))
                                        Icon(
                                            if (folded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                                            contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp)
                                        )
                                    }
                                },
                                selected = folded && screen in section.ids,
                                onClick = { setCollapsed(title, !folded) },
                                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                                    .semantics { contentDescription = if (folded) "$title, 접힘, 누르면 하위 메뉴 ${section.ids.size}개 펼치기" else "$title, 펼침, 누르면 하위 메뉴 접기" }
                            )
                        }
                        if (!folded) {
                            val lineColor = MaterialTheme.colorScheme.outlineVariant
                            section.ids.forEach { id ->
                                val item = appScreens[id]
                                val sub = title != null
                                NavigationDrawerItem(
                                    icon = { Icon(item.icon, contentDescription = null, modifier = if (sub) Modifier.size(20.dp) else Modifier) },
                                    label = { Text(item.label, style = if (sub) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    badge = if (id == SCREEN_INFO && updateAvailable != null) {
                                        { Text("NEW", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium) }
                                    } else null,
                                    selected = screen == id,
                                    onClick = { go(id) },
                                    // 하위 메뉴: 오른쪽으로 들여 쓰고 왼쪽에 세로선을 그어 묶음 제목의 하위임을 분명히 한다.
                                    modifier = if (sub) Modifier
                                        .padding(start = 28.dp)
                                        .drawBehind {
                                            drawLine(lineColor, Offset(14.dp.toPx(), 0f), Offset(14.dp.toPx(), size.height), strokeWidth = 2.dp.toPx())
                                        }
                                        .padding(NavigationDrawerItemDefaults.ItemPadding)
                                    else Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                                )
                            }
                        }
                    }
                }
            }
        }
    ) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        val palette = when (screen) {
            SCREEN_BRIEFING -> HeroPalettes.Sky
            SCREEN_NEWS -> HeroPalettes.Indigo
            SCREEN_MARKET -> HeroPalettes.Teal
            SCREEN_PRESS -> HeroPalettes.Ocean
            SCREEN_RESEARCH -> HeroPalettes.Violet
            SCREEN_LIBRARY -> HeroPalettes.Amber
            SCREEN_SITES -> HeroPalettes.Slate
            SCREEN_ALERTS -> HeroPalettes.Rose
            SCREEN_FISIS -> HeroPalettes.Green
            SCREEN_DART -> HeroPalettes.Teal
            SCREEN_REGULATORY -> HeroPalettes.Indigo
            SCREEN_SANCTION -> HeroPalettes.Coral
            SCREEN_LAW -> HeroPalettes.Violet
            SCREEN_DISCLOSURE -> HeroPalettes.Rose
            SCREEN_AUDIT -> HeroPalettes.Coral
            SCREEN_ACC_SEARCH -> HeroPalettes.Sky
            SCREEN_SIMULATOR -> HeroPalettes.Amber
            else -> HeroPalettes.Slate
        }
        CompositionLocalProvider(LocalHeroPalette provides palette) {
        Column(Modifier.fillMaxSize().padding(padding).navigationBarsPadding()) {
        // 상태 표시줄 영역과 상단 메뉴 바를 같은 네이비로 칠한다.
        Spacer(Modifier.fillMaxWidth().background(AppColors.heroStart).windowInsetsTopHeight(WindowInsets.statusBars))
        Row(Modifier.fillMaxWidth().background(AppColors.heroStart).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::openMenu) {
                Icon(Icons.Filled.Menu, contentDescription = "메뉴 열기", tint = AppColors.onHero)
            }
            Text("FSS Insights", color = AppColors.onHero, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
        }
        NoticeBanner()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // 화면을 오가도 각 화면의 검색어·스크롤 위치가 유지되도록 화면별로 상태를 보관한다.
            stateHolder.SaveableStateProvider(screen) {
                when (screen) {
                    SCREEN_BRIEFING -> BriefingScreen(newsViewModel, releasesViewModel, marketViewModel, onOpenTab = ::go)
                    SCREEN_NEWS -> NewsScreen(newsViewModel, onOpenSettings = { settingsOpen = true })
                    SCREEN_MARKET -> MarketScreen(marketViewModel)
                    SCREEN_FISIS -> FisisScreen(fisisViewModel, sanctionViewModel, onOpenLaw = ::openLaw)
                    SCREEN_DART -> DartScreen(dartViewModel, searchRequest = dartRequest, onSearchRequestConsumed = { dartRequest = null })
                    SCREEN_DISCLOSURE -> DisclosureScreen(
                        accountingState, disclosureSelected, onSelect = { disclosureSelected = it },
                        modifier = Modifier.fillMaxSize().imePadding(), onOpenCompany = ::openCompany, onOpenLaw = ::openLaw
                    )
                    SCREEN_AUDIT -> AuditScreen(
                        accountingState, auditSelected, onSelect = { auditSelected = it },
                        modifier = Modifier.fillMaxSize().imePadding(), onOpenCompany = ::openCompany
                    )
                    SCREEN_ACC_SEARCH -> AccountingSearchScreen(accountingState, Modifier.fillMaxSize().imePadding())
                    SCREEN_SIMULATOR -> SimulatorScreen(Modifier.fillMaxSize().imePadding())
                    SCREEN_CONSUMER -> WebEmbedScreen("consumer", backEnabled = !drawerState.isOpen)
                    SCREEN_FINSIM -> WebEmbedScreen("finsim", backEnabled = !drawerState.isOpen)
                    SCREEN_CRYPTO -> WebEmbedScreen("crypto", backEnabled = !drawerState.isOpen)
                    SCREEN_RISK -> WebEmbedScreen("risk", backEnabled = !drawerState.isOpen)
                    SCREEN_REGULATORY -> RegulatoryScreen(regulatoryViewModel)
                    SCREEN_SANCTION -> SanctionScreen(sanctionViewModel, onOpenLaw = ::openLaw)
                    SCREEN_LAW -> io.github.hdlee73.financenewsradar.law.LawScreen(
                        launch = lawLaunch,
                        onLaunchConsumed = { lawLaunch = null },
                        onReturn = if (lawFromLink) ({ goBack() }) else null
                    )
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
