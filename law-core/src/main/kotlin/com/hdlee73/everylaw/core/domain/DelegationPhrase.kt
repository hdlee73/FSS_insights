package com.hdlee73.everylaw.core.domain

import com.hdlee73.everylaw.core.model.Article
import com.hdlee73.everylaw.core.model.ItemPath

/**
 * 법률 조문의 어느 위치가 하위 법규에 "위임"하고 있는지 본문의 위임 문구로 판단한다.
 * 시행령이 법 조문을 인용했다는 사실만으로는 위임이 아니다(예: 사무 목록에서 법 조문을 나열한 경우).
 * 인용한 위치(또는 그 아래)에 "대통령령으로 정하는"·"금융위원회가 정하여 고시"처럼 위임 문구가 있어야 위임으로 본다.
 */
object DelegationPhrase {
    private val PHRASE = Regex(
        """(?:대통령령|총리령|[가-힣]{1,12}부령|[가-힣]{1,12}규칙|[가-힣]{1,12}령|고시|정관|조례)[^\n.]{0,40}?(?:정하|정한|위임|고시)""" +
            """|위임\s*(?:하|한|받)|(?:정하여|정해)\s*고시"""
    )

    fun has(text: String): Boolean = PHRASE.containsMatchIn(text)

    /**
     * [article]에서 [path] 위치(그 아래 호·목 포함)가 위임 문구를 갖는지.
     * 빈 위치는 조 전체, [ItemPath.LEAD]는 항 앞 본문. 위치를 찾지 못하면 가까운 상위 위치로 올라간다.
     */
    fun delegatesAt(article: Article, path: ItemPath): Boolean {
        val parsed = article.parsed
        if (path.isEmpty) return has(article.content)
        if (path == ItemPath.LEAD) return has(parsed.lead)
        var p = path
        while (!p.isEmpty) {
            parsed.find(p)?.let { item -> return item.flatten().any { has(it.text) } }
            p = p.parent()
        }
        return has(article.content)
    }
}
