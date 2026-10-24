package io.github.hdlee73.financenewsradar.law

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hdlee73.everylaw.core.model.LawSummary
import io.github.hdlee73.financenewsradar.law.data.SplitMode
import io.github.hdlee73.financenewsradar.law.library.DocumentScreen
import io.github.hdlee73.financenewsradar.law.library.DocumentViewModel
import io.github.hdlee73.financenewsradar.law.library.PrecedentScreen
import io.github.hdlee73.financenewsradar.law.library.PrecedentViewModel
import io.github.hdlee73.financenewsradar.law.search.SearchScreen
import io.github.hdlee73.financenewsradar.law.search.SearchViewModel

/** 검색 화면 위에 쌓이는 상세 화면 */
private sealed interface LawRoute {
    data class Precedent(val serialNo: String) : LawRoute
    data class Law(val law: LawSummary) : LawRoute
    data class AdminRule(val serialNo: String) : LawRoute
}

/**
 * 서랍 메뉴의 "법령·위임체계" 화면. 검색 → 위임 트리 → 판례·조문 상세를 이 안에서 오가고,
 * 뒤로가기는 앱을 끄지 않고 직전 화면(결과 → 검색 첫 화면 → 앱의 이전 메뉴)으로 돌아간다.
 */
@Composable
fun LawScreen() {
    val container = LawContainer.get(LocalContext.current)
    val factory = container.viewModelFactory
    val searchVm: SearchViewModel = viewModel(factory = factory)
    var stack by remember { mutableStateOf(emptyList<LawRoute>()) }
    val appSettings by container.settings.settings.collectAsStateWithLifecycle()
    val canGoBackInSearch by searchVm.canGoBack.collectAsStateWithLifecycle()

    val widthDp = LocalConfiguration.current.screenWidthDp
    val split = when (appSettings.splitMode) {
        SplitMode.Auto -> widthDp >= 600
        SplitMode.Always -> true
        SplitMode.Never -> false
    }

    // 뒤로가기: ① 상세 화면 → 검색 결과 ② 검색 결과 → 이전 결과·첫 화면 ③ 그 다음은 앱의 이전 메뉴
    BackHandler(enabled = stack.isNotEmpty()) { stack = stack.dropLast(1) }
    BackHandler(enabled = stack.isEmpty() && canGoBackInSearch) { searchVm.back() }

    val runQuery: (String) -> Unit = { q ->
        stack = emptyList()
        searchVm.submit(q)
    }

    ProvideCitationHandler(onCite = runQuery) {
        Box(Modifier.fillMaxSize()) {
            when (val top = stack.lastOrNull()) {
                null -> SearchScreen(
                    vm = searchVm,
                    isWide = split,
                    splitMode = appSettings.splitMode,
                    onSplitMode = container.settings::setSplitMode,
                    onOpenPrecedent = { stack = stack + LawRoute.Precedent(it.serialNo) },
                    onOpenLaw = { stack = stack + LawRoute.Law(it) },
                    onOpenAdminRule = { stack = stack + LawRoute.AdminRule(it.serialNo) },
                )
                is LawRoute.Precedent -> {
                    val vm: PrecedentViewModel = viewModel(factory = factory)
                    PrecedentScreen(vm, top.serialNo, onBack = { stack = stack.dropLast(1) })
                }
                is LawRoute.Law -> {
                    val vm: DocumentViewModel = viewModel(factory = factory)
                    DocumentScreen(vm, load = { loadLaw(top.law) }, onBack = { stack = stack.dropLast(1) }, onOpenTree = runQuery)
                }
                is LawRoute.AdminRule -> {
                    val vm: DocumentViewModel = viewModel(factory = factory)
                    DocumentScreen(vm, load = { loadAdminRule(top.serialNo) }, onBack = { stack = stack.dropLast(1) }, onOpenTree = runQuery)
                }
            }
        }
    }
}
