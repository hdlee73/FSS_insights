package io.github.hdlee73.financenewsradar.law

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * 서랍 메뉴의 "스마트 법령검색" 화면. 검색 → 위임 트리 → 판례·조문 상세를 이 안에서 오가고,
 * 뒤로가기는 앱을 끄지 않고 직전 화면(결과 → 검색 첫 화면 → 앱의 이전 메뉴)으로 돌아간다.
 */
/** 다른 화면(예: 제재공시의 위반 조문)에서 스마트 법령검색을 특정 검색어로 열 때 넘기는 요청. [nonce]가 바뀌면 다시 검색한다. */
data class LawLaunch(val query: String, val nonce: Long = System.nanoTime())

/**
 * [launch]가 오면 그 검색어로 바로 검색하고 [onLaunchConsumed]로 알린다.
 * [onReturn]이 있으면(다른 화면에서 들어온 경우) 검색 결과 화면에서 뒤로가기 시 들어온 화면으로 바로 돌아간다.
 */
@Composable
fun LawScreen(launch: LawLaunch? = null, onLaunchConsumed: () -> Unit = {}, onReturn: (() -> Unit)? = null) {
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

    LaunchedEffect(launch?.nonce) {
        if (launch != null) {
            runQuery(launch.query)
            onLaunchConsumed()
        }
    }
    // 다른 화면에서 들어왔으면 상세 화면이 없을 때의 뒤로가기는 그 화면으로(위 BackHandler보다 나중에 등록되어 먼저 처리된다).
    BackHandler(enabled = onReturn != null && stack.isEmpty()) { onReturn?.invoke() }

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
