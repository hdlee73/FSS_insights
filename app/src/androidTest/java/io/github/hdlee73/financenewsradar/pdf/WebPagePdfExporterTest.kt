package io.github.hdlee73.financenewsradar.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hdlee73.financenewsradar.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class WebPagePdfExporterTest {
    @Test
    fun exportsOffscreenPagesAndProvidesReadableShareUri() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "article_pdfs/test-multipage.pdf")
        val done = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val exporter = WebPagePdfExporter()
        var webView: WebView? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = WebView(activity)
                webView = view
                activity.setContentView(view)
                var started = false
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                            override fun onComplete(requestId: Long) {
                                if (started) return
                                started = true
                                exporter.export(view, file, "한글 기사 PDF") { result ->
                                    error.set(result.exceptionOrNull())
                                    done.countDown()
                                }
                            }
                        })
                    }
                }
                view.loadDataWithBaseURL("https://example.test/", """
                    <html><head><meta charset="utf-8"><style>
                    body { font-family:sans-serif; font-size:20px; }
                    section { page-break-after:always; } section:last-child { page-break-after:auto; }
                    </style></head><body>
                    <section><h1>FIRST PAGE / 첫 번째 기사</h1><p>Visible article text, source and date.</p></section>
                    <section><h1>LAST PAGE / 마지막 기사</h1><p>This page is outside the initial viewport.</p></section>
                    </body></html>
                """.trimIndent(), "text/html", "UTF-8", null)
            }
            assertTrue("WebView PDF callback timed out", done.await(65, TimeUnit.SECONDS))
            error.get()?.let { throw AssertionError("PDF generation failed", it) }
            assertTrue(file.length() > 100)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.articlefiles", file)
            context.contentResolver.openFileDescriptor(uri, "r")!!.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    assertTrue("Off-screen article page must also be exported", renderer.pageCount >= 2)
                    for (index in listOf(0, renderer.pageCount - 1)) {
                        renderer.openPage(index).use { page ->
                            val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            var ink = 0
                            for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
                                if ((bitmap.getPixel(x, y) and 0x00FFFFFF) < 0x00EEEEEE) ink++
                            }
                            assertTrue("PDF page $index must contain visible text", ink > 20)
                            bitmap.recycle()
                        }
                    }
                }
            }
            scenario.onActivity { exporter.cancel(); webView?.destroy() }
        }
        file.delete()
    }
}
