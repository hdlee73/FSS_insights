package io.github.hdlee73.financenewsradar.accounting.data

import io.github.hdlee73.financenewsradar.accounting.ui.screens.groupRank
import org.junit.Test
import org.junit.Assert.assertEquals

class SearchOrderTest {
    @Test
    fun groupsAreOrderedAsRequested() {
        val shuffled = listOf("내부회계관리제도", "회계감사기준", "기타 기준서", "K-IFRS", "새묶음", "일반기업회계기준")
        assertEquals(
            listOf("K-IFRS", "일반기업회계기준", "기타 기준서", "회계감사기준", "내부회계관리제도", "새묶음"),
            shuffled.sortedBy { groupRank(it) }
        )
    }
}
