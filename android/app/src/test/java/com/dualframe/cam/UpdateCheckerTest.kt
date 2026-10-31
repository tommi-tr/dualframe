package com.dualframe.cam

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun versions() {
        assertTrue(UpdateChecker.isNewer("1.4", "1.3"))
        assertTrue(UpdateChecker.isNewer("1.10", "1.9"))
        assertTrue(UpdateChecker.isNewer("2.0", "1.12"))
        assertFalse(UpdateChecker.isNewer("1.3", "1.3"))
        assertFalse(UpdateChecker.isNewer("1.2", "1.3"))
        assertTrue(UpdateChecker.isNewer("1.3.1", "1.3"))
    }
}
