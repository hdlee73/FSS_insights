package io.github.hdlee73.financenewsradar.ui

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
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Info
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
import io.github.hdlee73.financenewsradar.BuildConfig
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

// 왼쪽 서랍 메뉴에 나오는 모든 화면(순서 = 화면 번호). 오늘의 브리핑·뉴스·시장동향의 번호는 브리핑 화면의 바로가기와 같다.
private val appScreens = listOf(
    AppScreen("오늘의 브리핑", Icons.Filled.WbSunny),
    AppScreen("뉴스 검색", Icons.Filled.Newspaper),
    AppScreen("금융시장 동향", Icons.Filled.ShowChart),
    AppScreen("보도자료", Icons.Filled.Description),
    AppScreen("연구원 자료", Icons.Filled.Science),
    AppScreen("참고자료", Icons.Filled.FolderOpen),
    AppScreen("주요사이트", Icons.Filled.Language),
    AppScreen("알림", Icons.Filled.Notifications),
    AppScreen("앱 정보", Icons.Filled.Info)
)

// 서랍 메뉴의 묶음: 소제목과 그 아래 화면 번호.
private val drawerSections = listOf(
    null to listOf(SCREEN_BRIEFING, SCREEN_NEWS, SCREEN_MARKET),
    "자료" to listOf(SCREEN_PRESS, SCREEN_RESEARCH, SCREEN_LIBRARY),
    "더보기" to listOf(SCREEN_SITES, SCREEN_ALERTS, SCREEN_INFO)
)

/** 앱의 뼈대: 왼쪽 서랍 메뉴(상단 메뉴 버튼 또는 화면 왼쪽 가장자리 밀기)로 모든 화면을 오간다. */
@Composable
fun FinanceNewsRadarApp(
    newsViewModel: NewsViewModel = viewModel(),
    releasesViewModel: ReleasesViewModel = viewModel(),
    libraryViewModel: LibraryViewModel = viewModel(),
    marketViewModel: MarketViewModel = viewModel()
) {
    val newsState by newsViewModel.state.collectAsStateWithLifecycle()
    val releases by releasesViewModel.state.collectAsStateWithLifecycle()
    val updateAvailable by UpdateStatus.available.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val stateHolder = rememberSaveableStateHolder()
    val view = LocalView.current
    val darkIcons = false
    SideEffect {
        // 네이비 헤더 위에서 상태 표시줄 아이콘이 보이도록 밝은 아이콘으로.
        (view.context as? android.app.Activity)?.window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = darkIcons
        }
    }
    var screen by rememberSaveable { mutableIntStateOf(SCREEN_BRIEFING) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    fun openMenu() {
        keyboard?.hide()
        focusManager.clearFocus()
        scope.launch { drawerState.open() }
    }
    fun go(target: Int) {
        keyboard?.hide()
        focusManager.clearFocus()
        screen = target
        scope.launch { drawerState.close() }
    }
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
                    drawerSections.forEachIndexed { index, (title, ids) ->
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        if (title != null) {
                            Text(title, Modifier.padding(start = 28.dp, top = 4.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ids.forEach { id ->
                            val item = appScreens[id]
                            NavigationDrawerItem(
                                icon = { Icon(item.icon, contentDescription = null) },
                                label = { Text(item.label) },
                                badge = if (id == SCREEN_INFO && updateAvailable != null) {
                                    { Text("NEW", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium) }
                                } else null,
                                selected = screen == id,
                                onClick = { go(id) },
                                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                            )
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
        Column(Modifier.fillMaxSize().padding(padding).navigationBarsPadding()) {
        // 상태 표시줄 영역과 상단 메뉴 바를 같은 네이비로 칠한다.
        Spacer(Modifier.fillMaxWidth().background(AppColors.header).windowInsetsTopHeight(WindowInsets.statusBars))
        Row(Modifier.fillMaxWidth().background(AppColors.header).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::openMenu) {
                Icon(Icons.Filled.Menu, contentDescription = "메뉴 열기", tint = Color.White)
            }
            Text("FSS Insights", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // 화면을 오가도 각 화면의 검색어·스크롤 위치가 유지되도록 화면별로 상태를 보관한다.
            stateHolder.SaveableStateProvider(screen) {
                when (screen) {
                    SCREEN_BRIEFING -> BriefingScreen(newsViewModel, releasesViewModel, marketViewModel, onOpenTab = ::go)
                    SCREEN_NEWS -> NewsScreen(newsViewModel, onOpenSettings = { settingsOpen = true })
                    SCREEN_MARKET -> MarketScreen(marketViewModel)
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
}
