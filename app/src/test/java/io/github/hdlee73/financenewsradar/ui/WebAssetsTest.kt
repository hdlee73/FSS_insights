package io.github.hdlee73.financenewsradar.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** 앱에 내장한 웹 화면(assets/web)이 빠진 파일 없이 들어 있는지 확인한다(웹에서 sync_web_assets.py 로 복사). */
class WebAssetsTest {
    private val root = listOf("src/main/assets/web", "app/src/main/assets/web").map(::File).first { it.isDirectory }

    @Test
    fun entryFilesExist() {
        listOf("index.html", "js/app.js", "js/core.js", "css/app.css", "js/screens/consumer.js", "js/screens/finsim.js", "js/screens/crypto.js")
            .forEach { assertTrue("$it 없음", File(root, it).isFile) }
    }

    /** 모든 js 의 정적 import·동적 import("./…") 대상 파일이 실제로 있어야 한다. */
    @Test
    fun everyImportedModuleIsBundled() {
        val pattern = Regex("""(?:from\s*|import\(\s*)["'`](\.[^"'`]+)["'`]""")
        val missing = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "js" }.forEach { f ->
            pattern.findAll(f.readText()).forEach { m ->
                val target = File(f.parentFile, m.groupValues[1]).canonicalFile
                if (!target.isFile) missing += "${f.relativeTo(root)} → ${m.groupValues[1]}"
            }
        }
        assertTrue("내장되지 않은 모듈: $missing", missing.isEmpty())
    }

    /** 자료 파일(fetch 대상)도 함께 있어야 한다. */
    @Test
    fun consumerDataIsBundled() {
        listOf("cases", "links", "tips", "scorecard").forEach { assertTrue("data/consumer/$it.json 없음", File(root, "data/consumer/$it.json").isFile) }
    }

    /** 앱 전용 연결: 파일 저장·외부 링크·공유는 앱의 FssNative 로 넘어가야 한다. */
    @Test
    fun coreUsesNativeBridge() {
        val core = File(root, "js/core.js").readText()
        listOf("window.FssNative.openUrl", "window.FssNative.share", "window.FssNative.saveFile").forEach { assertTrue("$it 연결 없음", core.contains(it)) }
    }
}
