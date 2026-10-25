package io.github.hdlee73.financenewsradar.law

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hdlee73.everylaw.core.data.remote.LawApiService
import com.hdlee73.everylaw.core.data.repository.RemoteLawRepository
import com.hdlee73.everylaw.core.data.repository.SwitchingLawRepository
import com.hdlee73.everylaw.core.domain.BuildPdfExtrasUseCase
import com.hdlee73.everylaw.core.domain.GetAdminRuleUseCase
import com.hdlee73.everylaw.core.domain.GetDelegationTreeUseCase
import com.hdlee73.everylaw.core.domain.GetLawArticlesUseCase
import com.hdlee73.everylaw.core.domain.GetLawVersionsUseCase
import com.hdlee73.everylaw.core.domain.GetPrecedentUseCase
import com.hdlee73.everylaw.core.domain.LawRepository
import com.hdlee73.everylaw.core.domain.SearchKeywordUseCase
import io.github.hdlee73.financenewsradar.data.NewsProxy
import io.github.hdlee73.financenewsradar.law.data.SettingsStore
import io.github.hdlee73.financenewsradar.law.library.DocumentViewModel
import io.github.hdlee73.financenewsradar.law.library.PrecedentViewModel
import io.github.hdlee73.financenewsradar.law.search.SearchViewModel

/**
 * 법령 기능의 의존성 묶음. 국가법령정보 OC 키는 앱에 두지 않고, 뉴스 프록시 서버(/law)가 붙여 준다.
 * 프록시가 연결되지 않은 빌드(포크·로컬)는 데모 데이터로 동작한다.
 */
class LawContainer private constructor(context: Context) {
    val settings = SettingsStore(context)

    private val api = LawApiService.create(
        ocProvider = null,
        baseUrl = NewsProxy.url.trimEnd('/').ifBlank { "https://invalid.example" } + "/law/",
    ) {
        addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("X-App-Token", NewsProxy.token).build())
        }
    }

    val repository: LawRepository = SwitchingLawRepository(
        hasKey = { NewsProxy.isConfigured },
        remote = RemoteLawRepository(api),
    )

    private val getDelegationTree = GetDelegationTreeUseCase(repository)
    private val searchKeyword = SearchKeywordUseCase(repository)
    private val getLawVersions = GetLawVersionsUseCase(repository)
    private val buildPdfExtras = BuildPdfExtrasUseCase(repository)
    private val getLawArticles = GetLawArticlesUseCase(repository)
    private val getPrecedent = GetPrecedentUseCase(repository)
    private val getAdminRule = GetAdminRuleUseCase(repository)

    val viewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer { SearchViewModel(getDelegationTree, searchKeyword, getLawVersions, buildPdfExtras, settings) }
        initializer { PrecedentViewModel(getPrecedent) }
        initializer { DocumentViewModel(getLawArticles, getAdminRule) }
    }

    companion object {
        @Volatile private var instance: LawContainer? = null

        fun get(context: Context): LawContainer =
            instance ?: synchronized(this) { instance ?: LawContainer(context.applicationContext).also { instance = it } }
    }
}
