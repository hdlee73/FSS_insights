package io.github.hdlee73.financenewsradar.data

import io.github.hdlee73.financenewsradar.model.NaverApiType
import io.github.hdlee73.financenewsradar.model.NaverCredentials
import org.junit.Assert.*
import org.junit.Test

class NaverApiRequestTest {
    @Test fun hubCredentialsUseHubHostAndHeadersOnly() {
        val request = NaverApiRequest.create(NaverCredentials("id", "secret", NaverApiType.API_HUB), "bank%20fund", 100, 101)
        assertEquals("https://naverapihub.apigw.ntruss.com/search/v1/news?query=bank%20fund&display=100&start=101&sort=date&format=json", request.url)
        assertEquals(mapOf("X-NCP-APIGW-API-KEY-ID" to "id", "X-NCP-APIGW-API-KEY" to "secret"), request.headers)
        assertFalse(request.url.contains("secret"))
    }
    @Test fun existingCredentialsRetainOriginalEndpointAndHeaders() {
        val request = NaverApiRequest.create(NaverCredentials("old-id", "old-secret", NaverApiType.DEVELOPERS), "bank", 100, 1)
        assertTrue(request.url.startsWith("https://openapi.naver.com/v1/search/news.json?"))
        assertEquals(mapOf("X-Naver-Client-Id" to "old-id", "X-Naver-Client-Secret" to "old-secret"), request.headers)
        assertFalse(request.url.contains("old-secret"))
    }
}
