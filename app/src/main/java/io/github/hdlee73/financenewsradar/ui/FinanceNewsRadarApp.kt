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

private class AppTab(val label: String, val outlined: ImageVector, val filled: ImageVector)

private val appTabs = listOf(
    AppTab("오늘의\n브리핑", Icons.Outlined.WbSunny, Icons.Filled.WbSunny),
    AppTab("뉴스\n검색", Icons.Outlined.Newspaper, Icons.Filled.Newspaper),
    AppTab("시장\n동향", Icons.Outlined.ShowChart, Icons.Filled.ShowChart),
    AppTab("자료", Icons.Outlined.FolderOpen, Icons.Filled.FolderOpen),
    AppTab("더보기", Icons.Outlined.MoreHoriz, Icons.Filled.MoreHoriz)
)

private const val TAB_MATERIALS = 3

// 하단 탭 수를 5개로 유지하기 위해, 성격이 비슷한 화면은 한 탭 안의 상단 구분 탭으로 묶는다.
private val materialSubTabs = listOf("보도자료", "연구원 자료", "참고자료")
private val moreSubTabs = listOf("주요사이트", "알림", "앱 정보")

/** 앱의 뼈대: 하단 탭(오늘의 브리핑 / 뉴스 / 시장동향 / 자료[보도자료·연구원 자료·참고자료] / 더보기[주요사이트·알림·앱 정보]). */
@Composable
fun FinanceNewsRadarApp(
    newsViewModel: NewsViewModel = viewModel(),
    releasesViewModel: ReleasesViewModel = viewModel(),
    libraryViewModel: LibraryViewModel = viewModel(),
    marketViewModel: MarketViewModel = viewModel()
) {
    val newsState by newsViewModel.state.collectAsStateWithLifecycle()
    val releases by releasesViewModel.state.collectAsStateWithLifecycle()
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
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var materialSub by rememberSaveable { mutableIntStateOf(0) }
    var moreSub by rememberSaveable { mutableIntStateOf(0) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(newsState.error) {
        newsState.error?.let {
            snackbarHost.showSnackbar(it)
            newsViewModel.dismissError()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            AppTabBar(selected = tab, onSelect = {
                keyboard?.hide()
                focusManager.clearFocus()
                tab = it
            })
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
        // 상태 표시줄 영역도 헤더와 같은 네이비로 칠한다.
        Spacer(Modifier.fillMaxWidth().background(AppColors.header).windowInsetsTopHeight(WindowInsets.statusBars))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // 탭을 오가도 각 화면의 검색어·스크롤 위치가 유지되도록 화면별로 상태를 보관한다.
            stateHolder.SaveableStateProvider(tab) {
                when (tab) {
                    0 -> BriefingScreen(
                        newsViewModel, releasesViewModel, marketViewModel,
                        onOpenTab = {
                            if (it == TAB_MATERIALS) materialSub = 0   // 브리핑의 보도자료 '더 보기'는 보도자료 구분 탭으로
                            tab = it
                        }
                    )
                    1 -> NewsScreen(newsViewModel, onOpenSettings = { settingsOpen = true })
                    2 -> MarketScreen(marketViewModel)
                    3 -> SubTabbed(materialSubTabs, materialSub, { materialSub = it }) {
                        stateHolder.SaveableStateProvider("materials-$materialSub") {
                            when (materialSub) {
                                0 -> ReleasesScreen(AgencyGroup.PRESS, releasesViewModel)
                                1 -> ReleasesScreen(AgencyGroup.RESEARCH, releasesViewModel)
                                else -> LibraryScreen(libraryViewModel)
                            }
                        }
                    }
                    else -> SubTabbed(moreSubTabs, moreSub, { moreSub = it }) {
                        stateHolder.SaveableStateProvider("more-$moreSub") {
                            when (moreSub) {
                                0 -> SitesScreen(
                                    links = releases.links,
                                    onSave = releasesViewModel::saveLinks,
                                    onReset = releasesViewModel::resetLinks
                                )
                                1 -> AlertSettingsScreen(newsState.settings.keywords)
                                else -> AppInfoScreen()
                            }
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
}

/** 한 하단 탭 안에서 화면을 나누는 상단 구분 탭. */
@Composable
private fun SubTabbed(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(AppColors.header)) {
            labels.forEachIndexed { index, label ->
                val isSelected = index == selected
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        label,
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = if (isSelected) 1f else 0.6f),
                        maxLines = 1,
                        style = MaterialTheme.typography.labelLarge
                    )
                    Box(
                        Modifier.fillMaxWidth().height(3.dp)
                            .background(if (isSelected) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Transparent)
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

/** 높이를 줄인 하단 탭(56dp). 평소에는 아이콘만 보이고, 선택한 탭만 넓어지면서 글자가 나타난다. */
@Composable
private fun AppTabBar(selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.background(MaterialTheme.colorScheme.background)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(56.dp)) {
            appTabs.forEachIndexed { index, item ->
                val isSelected = index == selected
                val color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                val tabWeight by androidx.compose.animation.core.animateFloatAsState(if (isSelected) 2.4f else 0.9f, label = "tabWeight")
                Column(
                    modifier = Modifier
                        .weight(tabWeight)
                        .fillMaxHeight()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(if (isSelected) item.filled else item.outlined, contentDescription = item.label.replace("\n", " "), tint = color, modifier = Modifier.size(24.dp))
                    if (isSelected) {
                        Text(item.label.replace("\n", " "), color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp))
                    }
                }
            }
        }
    }
}
