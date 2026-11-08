package io.github.hdlee73.financenewsradar.accounting.domain

import java.util.Locale

object Format {
    /** 원 단위 금액을 조·억·만 단위 한글로. 예: 3,161,000,000 → "31.6억원" */
    fun won(v: Long?): String {
        if (v == null) return "-"
        val a = kotlin.math.abs(v.toDouble())
        val sign = if (v < 0) "-" else ""
        return sign + when {
            a >= 1e12 -> "${trim(a / 1e12)}조원"
            a >= 1e8 -> "${trim(a / 1e8)}억원"
            a >= 1e4 -> "${String.format(Locale.KOREA, "%,d", Math.round(a / 1e4))}만원"
            else -> "${String.format(Locale.KOREA, "%,d", Math.round(a))}원"
        }
    }

    private fun trim(x: Double): String {
        val s = if (x >= 100) String.format(Locale.KOREA, "%,.0f", x) else String.format(Locale.KOREA, "%,.1f", x)
        return s.removeSuffix(".0")
    }

    /** yyyyMMdd → yyyy.MM.dd (형식이 다르면 그대로) */
    fun dateYmd(s: String): String =
        if (s.length == 8 && s.all { it.isDigit() }) "${s.substring(0, 4)}.${s.substring(4, 6)}.${s.substring(6)}" else s

    /** 입력창 문자열(쉼표 허용)을 억원 단위 숫자로 → 원. 잘못된 값이면 null */
    fun eokToWon(text: String): Long? {
        val v = text.replace(",", "").trim().toDoubleOrNull() ?: return null
        if (v < 0 || v > 100_000_000) return null  // 1경원(삼성전자 등 대형사 자산도 입력 가능)
        return Math.round(v * 1e8)
    }
}
