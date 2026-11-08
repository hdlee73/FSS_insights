package io.github.hdlee73.financenewsradar.accounting.data

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 데이터 읽기 순서: ① 원격 최신(raw.githubusercontent.com) → ② 기기에 보관한 직전 성공본 → ③ 앱에 내장한 시드.
 * 네트워크가 끊겨도 앞서 받은 자료나 내장 자료로 항상 화면이 뜬다.
 */
class AccountingRepository(private val context: Context) {

    data class Loaded<T>(val data: Dataset<T>, val source: DataSource)

    suspend fun loadAudit(): Loaded<AuditAction> = load("audit_actions.json", DataParser::parseAudit)
    suspend fun loadDisclosure(): Loaded<DisclosureCase> = load("disclosure_sanctions.json", DataParser::parseDisclosure)
    suspend fun loadBoard(): Loaded<BoardItem> = load("board_index.json", DataParser::parseBoard)

    /** 기준서 목록은 앱에 내장된 자료를 쓴다(공개 목록이라 자주 바뀌지 않음). */
    suspend fun loadStandards(): List<StandardItem> = withContext(Dispatchers.IO) {
        runCatching { DataParser.parseStandards(context.assets.open("seed/standards.json").bufferedReader().use { it.readText() }).items }
            .getOrDefault(emptyList())
    }

    private suspend fun <T> load(name: String, parse: (String) -> Dataset<T>): Loaded<T> = withContext(Dispatchers.IO) {
        val cacheFile = File(context.filesDir, "data/$name")
        // ① 원격
        runCatching {
            val text = download(DATA_BASE + name)
            val parsed = parse(text)
            if (parsed.items.isEmpty()) error("empty")
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.parentFile, "$name.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(cacheFile)) { cacheFile.writeText(text); tmp.delete() }
            return@withContext Loaded(parsed, DataSource.REMOTE)
        }
        // ② 보관본
        runCatching {
            if (cacheFile.exists()) {
                val parsed = parse(cacheFile.readText())
                if (parsed.items.isNotEmpty()) return@withContext Loaded(parsed, DataSource.CACHE)
            }
        }
        // ③ 내장 시드(없거나 깨졌으면 빈 자료)
        val seed = runCatching { parse(context.assets.open("seed/$name").bufferedReader().use { it.readText() }) }
            .getOrElse { Dataset("", emptyList()) }
        Loaded(seed, DataSource.SEED)
    }

    private fun download(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 12_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "FSS-Insights/$APP_VERSION_FOR_UA")
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val DATA_BASE = "https://raw.githubusercontent.com/hdlee73/FSS_insights/accounting-data/"
        private const val APP_VERSION_FOR_UA = "1"
    }
}
