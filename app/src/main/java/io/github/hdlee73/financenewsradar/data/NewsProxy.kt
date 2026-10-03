package io.github.hdlee73.financenewsradar.data

import io.github.hdlee73.financenewsradar.BuildConfig
import io.github.hdlee73.financenewsradar.model.NaverCredentials

/** 빌드할 때 주입되는 뉴스 프록시 주소·앱 토큰. 비어 있으면 서버가 아직 연결되지 않은 것이다. */
object NewsProxy {
    val url: String get() = BuildConfig.NEWS_PROXY_URL
    val token: String get() = BuildConfig.NEWS_PROXY_TOKEN
    val isConfigured: Boolean get() = url.isNotBlank()

    fun naverAvailable(credentials: NaverCredentials): Boolean = credentials.isComplete || isConfigured
}
