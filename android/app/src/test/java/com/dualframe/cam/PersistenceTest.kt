package com.dualframe.cam

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersistenceTest {
    private val eps = 1e-4f

    @Test
    fun pendingJobRoundTrip() {
        val pt = CropTrack().apply { add(0, FrameMath.tall()); add(500_000, FrameMath.tall(BoxState(0.3f, 0.5f, 0.6f))) }
        val st = CropTrack().apply { add(0, FrameMath.wide()); add(1_000_000, FrameMath.wide(BoxState(0.5f, 0.1f, 1f))) }
        val job = PendingJob(
            id = "20261031_183000_000", masterPath = "/x/master.mp4", angle = 270, codec = Codec.H264,
            maxLongSide = null, fps = 60, lensLabel = "0.5×", createdAt = 123L,
            primaryTrack = pt, secondaryTrack = st, state = "ready", attempts = 1,
            done = mapOf("9x16" to MediaRef("content://a", "a.mp4", 1080, 1920))
        )
        val back = PendingJob.fromJson(JSONObject(job.toJson().toString()))
        assertEquals(270, back.angle)
        assertEquals(Codec.H264, back.codec)
        assertNull(back.maxLongSide)
        assertEquals(60, back.fps)
        assertEquals("ready", back.state)
        assertEquals("a.mp4", back.done["9x16"]?.name)
        assertEquals(pt.rectAt(250_000).left, back.primaryTrack.rectAt(250_000).left, eps)
        assertEquals(st.rectAt(2_000_000).top, back.secondaryTrack.rectAt(2_000_000).top, eps)
    }

    @Test
    fun takeRoundTrip() {
        val t = Take(
            id = "t1", type = "video", createdAt = 5L, lens = "1×", settings = "1080p · 30 fps · HEVC",
            status = "done", durationMs = 42_000,
            vertical = MediaRef("content://v", "v.mp4", 1080, 1920),
            photos = listOf(PhotoPair(7L, MediaRef("content://pv", "pv.jpg", 1, 2), null)),
            note = "ok"
        )
        val back = Take.fromJson(JSONObject(t.toJson().toString()))
        assertEquals(t, back)
    }

    @Test
    fun bothBoxesResizeAndClamp() {
        val p = FrameMath.tall(BoxState(0.0f, 0.0f, 0.5f))
        assertEquals(0.375f, p.width, eps)
        assertEquals(0.5f, p.height, eps)
        assertEquals(0f, p.left, eps)
        assertEquals(0f, p.top, eps)
        val big = FrameMath.tall(BoxState(0.9f, 0.9f, 3f)) // scala oltre 1 → 1, centrato in verticale
        assertEquals(1f, big.height, eps)
        assertEquals(1f, big.right, eps)
    }
}
