package io.github.hdlee73.financenewsradar.pdf

import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.webkit.WebView
import java.io.File

/** Exports the loaded document, including off-screen pages, using WebView's PDF renderer. */
class WebPagePdfExporter {
    private val handler = Handler(Looper.getMainLooper())
    private var cancelActive: (() -> Unit)? = null

    fun cancel() { cancelActive?.invoke() }

    fun export(webView: WebView, destination: File, title: String, onResult: (Result<File>) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(cancelActive == null) { "PDF 생성이 이미 진행 중입니다." }
        val adapter = webView.createPrintDocumentAdapter(title)
        val signal = CancellationSignal()
        var descriptor: ParcelFileDescriptor? = null
        var completed = false
        lateinit var timeout: Runnable
        fun finish(result: Result<File>) {
            if (completed) return
            completed = true
            handler.removeCallbacks(timeout)
            cancelActive = null
            runCatching { descriptor?.close() }
            runCatching { adapter.onFinish() }
            if (result.isFailure) destination.delete()
            onResult(result)
        }
        fun fail(message: String) = finish(Result.failure(IllegalStateException(message)))
        timeout = Runnable {
            signal.cancel()
            fail("PDF 생성 시간이 초과되었습니다. 페이지를 다시 연 뒤 시도해 주세요.")
        }
        cancelActive = {
            signal.cancel()
            fail("PDF 생성을 취소했습니다.")
        }
        handler.postDelayed(timeout, 60_000)
        runCatching {
            destination.parentFile?.mkdirs()
            descriptor = ParcelFileDescriptor.open(destination,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
            val attributes = PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(PrintAttributes.Resolution("pdf", "PDF", 300, 300))
                .setMinMargins(PrintAttributes.Margins(250, 250, 250, 250))
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build()
            adapter.onStart()
            adapter.onLayout(null, attributes, signal, object : PrintDocumentAdapter.LayoutResultCallback() {
                override fun onLayoutFinished(info: PrintDocumentInfo, changed: Boolean) {
                    if (completed) return
                    runCatching {
                        adapter.onWrite(arrayOf(PageRange.ALL_PAGES), requireNotNull(descriptor), signal,
                            object : PrintDocumentAdapter.WriteResultCallback() {
                                override fun onWriteFinished(pages: Array<out PageRange>) {
                                    if (completed) return
                                    if (destination.length() > 0) finish(Result.success(destination))
                                    else fail("빈 PDF가 생성되었습니다. 원문이 표시되는지 확인해 주세요.")
                                }
                                override fun onWriteFailed(error: CharSequence?) = fail(error?.toString() ?: "PDF 저장에 실패했습니다.")
                                override fun onWriteCancelled() = fail("PDF 저장을 취소했습니다.")
                            })
                    }.onFailure { finish(Result.failure(it)) }
                }
                override fun onLayoutFailed(error: CharSequence?) = fail(error?.toString() ?: "이 페이지를 PDF로 변환할 수 없습니다.")
                override fun onLayoutCancelled() = fail("PDF 생성을 취소했습니다.")
            }, Bundle())
        }.onFailure { finish(Result.failure(it)) }
    }
}
