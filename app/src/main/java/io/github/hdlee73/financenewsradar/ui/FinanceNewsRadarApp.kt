package io.github.hdlee73.financenewsradar.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.hdlee73.financenewsradar.model.AgencyGroup

private class AppTab(val label: String, val outlined: ImageVector, val filled: ImageVector)

private val appTabs = listOf(
    AppTab("뉴스", Icons.Outlined.Newspaper, Icons.Filled.Newspaper),
    AppTab("보도자료", Icons.Outlined.AccountBalance, Icons.Filled.AccountBalance),
    AppTab("연구자료", Icons.Outlined.MenuBook, Icons.Filled.MenuBook),
    AppTab("참고사이트", Icons.Outlined.Language, Icons.Filled.Language)
)

/** 앱의 뼈대: 4개 하단 탭(뉴스 / 금융당국 보도자료 / 연구소 최근자료 / 참고사이트). */
@Composable
fun FinanceNewsRadarApp(
    newsViewModel: NewsViewModel = viewModel(),
    releasesViewModel: ReleasesViewModel = viewModel()
) {
    val newsState by newsViewModel.state.collectAsStateWithLifecycle()
    val releases by releasesViewModel.state.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val stateHolder = rememberSaveableStateHolder()
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
        Box(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
            // 탭을 오가도 각 화면의 검색어·스크롤 위치가 유지되도록 화면별로 상태를 보관한다.
            stateHolder.SaveableStateProvider(tab) {
                when (tab) {
                    0 -> NewsScreen(newsViewModel, onOpenSettings = { settingsOpen = true })
                    1 -> ReleasesScreen(AgencyGroup.PRESS, releasesViewModel)
                    2 -> ReleasesScreen(AgencyGroup.RESEARCH, releasesViewModel)
                    else -> SitesScreen(
                        links = releases.links,
                        onSave = releasesViewModel::saveLinks,
                        onReset = releasesViewModel::resetLinks
                    )
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

@Composable
private fun AppTabBar(selected: Int, onSelect: (Int) -> Unit) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
            appTabs.forEachIndexed { index, item ->
                val isSelected = index == selected
                NavigationBarItem(
                    selected = isSelected,
                    onClick = { onSelect(index) },
                    icon = { Icon(if (isSelected) item.filled else item.outlined, contentDescription = null) },
                    label = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = Color.Transparent,
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }
}
