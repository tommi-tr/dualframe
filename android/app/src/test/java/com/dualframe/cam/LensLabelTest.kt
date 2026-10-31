package com.dualframe.cam

import org.junit.Assert.assertEquals
import org.junit.Test

class LensLabelTest {
    private fun lens(z: Float) = LensOption("0", null, z, listOf(1.9f), 10f)

    @Test
    fun labels() {
        assertEquals("0.5×", lens(0.5f).label)
        assertEquals(".5×", lens(0.5f).shortLabel)
        assertEquals("Int.", LensOption("1", null, 1f, emptyList(), 0f, front = true).shortLabel)
        assertEquals(-1f, LensOption("1", null, 1f, emptyList(), 0f, front = true).memoKey)
        assertEquals("1×", lens(1.02f).label)
        assertEquals("1×", lens(1f).shortLabel)
        assertEquals("5×", lens(4.95f).label)
        assertEquals("2.5×", lens(2.5f).label)
        assertEquals("Grandangolo", lens(0.6f).name)
        assertEquals("Principale", lens(1f).name)
        assertEquals("Teleobiettivo", lens(3f).name)
    }
}
