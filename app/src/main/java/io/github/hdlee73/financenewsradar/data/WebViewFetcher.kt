package io.github.hdlee73.financenewsradar.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
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
    private const val TOTAL_TIMEOUT_MS = 50_000L
    private const val POLL_MS = 1_000L
    private const val MAX_POLLS = 15
    /** '더보기'로 이어 붙이는 목록은 클릭을 여러 번 해야 하므로 더 오래 기다리고, 목록이 늘지 않으면 일찍 끝낸다. */
    private const val MAX_POLLS_MORE = 36
    private const val STALL_POLLS = 5

    /** [moreLabel]이 있으면 준비될 때까지 그 글자가 든 '더보기' 버튼을 매번 눌러 목록을 늘린다. */
    suspend fun get(context: Context, url: String, moreLabel: String? = null, isReady: (String) -> Boolean = { true }): String = withTimeout(TOTAL_TIMEOUT_MS) {
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
                    var lastSize = 0
                    var stalled = 0
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
                            val html = parsed.second
                            if (moreLabel != null && !blocked && html.length > 3_000) {
                                if (html.length != lastSize) {
                                    lastSize = html.length
                                    stalled = 0
                                } else stalled++
                            }
                            val ready = !blocked && html.length > 3_000 && isReady(html)
                            val limit = if (moreLabel != null) MAX_POLLS_MORE else MAX_POLLS
                            // 목록을 나중에 불러오는 사이트가 있어, 기대한 내용이 나타날 때까지 기다린다.
                            val gaveUp = polls >= limit || (moreLabel != null && polls >= 6 && stalled >= STALL_POLLS)
                            if (ready || gaveUp) {
                                finish(Result.success(html))
                            } else {
                                if (moreLabel != null && !blocked) current.evaluateJavascript(clickMoreScript(moreLabel), null)
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

    /** 자바스크립트 fetch() 결과를 받는 다리. */
    private class FetchBridge(val onResult: (String) -> Unit) {
        @android.webkit.JavascriptInterface
        fun done(text: String?) = onResult(text.orEmpty())
    }

    /**
     * [pageUrl]을 WebView로 연 뒤(쿠키·출처 확인 통과) 같은 사이트의 [path]를 그 페이지 안에서 fetch()로 받아 글을 돌려준다.
     * 일반 HTTP 요청은 막고 브라우저 안 요청만 받아 주는 RSS·ajax 주소용. 못 받으면 빈 문자열.
     */
    suspend fun fetchInPage(context: Context, pageUrl: String, path: String): String = withTimeout(TOTAL_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                var webView: WebView? = null
                fun finish(text: String) {
                    val view = webView
                    webView = null
                    if (view != null) {
                        view.stopLoading()
                        view.destroy()
                    }
                    if (cont.isActive) cont.resume(text)
                }
                try {
                    val view = createWebView(context)
                    webView = view
                    var started = false
                    view.addJavascriptInterface(FetchBridge { text -> handler.post { finish(text) } }, "FssBridge")
                    view.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView?, u: String?) {
                            if (started) return
                            started = true
                            handler.postDelayed({
                                webView?.evaluateJavascript(
                                    "(function(){fetch(" + org.json.JSONObject.quote(path) + ",{credentials:'include'})" +
                                        ".then(function(r){return r.text();}).then(function(t){FssBridge.done(t);})" +
                                        ".catch(function(e){FssBridge.done('');});})()",
                                    null
                                )
                            }, 1_500)
                        }
                    }
                    cont.invokeOnCancellation { handler.post { finish("") } }
                    view.loadUrl(pageUrl)
                } catch (e: Exception) {
                    finish("")
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context): WebView = WebView(context.applicationContext).apply {
        // 쿠키는 모두 허용(쿠키 동의·봇 확인을 거치는 사이트용). 제3자 쿠키도 받는다.
        val view = this
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(view, true)
        // 기본 사용자 에이전트의 WebView 표시("; wv")를 빼 일반 크롬처럼 보이게 한다.
        settings.userAgentString = settings.userAgentString.replace("; wv", "")
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = false
        settings.blockNetworkImage = true
    }

    /**
     * 글자에 [label]이 든 가장 안쪽 버튼·링크를 눌러 목록을 더 불러온다.
     * 메뉴에도 같은 글자가 있을 수 있어 화면에서 가장 아래쪽(목록 끝)에 있는 것을 고르고,
     * 스크롤로 불러오는 사이트도 있어 그 위치까지 내려 둔 뒤 click()과 마우스 이벤트를 모두 보낸다.
     */
    private fun clickMoreScript(label: String): String =
        "(function(){var l=" + org.json.JSONObject.quote(label) + ";var e=document.querySelectorAll('a,button,input[type=button],input[type=submit],span,div,li,p');" +
            "var best=null,bestTop=-1;" +
            "for(var i=0;i<e.length;i++){var n=e[i];var t=(n.innerText||n.value||'').replace(/\\s+/g,'');" +
            "if(t.length<=l.length+6&&t.indexOf(l)>=0&&n.offsetParent!==null){var r=n.getBoundingClientRect();var top=r.top+window.scrollY;" +
            "if(r.width>0&&r.height>0&&top>=bestTop){best=n;bestTop=top;}}}" +
            "if(!best){window.scrollTo(0,document.body.scrollHeight);return false;}" +
            "best.scrollIntoView({block:'center'});" +
            "['mousedown','mouseup','click'].forEach(function(k){best.dispatchEvent(new MouseEvent(k,{bubbles:true,cancelable:true,view:window}));});" +
            "if(best.click)best.click();return true;})()"

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
