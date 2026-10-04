package io.github.hdlee73.financenewsradar.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONTokener
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 일반 HTTP 요청이 막히거나(403 등) 자바스크립트로 목록을 그리는 사이트를 위해,
 * 숨겨 둔 WebView(실제 크롬 엔진)로 페이지를 열고 화면에 그려진 HTML을 가져온다.
 */
internal object WebViewFetcher {
    private const val TOTAL_TIMEOUT_MS = 35_000L
    private const val POLL_MS = 1_000L
    private const val MAX_POLLS = 15

    suspend fun get(context: Context, url: String, isReady: (String) -> Boolean = { true }): String = withTimeout(TOTAL_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                var webView: WebView? = null
                fun finish(result: Result<String>) {
                    val view = webView
                    webView = null
                    if (view != null) {
                        view.stopLoading()
                        view.destroy()
                    }
                    if (cont.isActive) result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
                }
                try {
                    val view = createWebView(context)
                    webView = view
                    var loaded = false
                    var polls = 0
                    lateinit var poll: Runnable
                    poll = Runnable {
                        val current = webView ?: return@Runnable
                        current.evaluateJavascript(
                            "(function(){return JSON.stringify({t:document.title,h:document.documentElement.outerHTML});})()"
                        ) { raw ->
                            val parsed = decode(raw)
                            polls++
                            val blocked = parsed.first.contains("just a moment", true) ||
                                parsed.first.contains("access denied", true) || parsed.first.contains("attention required", true)
                            // 목록을 나중에 불러오는 사이트가 있어, 기대한 내용이 나타날 때까지 기다린다(최대 MAX_POLLS초).
                            if ((!blocked && parsed.second.length > 3_000 && isReady(parsed.second)) || polls >= MAX_POLLS) {
                                finish(Result.success(parsed.second))
                            } else {
                                handler.postDelayed(poll, POLL_MS)
                            }
                        }
                    }
                    view.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView?, u: String?) {
                            if (!loaded) {
                                loaded = true
                                handler.postDelayed(poll, POLL_MS)
                            }
                        }
                    }
                    cont.invokeOnCancellation { handler.post { finish(Result.failure(java.util.concurrent.CancellationException())) } }
                    view.loadUrl(url)
                } catch (e: Exception) {
                    finish(Result.failure(e))
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context): WebView = WebView(context.applicationContext).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = false
        settings.blockNetworkImage = true
    }

    /** evaluateJavascript는 JSON 문자열을 한 번 더 따옴표로 감싸 돌려준다. */
    private fun decode(raw: String?): Pair<String, String> {
        if (raw.isNullOrBlank() || raw == "null") return "" to ""
        return runCatching {
            val inner = JSONTokener(raw).nextValue() as? String ?: return "" to ""
            val obj = org.json.JSONObject(inner)
            obj.optString("t") to obj.optString("h")
        }.getOrDefault("" to "")
    }
}
