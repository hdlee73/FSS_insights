package io.github.hdlee73.financenewsradar.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.hdlee73.financenewsradar.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 앱 안에 내장된 웹 화면(assets/web)을 보여 준다. 금융소비자 지킴이·제재 양정 시뮬레이터·가상자산 시장 동향은
 * 웹 버전과 같은 코드를 쓰며, 파일은 앱에 들어 있어 오프라인에서도 열린다(가상자산 시세만 인터넷이 필요).
 *
 * - 화면 이동은 WebView 기록에 남아, 뒤로가기는 앱을 닫지 않고 직전 화면·탭으로 돌아간다.
 * - 외부 링크·전화(tel:)·메일은 기기 앱으로 넘기고, 파일 저장·불러오기는 시스템 파일 창을 쓴다.
 * - [route]는 웹 화면 이름(consumer, finsim, crypto). [backEnabled]는 서랍 메뉴가 열려 있을 때 뒤로가기를 서랍에 양보하기 위한 값이다.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebEmbedScreen(route: String, modifier: Modifier = Modifier, backEnabled: Boolean = true) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    // 파일 저장(엑셀·JSON·문서 초안): 웹이 넘긴 내용을 받아 두었다가 사용자가 고른 위치에 쓴다.
    val pendingSave = remember { arrayOfNulls<ByteArray>(1) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val bytes = pendingSave[0]
        pendingSave[0] = null
        if (uri != null && bytes != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("열 수 없음") }
                        .onFailure { CrashLogger.log(context, "웹 화면 파일 저장", it) }
                        .isSuccess
                }
                Toast.makeText(context, if (ok) "저장했습니다." else "저장하지 못했습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }
    // 파일 불러오기(JSON 등): 웹의 <input type="file">을 시스템 파일 창으로 연결한다.
    val pendingChooser = remember { arrayOfNulls<ValueCallback<Array<Uri>>>(1) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingChooser[0]?.onReceiveValue(uri?.let { arrayOf(it) })
        pendingChooser[0] = null
    }

    val bridge = remember {
        FssNativeBridge(context.applicationContext, Handler(Looper.getMainLooper()),
            onOpen = { url -> openWebLink(context, url) },
            onSave = { name, bytes ->
                pendingSave[0] = bytes
                runCatching { saver.launch(name) }.onFailure { pendingSave[0] = null }
            },
            onShare = { title, url ->
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, "$title\n$url")
                runCatching { context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            })
    }

    DisposableEffect(lifecycle, webView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> webView?.onPause()
                Lifecycle.Event.ON_RESUME -> webView?.onResume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose {
            pendingChooser[0]?.onReceiveValue(null)
            pendingChooser[0] = null
            webView?.let { w ->
                // 화면을 떠날 때 WebView를 정리해 메모리가 새지 않게 한다.
                runCatching { (w.parent as? android.view.ViewGroup)?.removeView(w); w.stopLoading(); w.destroy() }
            }
            webView = null
        }
    }
    // 서랍 메뉴가 열려 있지 않을 때만: 웹 안에서 뒤로 갈 곳이 있으면 거기로, 없으면 앱의 이전 화면 처리로 넘어간다.
    BackHandler(enabled = backEnabled && canGoBack) { webView?.goBack() }

    Box(modifier.fillMaxSize().imePadding()) {
        if (failed) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("화면을 표시하지 못했습니다.", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { failed = false; reloadKey++ }, modifier = Modifier.padding(top = 12.dp)) { Text("다시 시도") }
            }
        } else androidx.compose.runtime.key(reloadKey) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true // 긴급대응 진행 표시·시뮬레이션 저장은 localStorage
                            allowFileAccess = false
                            allowContentAccess = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            setSupportZoom(false)
                            cacheMode = WebSettings.LOAD_DEFAULT
                        }
                        overScrollMode = android.view.View.OVER_SCROLL_NEVER
                        // 일부 기기(폴드·태블릿)에서 WebView가 처음 잰 작은 높이에 문서의 100% 높이가 묶여 본문이 잘려 보이는 일이 있어,
                        // 실제 뷰 크기를 CSS 변수(--app-h/--app-w)로 직접 넘겨 화면이 항상 뷰 크기를 따르게 한다.
                        addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
                            val d = v.resources.displayMetrics.density
                            val h = ((b - t) / d).toInt()
                            val w = ((r - l) / d).toInt()
                            if (h > 0 && w > 0) {
                                (v as WebView).evaluateJavascript(
                                    "(function(){var e=document.documentElement;e.style.setProperty('--app-h','${h}px');e.style.setProperty('--app-w','${w}px');dispatchEvent(new Event('resize'));})()",
                                    null
                                )
                            }
                        }
                        addJavascriptInterface(bridge, "FssNative")
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                                serveAsset(ctx, request.url)

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                if (request.url.host == ASSET_HOST) return false
                                openWebLink(ctx, request.url.toString())
                                return true
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                // 처음 그릴 때도 현재 뷰 크기를 한 번 더 넘긴다(레이아웃 변경 알림보다 늦게 로드되는 경우 대비).
                                view.requestLayout()
                            }

                            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                canGoBack = view.canGoBack()
                            }

                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                CrashLogger.log(ctx, "웹 화면 렌더러 종료(충돌=${detail.didCrash()})", IllegalStateException("render process gone"))
                                webView = null
                                failed = true
                                view.post { runCatching { view.destroy() } }
                                return true // 앱은 계속 실행하고, 사용자가 '다시 시도'로 새 WebView를 만든다.
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            // 내장 화면의 자바스크립트 오류를 로컬 로그 파일에 남긴다(본문이 비는 문제를 추적하기 위함).
                            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                                if (message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                                    CrashLogger.log(ctx, "웹 화면 오류[$route] ${message.sourceId()}:${message.lineNumber()}", IllegalStateException(message.message()))
                                }
                                return true
                            }

                            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                                pendingChooser[0]?.onReceiveValue(null)
                                pendingChooser[0] = callback
                                return runCatching { picker.launch(arrayOf("*/*")); true }.getOrElse {
                                    pendingChooser[0] = null
                                    callback.onReceiveValue(null)
                                    true
                                }
                            }
                        }
                        loadUrl("https://$ASSET_HOST/web/index.html#/$route")
                        webView = this
                    }
                },
            )
        }
    }
}

