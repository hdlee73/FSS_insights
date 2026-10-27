package io.github.hdlee73.financenewsradar.data

import android.content.Context
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * 기기에 내장된 Gemini Nano(ML Kit GenAI Prompt API)로 본문을 한국어로 요약한다.
 * Gemini Nano를 쓸 수 없는 기기에서는 [isAvailable]이 false이며 요약 기능 전체가 꺼진 채 기존 화면 그대로 동작한다.
 */
/** 요약 작업의 결과: 성공하면 [text], 실패하면 사용자에게 보일 짧은 [reason]. */
internal class Outcome(val text: String? = null, val reason: String? = null)

internal object SummaryEngine {
    private const val MAX_INPUT_CHARS = 3_200
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

    /** 본문 [body]를 한국어 2문장 안팎으로 요약. 실패하면 [Outcome.reason]에 이유를 담는다. */
    suspend fun summarize(title: String, body: String, kind: String = "금융 관련 보도자료 또는 연구보고서", focus: String = "핵심 내용"): Outcome {
        if (body.length < MIN_BODY_CHARS) return Outcome(reason = "본문이 너무 짧음")
        val client = model ?: return Outcome(reason = "Gemini Nano 준비 안 됨")
        val prompt = buildString {
            append("다음은 ").append(kind).append("입니다. ").append(focus).append("을 한국어로 두 문장, 150자 이내로 요약하세요. ")
            append("원문이 영어 등 외국어여도 요약은 반드시 한국어로 쓰고, 요약문 외의 설명이나 머리말은 쓰지 마세요.\n\n")
            append("제목: ").append(title).append("\n본문: ").append(body.take(capFor(body)))
        }
        // Gemini Nano는 한 번에 하나씩만 처리하므로 순서대로 실행한다.
        val raw = try {
            lock.withLock { client.generateContent(prompt).candidates.firstOrNull()?.text }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            return Outcome(reason = "AI 응답 실패(${e.javaClass.simpleName}${e.message?.let { ": " + it.take(40) }.orEmpty()})")
        }
        val text = raw?.let(::clean) ?: return Outcome(reason = "AI 응답이 비어 있음")
        return Outcome(text = text)
    }

    /** 한글은 글자당 토큰이 많아 입력 한도(4천 토큰)에 맞게 더 짧게 자른다. */
    private fun capFor(body: String): Int {
        val sample = body.take(400)
        val hangul = sample.count { it in '가'..'힣' }
        return if (hangul * 3 > sample.length) 1_500 else MAX_INPUT_CHARS
    }

    private fun clean(raw: String): String? {
        val text = raw.trim().removePrefix("요약:").trim().replace(Regex("[*#]+"), "").replace(Regex("\\s*\\n+\\s*"), " ").trim()
        return text.takeIf { it.length >= 10 }?.take(240)
    }
}

/** 요약문 저장소: 같은 자료를 다시 요약하지 않도록 링크별로 기기에 보관(최근 400건). */
internal class SummaryStore(context: Context, name: String = "summary_cache") {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

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
    private val pdfLink = Regex("""(?i)\.pdf(\?|$)|common/downloadw?\?""")
    private val skipLink = Regex("""(?i)\.(hwp|hwpx|docx?|xlsx?|pptx?|zip)(\?|$)""")
    private val noise = Regex("(?is)<!--.*?-->|<(script|style|noscript|nav|header|footer|aside|form)\\b.*?</\\1>")
    private val main = Regex("(?is)<(article|main)\\b.*?</\\1>")
    private val tag = Regex("(?s)<[^>]+>")

    /** 본문 글. 제목과 관련 없는 글(엉뚱한 페이지가 열린 경우)이면 이유만 담아 돌려준다. */
    suspend fun fetch(context: Context, url: String, title: String): Outcome {
        if (skipLink.containsMatchIn(url)) return Outcome(reason = "문서 파일(HWP·오피스)은 읽을 수 없음")
        // 1차: 가벼운 HTTP. 막히거나 본문이 비면(자바스크립트로 그리는 사이트) 2차로 숨은 WebView로 읽는다.
        val html = runCatching { HtmlFetcher.get(url) }.getOrNull()
        val http = html?.let(::bodyText)
        if (http != null && relatedTo(title, http)) return Outcome(text = http)
        val rendered = runCatching { WebViewFetcher.get(context, url) }.getOrNull()
            ?: return Outcome(reason = if (html == null) "페이지를 읽지 못함" else "본문이 짧거나 없음")
        val body = bodyText(rendered) ?: return Outcome(reason = "본문이 짧거나 없음")
        return if (relatedTo(title, body)) Outcome(text = body) else Outcome(reason = "제목과 맞지 않는 페이지가 열림")
    }

    fun isPdf(url: String) = pdfLink.containsMatchIn(url)

    /** 제목의 낱말 중 40% 이상이 본문에 있어야 같은 자료로 본다(목록 페이지 등이 열려 모든 자료가 같은 요약이 되는 것을 막는다). */
    private fun relatedTo(title: String, body: String): Boolean {
        val words = title.split(Regex("[\\s,.:;()\\[\\]\"'‘’“”/-]+")).filter { it.length >= 2 }
        if (words.isEmpty()) return true
        val lower = body.lowercase()
        return words.count { lower.contains(it.lowercase()) } * 100 >= words.size * 40
    }

