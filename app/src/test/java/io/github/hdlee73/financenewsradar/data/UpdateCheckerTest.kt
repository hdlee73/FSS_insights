package io.github.hdlee73.financenewsradar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test fun parsesTagsAndSuffixes() {
        assertEquals(listOf(0, 12, 1), UpdateChecker.parseVersion("v0.12.1"))
        assertEquals(listOf(0, 12, 0), UpdateChecker.parseVersion("0.12.0-debug"))
        assertNull(UpdateChecker.parseVersion("latest"))
    }

    @Test fun comparesNumerically() {
        assertTrue(UpdateChecker.isNewer("v0.13.0", "0.12.0"))
        assertTrue(UpdateChecker.isNewer("0.12.10", "0.12.9"))
        assertTrue(UpdateChecker.isNewer("1.0", "0.99.9"))
        assertFalse(UpdateChecker.isNewer("0.12.0", "0.12.0-debug"))
        assertFalse(UpdateChecker.isNewer("0.11.9", "0.12.0"))
        assertFalse(UpdateChecker.isNewer("garbage", "0.12.0"))
    }
}