private const val ASSET_HOST = "appassets.androidplatform.net"

/** https://appassets.androidplatform.net/web/… 요청을 앱 assets/web/… 파일로 답한다(그 밖의 주소는 막지 않고 통과시킨다). */
private fun serveAsset(context: Context, uri: Uri): WebResourceResponse? {
    if (uri.host != ASSET_HOST) return null
    val path = uri.path.orEmpty().trimStart('/')
    // assets/web 밖을 읽지 못하게 한다(.. 포함 경로 거부).
    if (!path.startsWith("web/") || path.split('/').any { it == ".." || it.isEmpty() }) return notFound()
    return try {
        val ext = MimeTypeMap.getFileExtensionFromUrl(path).lowercase()
        val mime = when (ext) {
            "js", "mjs" -> "text/javascript"
            "html" -> "text/html"
            "css" -> "text/css"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
        val enc = if (mime.startsWith("text/") || mime == "application/json") "utf-8" else null
        WebResourceResponse(mime, enc, 200, "OK", mapOf("Cache-Control" to "no-cache"), context.assets.open(path))
    } catch (_: java.io.IOException) {
        notFound()
    }
}

private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))

/** http(s)·전화·메일만 기기 앱으로 넘긴다. */
private fun openWebLink(context: Context, url: String) {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
    val action = when (uri.scheme?.lowercase()) {
        "http", "https", "mailto" -> Intent.ACTION_VIEW
        "tel" -> Intent.ACTION_DIAL // 바로 걸지 않고 다이얼러에 번호만 채운다
        else -> return
    }
    try {
        context.startActivity(Intent(action, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "열 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show()
    }
}

/** 웹 코드가 앱 기능을 부르는 통로(window.FssNative). 호출은 WebView의 백그라운드 스레드에서 오므로 화면 동작은 주 스레드로 넘긴다. */
private class FssNativeBridge(
    private val context: Context,
    private val main: Handler,
    private val onOpen: (String) -> Unit,
    private val onSave: (String, ByteArray) -> Unit,
    private val onShare: (String, String) -> Unit,
) {
    @JavascriptInterface
    fun openUrl(url: String) {
        main.post { onOpen(url) }
    }

    @JavascriptInterface
    fun share(title: String, url: String) {
        main.post { onShare(title, url) }
    }

    /** [base64]로 받은 파일 내용을 사용자가 고른 위치에 저장한다. 크기를 제한하고, 읽을 수 없으면 알린다. */
    @JavascriptInterface
    fun saveFile(name: String, @Suppress("UNUSED_PARAMETER") mime: String, base64: String) {
        val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull()
        main.post {
            if (bytes == null || bytes.size > MAX_SAVE_BYTES) {
                Toast.makeText(context, "저장할 수 없는 파일입니다.", Toast.LENGTH_SHORT).show()
            } else {
                onSave(name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "파일" }, bytes)
            }
        }
    }

    private companion object {
        const val MAX_SAVE_BYTES = 25 * 1024 * 1024
    }
}