    private fun bodyText(html: String): String? {
        val cleaned = noise.replace(html, " ")
        val region = main.find(cleaned)?.value ?: cleaned
        val text = HtmlListParser.compact(HtmlListParser.decodeEntities(tag.replace(region, " ")))
        return text.takeIf { it.length >= 150 }
    }
}

/** PDF 링크를 내려받아 앞쪽 본문 글을 뽑는다(표지를 건너뛰고 2~5쪽). 실패하면 이유를 담는다. */
internal object PdfText {
    private const val MAX_BYTES = 10 * 1024 * 1024
    private const val IN_PAGE_BYTES = 6 * 1024 * 1024
    private var initialized = false

    /** 일반 요청으로 받는다. 실패하면 이유 글을, 성공하면 null. */
    private fun download(url: String, file: File): String? {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 25_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36")
            connection.setRequestProperty("Accept", "application/pdf,*/*;q=0.8")
            connection.setRequestProperty("Referer", URI(url).let { "${it.scheme}://${it.host}/" })
            val code = connection.responseCode
            if (code !in 200..299) return "서버 응답 $code"
            // 너무 큰 PDF는 앞쪽 [MAX_BYTES]만 받는다(앞쪽 쪽의 글은 대개 파일 앞부분에 있다).
            connection.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0
                    while (total < MAX_BYTES) {
                        val read = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES - total))
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        total += read
                    }
                }
            }
            null
        } finally {
            connection.disconnect()
        }
    }

    suspend fun extract(context: Context, url: String): Outcome {
        // 요청이 너무 잦아 서버가 429로 제한하면 잠시 기다렸다가 다시 시도한다.
        var attempt = 0
        while (true) {
            val outcome = extractOnce(context, url)
            val limited = outcome.reason?.contains("429") == true
            if (!limited || attempt >= 3) return outcome
            attempt++
            kotlinx.coroutines.delay(4_000L * attempt)
        }
    }

    private suspend fun extractOnce(context: Context, url: String): Outcome {
        // 여러 건을 동시에 처리할 수 있으므로 호출마다 다른 임시 파일을 쓴다.
        val file = withContext(Dispatchers.IO) { File.createTempFile("summary-source", ".pdf", context.cacheDir) }
        try {
            var failure = withContext(Dispatchers.IO) { runCatching { download(url, file) }.getOrElse { "${it.javaClass.simpleName}" } }
            if (failure != null) {
                // 직접 요청을 막는 사이트(403 등): 같은 사이트 페이지를 연 WebView 안에서 같은 세션으로 받아 본다.
                val origin = runCatching { URI(url).let { "${it.scheme}://${it.host}/" } }.getOrNull()
                val viaPage = origin?.let { runCatching { WebViewFetcher.fetchFileInPage(context, it, url, IN_PAGE_BYTES) }.getOrNull() }
                if (viaPage != null && viaPage.isNotEmpty() && !viaPage.startsWith("ERR:")) {
                    val bytes = runCatching { android.util.Base64.decode(viaPage, android.util.Base64.DEFAULT) }.getOrNull()
                    if (bytes != null && bytes.isNotEmpty()) {
                        withContext(Dispatchers.IO) { file.writeBytes(bytes) }
                        failure = null
                    }
                }
                if (failure != null) {
                    val detail = viaPage?.takeIf { it.startsWith("ERR:") }?.let { " · 브라우저 요청도 실패(${it.drop(4).take(30)})" }.orEmpty()
                    return Outcome(reason = "PDF 내려받기 실패($failure$detail)")
                }
            }
            return parse(context, file)
        } finally {
            withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { file.delete() }
        }
    }

    private suspend fun parse(context: Context, file: File): Outcome = withContext(Dispatchers.IO) {
        try {
            if (file.length() == 0L) return@withContext Outcome(reason = "PDF 내려받기 실패(빈 파일)")
            try {
                if (!initialized) {
                    com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext)
                    initialized = true
                }
                com.tom_roush.pdfbox.pdmodel.PDDocument.load(file).use { doc ->
                    val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                    stripper.sortByPosition = true
                    val pages = doc.numberOfPages
                    // 첫 쪽은 표지인 경우가 많아, 쪽이 3장 이상이면 2쪽부터 읽는다.
                    stripper.startPage = if (pages >= 3) 2 else 1
                    stripper.endPage = minOf(pages, 5)
                    val text = HtmlListParser.compact(stripper.getText(doc))
                    if (text.length >= 150) Outcome(text = text) else Outcome(reason = "PDF에서 읽을 글이 없음(스캔본일 수 있음)")
                }
            } catch (e: Throwable) {
                Outcome(reason = "PDF 읽기 실패(${e.javaClass.simpleName}${e.message?.let { ": " + it.take(40) }.orEmpty()})")
            }
        } catch (e: Throwable) {
            Outcome(reason = "PDF 내려받기 실패(${e.javaClass.simpleName})")
        } finally {
            file.delete()
        }
    }
}
