package com.cardscanner

import com.cardscanner.detection.CornerMath
import com.cardscanner.detection.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic around the corner network (CornerMath), the same as station/corner_net.py's and
 * `_plausible_neural`'s in the scanner server's repository. The network itself needs OpenCV's
 * native libraries and is checked on a device (CornerNetParityTest).
 */
class CornerMathTest {
    private val card = listOf(Pt(250.0, 100.0), Pt(400.0, 100.0), Pt(400.0, 310.0), Pt(250.0, 310.0))

    @Test fun aPointGoesToTheWindowAndBack() {
        val p = Pt(321.5, 187.25)
        val inWindow = CornerMath.toWindow(p, 300.0, 200.0, 400.0)
        val back = CornerMath.toFrame(inWindow, 300.0, 200.0, 400.0)
        assertEquals(p.x, back.x, 1e-9)
        assertEquals(p.y, back.y, 1e-9)
        // the window's centre is the frame point it is centred on
        val centre = CornerMath.toWindow(Pt(300.0, 200.0), 300.0, 200.0, 400.0)
        assertEquals(CornerMath.WINDOW_W / 2.0, centre.x, 1e-9)
        assertEquals(CornerMath.WINDOW_H / 2.0, centre.y, 1e-9)
        // a window of height 640 shows the card at half size
        val moved = CornerMath.toWindow(Pt(400.0, 200.0), 300.0, 200.0, 640.0)
        assertEquals(CornerMath.WINDOW_W / 2.0 + 50.0, moved.x, 1e-9)
    }

    @Test fun aPeakInEachMapIsReadAsItsCornerPosition() {
        val cells = CornerMath.MAP_H * CornerMath.MAP_W
        val maps = FloatArray(4 * cells) { -20f }
        // corner k peaks at cell (row, col): its position is the cell's centre x STRIDE
        val peaks = listOf(10 to 5, 10 to 40, 70 to 40, 70 to 5)
        peaks.forEachIndexed { k, (row, col) -> maps[k * cells + row * CornerMath.MAP_W + col] = 12f }
        val corners = CornerMath.decode(maps)
        peaks.forEachIndexed { k, (row, col) ->
            assertEquals((col + 0.5) * CornerMath.STRIDE, corners[k].x, 1e-3)
            assertEquals((row + 0.5) * CornerMath.STRIDE, corners[k].y, 1e-3)
        }
    }

    @Test fun twoEqualPeaksGiveTheMiddleBetweenThem() {
        val cells = CornerMath.MAP_H * CornerMath.MAP_W
        val maps = FloatArray(4 * cells) { -30f }
        for (k in 0 until 4) {
            maps[k * cells + 20 * CornerMath.MAP_W + 10] = 8f
            maps[k * cells + 20 * CornerMath.MAP_W + 14] = 8f
        }
        assertEquals((12.0 + 0.5) * CornerMath.STRIDE, CornerMath.decode(maps)[0].x, 1e-3)  // sub-pixel
    }

    @Test fun theNextWindowIsAroundTheCardAndZoomedOut() {
        val (cx, cy, height) = CornerMath.nextWindow(card)
        assertEquals(325.0, cx, 1e-9)
        assertEquals(205.0, cy, 1e-9)
        assertEquals(210.0 * CornerMath.ZOOM_OUT, height, 1e-9)
    }

    @Test fun aCardsShapeIsBelievedAndOthersAreNot() {
        assertTrue(CornerMath.plausible(card, 640, 480))
        // too wide to be a card
        assertFalse(CornerMath.plausible(listOf(Pt(100.0, 100.0), Pt(500.0, 100.0), Pt(500.0, 160.0), Pt(100.0, 160.0)), 640, 480))
        // outside the frame
        assertFalse(CornerMath.plausible(card.map { Pt(it.x - 600, it.y) }, 640, 480))
        // a bow tie
        assertFalse(CornerMath.plausible(listOf(card[0], card[2], card[1], card[3]), 640, 480))
        // far too small
        assertFalse(CornerMath.plausible(listOf(Pt(10.0, 10.0), Pt(20.0, 10.0), Pt(20.0, 24.0), Pt(10.0, 24.0)), 640, 480))
        // sideways is refused unless allowed
        val sideways = listOf(Pt(100.0, 100.0), Pt(310.0, 100.0), Pt(310.0, 250.0), Pt(100.0, 250.0))
        assertFalse(CornerMath.plausible(sideways, 640, 480))
        assertTrue(CornerMath.plausible(sideways, 640, 480, allowLandscape = true))
    }
}
