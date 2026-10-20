package io.github.hdlee73.financenewsradar.data

import android.content.Context
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * 기기에 내장된 Gemini Nano(ML Kit GenAI Prompt API)로 본문을 한국어로 요약한다.
 * Gemini Nano를 쓸 수 없는 기기에서는 [isAvailable]이 false이며 요약 기능 전체가 꺼진 채 기존 화면 그대로 동작한다.
 */
internal object SummaryEngine {
    private const val MAX_INPUT_CHARS = 2_400
    private const val MIN_BODY_CHARS = 150

    private val lock = Mutex()
    private var model: GenerativeModel? = null
    private var availability: Boolean? = null

    /** 이 기기에서 Gemini Nano가 이미 내려받아져 바로 쓸 수 있는지. 한 번 확인한 결과는 재사용한다(내려받는 중이면 다음에 다시 확인). */
    suspend fun isAvailable(): Boolean {
        availability?.let { return it }
        val ok = runCatching {
            val client = model ?: Generation.getClient().also { model = it }
            client.checkStatus() == FeatureStatus.AVAILABLE
        }.getOrDefault(false)
        // 사용 불가는 기록하지 않는다: AICore가 막 준비되는 중일 수 있어 다음 새로고침 때 다시 본다.
        if (ok) availability = true
        return ok
    }

    /** 본문 [body]를 한국어 2문장 안팎으로 요약. 본문이 짧거나 모델이 실패하면 null. */
    suspend fun summarize(title: String, body: String): String? {
        if (body.length < MIN_BODY_CHARS) return null
        val client = model ?: return null
        val prompt = buildString {
            append("다음은 금융 관련 보도자료 또는 연구보고서입니다. 핵심 내용을 한국어로 두 문장, 150자 이내로 요약하세요. ")
            append("원문이 영어 등 외국어여도 요약은 반드시 한국어로 쓰고, 요약문 외의 설명이나 머리말은 쓰지 마세요.\n\n")
            append("제목: ").append(title).append("\n본문: ").append(body.take(MAX_INPUT_CHARS))
        }
        // Gemini Nano는 한 번에 하나씩만 처리하므로 순서대로 실행한다.
        return lock.withLock {
            runCatching { client.generateContent(prompt).candidates.firstOrNull()?.text }.getOrNull()
        }?.let(::clean)
    }

    private fun clean(raw: String): String? {
        val text = raw.trim().removePrefix("요약:").trim().replace(Regex("[*#]+"), "").replace(Regex("\\s*\\n+\\s*"), " ").trim()
        return text.takeIf { it.length >= 10 }?.take(240)
    }
}

/** 요약문 저장소: 같은 자료를 다시 요약하지 않도록 링크별로 기기에 보관(최근 400건). */
internal class SummaryStore(context: Context) {
    private val prefs = context.getSharedPreferences("summary_cache", Context.MODE_PRIVATE)

    fun load(): Map<String, String> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrDefault(emptyMap())
    }

    fun save(all: Map<String, String>) {
        val json = JSONObject()
        all.entries.toList().takeLast(MAX).forEach { json.put(it.key, it.value) }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    private companion object {
        const val KEY = "summaries"
        const val MAX = 400
    }
}

/** 자료 상세 페이지에서 본문 글만 뽑는다. 읽지 못하거나 본문이 없으면 null. */
internal object ArticleText {
    private val skipLink = Regex("""(?i)\.(pdf|hwp|hwpx|docx?|xlsx?|pptx?|zip)(\?|$)|downloadw?\b|/download""")
    private val noise = Regex("(?is)<!--.*?-->|<(script|style|noscript|nav|header|footer|aside|form)\\b.*?</\\1>")
    private val main = Regex("(?is)<(article|main)\\b.*?</\\1>")
    private val tag = Regex("(?s)<[^>]+>")

    suspend fun fetch(context: Context, url: String): String? {
        if (skipLink.containsMatchIn(url)) return null
        // 1차: 가벼운 HTTP. 막히거나 본문이 비면(자바스크립트로 그리는 사이트) 2차로 숨은 WebView로 읽는다.
        val http = runCatching { HtmlFetcher.get(url) }.getOrNull()?.let(::bodyText)
        if (http != null) return http
        val rendered = runCatching { WebViewFetcher.get(context, url) }.getOrNull() ?: return null
        return bodyText(rendered)
    }

    private fun bodyText(html: String): String? {
        val cleaned = noise.replace(html, " ")
        val region = main.find(cleaned)?.value ?: cleaned
        val text = HtmlListParser.compact(HtmlListParser.decodeEntities(tag.replace(region, " ")))
        return text.takeIf { it.length >= 150 }
    }
}
