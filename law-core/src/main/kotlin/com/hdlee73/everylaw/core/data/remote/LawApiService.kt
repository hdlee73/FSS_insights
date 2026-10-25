package com.hdlee73.everylaw.core.data.remote

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * 국가법령정보 공동활용 Open API (DRF).
 * 문서: https://open.law.go.kr/LSO/openApi/guideList.do
 *
 * - 목록 조회: GET /DRF/lawSearch.do?OC=..&target=..&type=XML&query=..
 * - 본문 조회: GET /DRF/lawService.do?OC=..&target=..&type=XML&(ID|MST)=..
 *
 * 응답은 한글 태그 XML 이므로 문자열로 받아 [com.hdlee73.everylaw.core.parser.DrfParsers]에서 파싱한다.
 * OC(이메일 ID 형태의 인증값)는 OkHttp 인터셉터가 모든 요청에 붙인다(코드·저장소에 넣지 않음).
 */
interface LawApiService {

    /** 현행 법령 검색. search=1 법령명, 2 본문검색 */
    @GET("DRF/lawSearch.do?target=law&type=XML")
    suspend fun searchLaws(
        @Query("query") query: String,
        @Query("search") search: Int = 1,
        @Query("display") display: Int = 20,
        @Query("page") page: Int = 1,
    ): String

    /** 법령 본문. JO=조번호4자리+가지번호2자리 로 특정 조만 받을 수 있다. */
    @GET("DRF/lawService.do?target=law&type=XML")
    suspend fun getLaw(
        @Query("MST") mst: String,
        @Query("JO") jo: String? = null,
    ): String

    /**
     * 시행일 법령 목록. nw=1 연혁, 2 시행예정, 3 현행 (쉼표로 복수 지정).
     * efYd=yyyyMMdd~yyyyMMdd 로 시행일 범위를 줄 수 있다.
     */
    @GET("DRF/lawSearch.do?target=eflaw&type=XML")
    suspend fun searchEffectiveLaws(
        @Query("query") query: String,
        @Query("nw") status: String = "1,2,3",
        @Query("efYd") effectiveRange: String? = null,
        @Query("display") display: Int = 100,
    ): String

    /** 시행일 법령 본문. MST(해당 버전 일련번호)와 efYd(시행일자)를 함께 준다. */
    @GET("DRF/lawService.do?target=eflaw&type=XML")
    suspend fun getEffectiveLaw(
        @Query("MST") mst: String,
        @Query("efYd") effectiveDate: String,
        @Query("JO") jo: String? = null,
    ): String

    /** 3단 비교 본문. knd=1 인용조문, knd=2 위임조문 (법률-시행령-시행규칙 위임 매핑) */
    @GET("DRF/lawService.do?target=thdCmp&type=XML")
    suspend fun getThreeTierComparison(
        @Query("MST") mst: String,
        @Query("knd") kind: Int = 2,
    ): String

    /** 법령 체계도: 상하위 법령과 관련 행정규칙 목록 */
    @GET("DRF/lawService.do?target=lsStmd&type=XML")
    suspend fun getLawHierarchy(
        @Query("MST") mst: String,
    ): String

    /** 행정규칙(훈령·예규·고시 등) 목록. 감독규정·시행세칙도 여기에 속한다. */
    @GET("DRF/lawSearch.do?target=admrul&type=XML")
    suspend fun searchAdminRules(
        @Query("query") query: String,
        @Query("search") search: Int = 1,
        @Query("display") display: Int = 20,
    ): String

    /** 행정규칙 본문. ID=행정규칙 일련번호 */
    @GET("DRF/lawService.do?target=admrul&type=XML")
    suspend fun getAdminRule(
        @Query("ID") serialNo: String,
    ): String

    /** 판례 목록. search=1 사건명, 2 본문검색 */
    @GET("DRF/lawSearch.do?target=prec&type=XML")
    suspend fun searchPrecedents(
        @Query("query") query: String,
        @Query("search") search: Int = 2,
        @Query("display") display: Int = 40,
        @Query("sort") sort: String = "ddes",
    ): String

    /** 판례 본문. ID=판례 일련번호 */
    @GET("DRF/lawService.do?target=prec&type=XML")
    suspend fun getPrecedent(
        @Query("ID") serialNo: String,
    ): String

    companion object {
        const val BASE_URL = "https://www.law.go.kr/"

        fun create(
            ocProvider: (() -> String)?,
            baseUrl: String = BASE_URL,
            configure: OkHttpClient.Builder.() -> Unit = {},
        ): LawApiService {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .apply {
                    // 프록시를 거칠 때는 OC를 서버가 붙이므로 ocProvider를 null로 둔다.
                    if (ocProvider != null) addInterceptor { chain ->
                        val req = chain.request()
                        val url = req.url.newBuilder().addQueryParameter("OC", ocProvider()).build()
                        chain.proceed(req.newBuilder().url(url).build())
                    }
                }
                .apply(configure)
                .build()
            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(ScalarsConverterFactory.create())
                .build()
                .create(LawApiService::class.java)
        }
    }
}
