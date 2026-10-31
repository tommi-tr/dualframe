package com.dualframe.cam

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameMathTest {
    private val eps = 1e-4f

    @Test
    fun primaryIs9by16InA3by4Frame() {
        val p = FrameMath.primary()
        // frame 3000x4000 (display portrait)
        val w = p.width * 3000f
        val h = p.height * 4000f
        assertEquals(9f / 16f, w / h, eps)
    }

    @Test
    fun secondaryIs16by9AndClamped() {
        val r = FrameMath.secondary(SecondaryState(0.5f, 0.99f, 1f))
        assertEquals(16f / 9f, (r.width * 3000f) / (r.height * 4000f), eps)
        assertEquals(1f, r.bottom, eps)
        val small = FrameMath.secondary(SecondaryState(0.0f, 0.0f, 0.5f))
        assertEquals(0f, small.left, eps)
        assertEquals(0f, small.top, eps)
        assertEquals(0.5f, small.width, eps)
    }

    @Test
    fun fileTags() {
        val p = FrameMath.primary()
        val s = FrameMath.secondary(SecondaryState())
        assertEquals("9x16", FrameMath.fileTag(p, 0, 3000, 4000))
        assertEquals("16x9", FrameMath.fileTag(s, 0, 3000, 4000))
        assertEquals("16x9", FrameMath.fileTag(p, 90, 3000, 4000))
        assertEquals("9x16", FrameMath.fileTag(s, 270, 3000, 4000))
    }

    @Test
    fun orientationSnapping() {
        assertEquals(0, FrameMath.snapOrientation(10, 0))
        assertEquals(90, FrameMath.snapOrientation(95, 0))
        assertEquals(0, FrameMath.snapOrientation(50, 0)) // isteresi
        assertEquals(270, FrameMath.snapOrientation(265, 0))
        assertEquals(0, FrameMath.snapOrientation(355, 270))
    }

    @Test
    fun cropTrackInterpolates() {
        val t = CropTrack()
        t.add(0, NormRect(0f, 0f, 1f, 0.4f))
        t.add(1_000_000, NormRect(0f, 0.6f, 1f, 1f))
        val mid = t.rectAt(500_000)
        assertEquals(0.3f, mid.top, eps)
        assertEquals(0.6f, t.rectAt(5_000_000).top, eps)
        assertEquals(0f, t.rectAt(-5).top, eps)
    }
}
