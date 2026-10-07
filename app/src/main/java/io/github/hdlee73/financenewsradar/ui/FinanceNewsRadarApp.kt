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
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MenuBook
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
    AppTab("뉴스\n검색", Icons.Outlined.Newspaper, Icons.Filled.Newspaper),
    AppTab("시장\n동향", Icons.Outlined.ShowChart, Icons.Filled.ShowChart),
    AppTab("금융당국\n보도자료", Icons.Outlined.AccountBalance, Icons.Filled.AccountBalance),
    AppTab("금융관련\n연구원 자료", Icons.Outlined.MenuBook, Icons.Filled.MenuBook),
    AppTab("참고자료\n모음", Icons.Outlined.FolderOpen, Icons.Filled.FolderOpen),
    AppTab("금융관련\n주요사이트", Icons.Outlined.Language, Icons.Filled.Language),
    AppTab("앱\n정보", Icons.Outlined.Info, Icons.Filled.Info)
)

/** 앱의 뼈대: 하단 탭(뉴스 / 시장동향 / 금융당국 보도자료 / 연구원 자료 / 참고자료 / 주요사이트 / 앱 정보). */
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
                    0 -> NewsScreen(newsViewModel, onOpenSettings = { settingsOpen = true })
                    1 -> MarketScreen(marketViewModel)
                    2 -> ReleasesScreen(AgencyGroup.PRESS, releasesViewModel)
                    3 -> ReleasesScreen(AgencyGroup.RESEARCH, releasesViewModel)
                    4 -> LibraryScreen(libraryViewModel)
                    5 -> SitesScreen(
                        links = releases.links,
                        onSave = releasesViewModel::saveLinks,
                        onReset = releasesViewModel::resetLinks
                    )
                    else -> AppInfoScreen()
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

/** 높이를 줄인 하단 탭(아이콘 + 글자 56dp). 시스템 제스처 영역만큼만 아래 여백을 둔다. */
@Composable
private fun AppTabBar(selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.background(MaterialTheme.colorScheme.background)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(56.dp)) {
            appTabs.forEachIndexed { index, item ->
                val isSelected = index == selected
                val color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(if (isSelected) item.filled else item.outlined, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                    Text(item.label, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 10.sp))
                }
            }
        }
    }
}
