package com.cardscanner

import com.cardscanner.reading.Fingerprint
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic of the fingerprint that is not OpenCV (Fingerprint.kt) gives what the scanner server's
 * Python code gives (core/image_index.py: stretch_plane, channel_bits, similarity), on the vectors
 * reading_reference.py made. The OpenCV steps (crop, INTER_AREA, DCT) are compared on the emulator:
 * androidTest FingerprintParityTest.
 */
class FingerprintMathTest {
    private val reference = JSONObject(javaClass.getResource("/fingerprint_reference.json")!!.readText())

    private fun bytes(array: org.json.JSONArray) = ByteArray(array.length()) { array.getInt(it).toByte() }

    @Test
    fun theStretchGivesThePythonPlane() {
        val cases = reference.getJSONArray("stretch")
        assertTrue(cases.length() >= 6)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val got = Fingerprint.stretchPlane(bytes(case.getJSONArray("plane")))
            assertArrayEquals("${case.getString("kind")} (${case.getInt("width")} x ${case.getInt("height")})", bytes(case.getJSONArray("expected")), got)
        }
    }

    @Test
    fun theBitsGiveThePythonBytes() {
        val cases = reference.getJSONArray("bits")
        assertTrue(cases.length() >= 8)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val low = case.getJSONArray("low").let { l -> FloatArray(l.length()) { n -> l.getDouble(n).toFloat() } }   // float32 values, exact as doubles
            val got = Fingerprint.channelBits(low).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            assertEquals("case $i", case.getString("expected_hex"), got)
        }
    }

    @Test
    fun similarityOfADistanceIsThePythonOne() {
        val distances = reference.getJSONArray("distances")
        val similarities = reference.getJSONArray("similarities")
        for (i in 0 until distances.length()) {
            assertEquals("distance ${distances.getInt(i)}", similarities.getDouble(i), Fingerprint.similarity(distances.getInt(i)), 0.0)
        }
    }

    @Test
    fun distanceCountsDifferingBitsAcrossSignedBytes() {
        val a = ByteArray(96) { 0 }
        val b = ByteArray(96) { 0xFF.toByte() }                // -1 as a signed byte: every bit differs
        assertEquals(768, Fingerprint.distance(a, b))
        assertEquals(0, Fingerprint.distance(a, a.copyOf()))
        val c = a.copyOf().also { it[10] = 0x81.toByte() }     // two bits, one of them the sign bit
        assertEquals(2, Fingerprint.distance(a, c))
    }

    @Test
    fun theConstantsAreTheServers() {
        assertEquals(reference.getString("descriptor"), Fingerprint.DESCRIPTOR)
        assertEquals(reference.getInt("hash_bytes"), Fingerprint.HASH_BYTES)
        val size = reference.getJSONArray("card_size")
        assertEquals(size.getInt(0), Fingerprint.CARD_W)
        assertEquals(size.getInt(1), Fingerprint.CARD_H)
    }
}
