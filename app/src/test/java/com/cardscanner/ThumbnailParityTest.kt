package com.cardscanner

import com.cardscanner.detection.CardTracker
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The comparison of two card thumbnails that decides "the card changed" is the station's
 * (scanner-server station/scanner.py: CardScanner._thumbnail_difference, plain and smoothed).
 * thumbnail_reference.json is made by thumbnail_reference.py from random thumbnails.
 */
class ThumbnailParityTest {
    private val reference = JSONObject(javaClass.getResource("/thumbnail_reference.json")!!.readText(Charsets.UTF_8))
    private val cases = reference.getJSONArray("cases")

    private fun floats(array: org.json.JSONArray) = FloatArray(array.length()) { array.getDouble(it).toFloat() }

    @Test fun plainAndSmoothedDifferencesMatchPython() {
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val a = floats(case.getJSONArray("a")); val b = floats(case.getJSONArray("b"))
            assertEquals("case $i plain", case.getDouble("plain"), CardTracker.thumbnailDifference(a, b), 1e-4)
            assertEquals("case $i smoothed", case.getDouble("smooth"), CardTracker.thumbnailDifference(a, b, smooth = true), 1e-4)
        }
    }

    @Test fun theLimitIsThePythonOne() {
        assertEquals(reference.getDouble("limit"), CardTracker.IMAGE_CHANGE_LIMIT, 0.0)
    }

    @Test fun aShiftedCardIsBelowTheLimitSmoothedAndADifferentOneAbove() {
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val smooth = CardTracker.thumbnailDifference(floats(case.getJSONArray("a")), floats(case.getJSONArray("b")), smooth = true)
            if (i % 2 == 0) assertTrue("case $i (the same card shifted) $smooth", smooth < CardTracker.IMAGE_CHANGE_LIMIT)
            else assertTrue("case $i (another card) $smooth", smooth > CardTracker.IMAGE_CHANGE_LIMIT)
        }
    }
}
