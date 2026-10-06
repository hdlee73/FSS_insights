package io.github.hdlee73.financenewsradar

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import io.github.hdlee73.financenewsradar.pdf.WebPagePdfExporter
import io.github.hdlee73.financenewsradar.ui.theme.FinanceNewsRadarTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ArticleReaderActivity : ComponentActivity() {
    private var webView: WebView? = null
    private val exporter = WebPagePdfExporter()
    private var loading by mutableStateOf(true)
    private var exporting by mutableStateOf(false)
    private var pageError by mutableStateOf<String?>(null)
    private var renderedUrl: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val link = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "기사 원문" }
        if (Uri.parse(link).scheme !in listOf("http", "https")) {
            notifyUser("올바른 기사 링크가 없습니다.")
            finish()
            return
        }
        setContent {
            FinanceNewsRadarTheme {
                BackHandler {
                    if (exporting) notifyUser("PDF 생성이 끝난 뒤 닫아 주세요.")
                    else if (webView?.canGoBack() == true) webView?.goBack()
                    else finish()
                }
                Scaffold(
                    topBar = {
                        Surface {
                            Row(Modifier.fillMaxWidth().statusBarsPadding(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                IconButton(onClick = { if (!exporting) finish() }, enabled = !exporting) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "닫기")
                                }
                                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                TextButton(onClick = {
                                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webView?.url ?: link))) }
                                        .onFailure { notifyUser("브라우저를 열 수 없습니다.") }
                                }, enabled = !exporting) { Text("외부 열기", style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    },
                    bottomBar = {
                        Surface(tonalElevation = 2.dp) {
                            Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp)) {
                                Text(
                                    if (exporting) "PDF 처리 중…" else "기사 공유는 웹페이지 주소를, PDF 공유는 열린 원문 전체를 보냅니다. (PDF는 로그인·유료 제한, 광고가 포함될 수 있음)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { shareLink(title, webView?.url ?: link) }, enabled = !exporting, modifier = Modifier.weight(1f)) { Text("기사 공유") }
                                    Button(onClick = { exportPdf(title) }, enabled = !loading && !exporting && pageError == null, modifier = Modifier.weight(1f)) { Text("PDF 공유") }
                                }
                            }
                        }
                    }
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        if (loading || exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
                        pageError?.let { error ->
                            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { webView?.reload() }) { Text("재시도") }
                            }
                        }
                        AndroidView(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            factory = { context ->
                                WebView(context).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.allowFileAccess = false
                                    settings.allowContentAccess = false
                                    settings.setSupportMultipleWindows(false)
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                            exporting || request.url.scheme !in listOf("http", "https")
                                        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                            if (exporting) exporter.cancel()
                                            loading = true
                                            pageError = null
                                            renderedUrl = null
                                        }
                                        override fun onPageFinished(view: WebView, url: String?) {
                                            view.postVisualStateCallback(System.nanoTime(), object : WebView.VisualStateCallback() {
                                                override fun onComplete(requestId: Long) {
                                                    if (view.url == url) { loading = false; renderedUrl = url }
                                                }
                                            })
                                        }
                                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                            if (request.isForMainFrame) { pageError = "원문을 불러오지 못했습니다. 연결을 확인하거나 외부 열기를 이용해 주세요."; loading = false }
                                        }
                                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                            if (request.isForMainFrame) { pageError = "원문 서버 오류 (${response.statusCode})."; loading = false }
                                        }
                                    }
                                    webView = this
                                    loadUrl(link)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private fun exportPdf(title: String) {
        val view = webView ?: return
        if (loading || exporting || pageError != null || renderedUrl != view.url) return
        exporting = true
        val folder = File(cacheDir, "article_pdfs").apply { mkdirs() }
        // Keep shared files long enough for recipient apps to read their granted URI.
        folder.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
        val name = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(70).ifBlank { "기사" }
        val file = File(folder, "${name}_${System.currentTimeMillis()}.pdf")
        exporter.export(view, file, title) { result ->
            exporting = false
            if (isDestroyed || isFinishing) return@export
            result.onSuccess { pdf ->
                runCatching {
                    val uri = FileProvider.getUriForFile(this, "$packageName.articlefiles", pdf)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, title)
                        clipData = ClipData.newRawUri("기사 PDF", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "기사 PDF 공유"))
                }.onFailure { notifyUser("PDF를 내보내지 못했습니다: ${it.message}") }
            }.onFailure { notifyUser(it.message ?: "PDF 변환에 실패했습니다.") }
        }
    }

    private fun shareLink(title: String, url: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, "$title\n$url")
        }
        runCatching { startActivity(Intent.createChooser(send, "기사 공유")) }
            .onFailure { notifyUser("공유할 수 있는 앱이 없습니다.") }
    }

    private fun notifyUser(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        exporter.cancel()
        webView?.stopLoading()
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "article_url"
        private const val EXTRA_TITLE = "article_title"
        fun open(context: Context, url: String, title: String) {
            context.startActivity(Intent(context, ArticleReaderActivity::class.java).putExtra(EXTRA_URL, url).putExtra(EXTRA_TITLE, title))
        }
    }
}
