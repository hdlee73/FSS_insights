package io.github.hdlee73.financenewsradar.data

import io.github.hdlee73.financenewsradar.model.NaverApiType
import io.github.hdlee73.financenewsradar.model.NaverCredentials

/** Keeps each credential type restricted to its own service and header names. */
data class NaverApiRequest(val url: String, val headers: Map<String, String>) {
    companion object {
        /** 개인 키는 서버(프록시)에만 두고, 앱은 앱 토큰만 보낸다. */
        fun proxy(baseUrl: String, token: String, encodedQuery: String, size: Int, start: Int) = NaverApiRequest(
            "${baseUrl.trimEnd('/')}/news?query=$encodedQuery&display=$size&start=$start&sort=date",
            mapOf("X-App-Token" to token)
        )


        fun create(credentials: NaverCredentials, encodedQuery: String, size: Int, start: Int): NaverApiRequest {
            val parameters = "query=$encodedQuery&display=$size&start=$start&sort=date"
            return when (credentials.apiType) {
                NaverApiType.API_HUB -> NaverApiRequest(
                    "https://naverapihub.apigw.ntruss.com/search/v1/news?$parameters&format=json",
                    mapOf("X-NCP-APIGW-API-KEY-ID" to credentials.clientId, "X-NCP-APIGW-API-KEY" to credentials.clientSecret)
                )
                NaverApiType.DEVELOPERS -> NaverApiRequest(
                    "https://openapi.naver.com/v1/search/news.json?$parameters",
                    mapOf("X-Naver-Client-Id" to credentials.clientId, "X-Naver-Client-Secret" to credentials.clientSecret)
                )
            }
        }
    }
}
