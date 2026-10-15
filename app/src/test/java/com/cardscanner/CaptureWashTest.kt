package com.cardscanner

import com.cardscanner.ui.CaptureWash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scanner screen's tint and wash (CaptureWash): the same numbers as the stations' live view */
class CaptureWashTest {
    @Test fun aCardAtRestIsTintedInItsStateColour() {
        val (alpha, green) = CaptureWash.autoCard(0f)
        assertEquals(CaptureWash.TINT, alpha, 0f)
        assertFalse(green)
    }

    @Test fun aCaptureWashesItGreenAndStronger() {
        val (alpha, green) = CaptureWash.autoCard(1f)
        assertEquals(CaptureWash.FLASH, alpha, 1e-6f)
        assertTrue(green)
        assertTrue(alpha > CaptureWash.TINT)
    }

    @Test fun theWashFadesDownToTheTintAndStopsBeingGreen() {
        var last = Float.MAX_VALUE
        for (step in 10 downTo 0) {
            val (alpha, _) = CaptureWash.autoCard(step / 10f)
            assertTrue(alpha <= last + 1e-6f)      // never gets stronger while fading
            assertTrue(alpha >= CaptureWash.TINT)  // never below the tint: there is no gap
            last = alpha
        }
        // the changeover: the wash 0.45 * w equals the tint at w = 0.22 / 0.45
        assertTrue(CaptureWash.autoCard(0.5f).second)
        assertFalse(CaptureWash.autoCard(0.4f).second)
    }

    @Test fun theFixedAreaGetsTheWashOnly() {
        assertEquals(0f, CaptureWash.area(0f), 0f)
        assertEquals(CaptureWash.FLASH, CaptureWash.area(1f), 1e-6f)
        assertEquals(0f, CaptureWash.area(-3f), 0f)   // out of range is held to 0..1
        assertEquals(CaptureWash.FLASH, CaptureWash.area(7f), 1e-6f)
    }
}
