package com.cardscanner

import com.cardscanner.detection.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera-frame note sent with a capture (FrameSidecar): the outline is in the pixels of the
 * frame that is stored, and the fields are those the server's core/frames.py and the station write.
 */
class FrameSidecarTest {
    @Test fun aFrameThatFitsIsNotScaled() {
        assertEquals(1.0, FrameSidecar.scale(1382, 1866), 0.0)
        assertEquals(1.0, FrameSidecar.scale(2048, 1536), 0.0)
    }

    @Test fun aLargeStillIsBroughtToTheLimitOnItsLongerSide() {
        val factor = FrameSidecar.scale(3264, 2448)
        assertEquals(2048.0 / 3264.0, factor, 1e-9)
        assertEquals(2048, (3264 * factor).toInt())
        assertEquals(1536, (2448 * factor).toInt())
        assertEquals(2048.0 / 3264.0, FrameSidecar.scale(2448, 3264), 1e-9)  // portrait
    }

    @Test fun theOutlineIsScaledWithTheFrame() {
        val corners = listOf(Pt(1000.0, 400.0), Pt(2000.0, 400.0), Pt(2000.0, 1800.0), Pt(1000.0, 1800.0))
        val json = FrameSidecar.cornersJson(corners, 0.5)!!
        assertEquals(4, json.length())
        assertEquals(500.0, json.getJSONArray(0).getDouble(0), 0.0)
        assertEquals(200.0, json.getJSONArray(0).getDouble(1), 0.0)
        assertEquals(1000.0, json.getJSONArray(2).getDouble(0), 0.0)
        assertEquals(900.0, json.getJSONArray(2).getDouble(1), 0.0)
        assertEquals(null, FrameSidecar.cornersJson(null, 1.0))
    }

    @Test fun theNoteHasTheFieldsTheStationsHave() {
        val now = 1_000_000_000_000L
        val meta = FrameSidecar.meta(
            frameWidth = 2048, frameHeight = 1536, corners = FrameSidecar.cornersJson(listOf(Pt(1.0, 2.0)), 1.0),
            cardWidth = 800, cardHeight = 1118, rotation = 90, fixedArea = false, stableFrames = 5,
            requiredStableFrames = 5, newCard = NewCard("jump_or_image_change", jump = 0.0412345, imageChange = 0.5678, at = now - 1500),
            secondsSincePreviousCapture = 2.0, now = now,
        )
        for (key in listOf("taken", "frame_width", "frame_height", "corners", "card_size", "rotation", "fixed_area",
            "stable_frames", "required_stable_frames", "new_card", "seconds_since_previous_capture")) {
            assertTrue("missing $key", meta.has(key))
        }
        val reason = meta.getJSONObject("new_card")
        assertEquals("jump_or_image_change", reason.getString("kind"))
        assertEquals(0.0412, reason.getDouble("jump"), 1e-9)
        assertEquals(0.568, reason.getDouble("image_change"), 1e-9)
        assertEquals(1.5, reason.getDouble("seconds_before"), 1e-9)
        assertEquals("android", meta.getString("source"))
    }

    @Test fun noOutlineAndNoReasonAreNullNotMissing() {
        val meta = FrameSidecar.meta(640, 480, null, 600, 800, 0, true, 0, 5, null, null)
        assertTrue(meta.isNull("corners"))
        assertTrue(meta.isNull("new_card"))
        assertTrue(meta.isNull("seconds_since_previous_capture"))
        assertTrue(meta.getBoolean("fixed_area"))
    }
}
