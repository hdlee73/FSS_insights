package io.github.hdlee73.financenewsradar.data

import android.content.Context
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
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

    /** 이미지 입력을 지원하지 않는 기기로 한 번 확인되면 이후 PDF는 시도하지 않는다. */
    private var imageUnsupported = false

    /** PDF 앞쪽 쪽 이미지로 한국어 요약(Gemini Nano가 이미지 입력을 지원하는 기기에서만 동작, 아니면 null). */
    suspend fun summarizePages(title: String, pages: List<Bitmap>): String? {
        if (imageUnsupported || pages.isEmpty()) return null
        val client = model ?: return null
        val prompt = "위 이미지는 금융 관련 영문 보고서의 일부 쪽입니다. 제목은 \"$title\"입니다. " +
            "핵심 내용을 한국어로 두 문장, 150자 이내로 요약하세요. 요약문 외의 설명이나 머리말은 쓰지 마세요."
        return lock.withLock {
            runCatching {
                // 요청은 이미지 한 장 + 글 하나만 받으므로 여러 쪽을 세로로 이어 붙여 한 장으로 만든다.
                val stitched = Bitmap.createBitmap(pages.maxOf { it.width }, pages.sumOf { it.height }, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(stitched)
                canvas.drawColor(Color.WHITE)
                var top = 0f
                for (page in pages) { canvas.drawBitmap(page, 0f, top, null); top += page.height }
                client.generateContent(generateContentRequest(ImagePart(stitched), TextPart(prompt)) {}).candidates.firstOrNull()?.text
            }.onFailure { imageUnsupported = true }.getOrNull()
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
    private val pdfLink = Regex("""(?i)\.pdf(\?|$)""")
    private val skipLink = Regex("""(?i)\.(hwp|hwpx|docx?|xlsx?|pptx?|zip)(\?|$)|downloadw?\b|/download""")
    private val noise = Regex("(?is)<!--.*?-->|<(script|style|noscript|nav|header|footer|aside|form)\\b.*?</\\1>")
    private val main = Regex("(?is)<(article|main)\\b.*?</\\1>")
    private val tag = Regex("(?s)<[^>]+>")

    /** 본문 글. 제목과 관련 없는 글(엉뚱한 페이지가 열린 경우)이면 null. */
    suspend fun fetch(context: Context, url: String, title: String): String? {
        if (skipLink.containsMatchIn(url) || pdfLink.containsMatchIn(url)) return null
        // 1차: 가벼운 HTTP. 막히거나 본문이 비면(자바스크립트로 그리는 사이트) 2차로 숨은 WebView로 읽는다.
        val http = runCatching { HtmlFetcher.get(url) }.getOrNull()?.let(::bodyText)?.takeIf { relatedTo(title, it) }
        if (http != null) return http
        val rendered = runCatching { WebViewFetcher.get(context, url) }.getOrNull() ?: return null
        return bodyText(rendered)?.takeIf { relatedTo(title, it) }
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

/** PDF 링크의 앞쪽 몇 쪽을 그림으로 만든다(본문 글을 못 읽는 PDF 요약용). 내려받기 실패 시 null. */
internal object PdfPages {
    private const val MAX_BYTES = 12 * 1024 * 1024
    private const val WIDTH = 720

    suspend fun render(context: Context, url: String): List<Bitmap>? = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "summary-source.pdf")
        try {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36")
                if (connection.responseCode !in 200..299) return@withContext null
                if (connection.contentLengthLong > MAX_BYTES) return@withContext null
                connection.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            } finally {
                connection.disconnect()
            }
            if (file.length() == 0L || file.length() > MAX_BYTES) return@withContext null
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    val count = renderer.pageCount
                    // 첫 쪽은 표지인 경우가 많아, 쪽이 충분하면 2~4쪽(요약문이 있는 쪽)을 쓴다.
                    val indices = if (count >= 4) listOf(1, 2, 3) else (0 until minOf(count, 3)).toList()
                    indices.map { index ->
                        renderer.openPage(index).use { page ->
                            val height = (WIDTH.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bitmap
                        }
                    }
                }
            }
        } catch (e: Exception) {
            null
        } finally {
            file.delete()
        }
    }
}
