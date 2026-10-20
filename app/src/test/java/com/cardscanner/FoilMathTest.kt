package com.cardscanner

import com.cardscanner.reading.FoilNet
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64 as JvmBase64

/**
 * The foil network's own arithmetic (FoilNet.normalise, FoilNet.foilProbability) gives what the server's
 * Python code gives (core/foil_marker.py: FoilMarker.probability). The network itself runs on the emulator:
 * androidTest FoilNetParityTest.
 */
class FoilMathTest {
    private val reference = JSONObject(javaClass.getResource("/foil_math_reference.json")!!.readText())

    @Test
    fun aCropIsNormalisedAsThePythonCodeDoes() {
        val cases = reference.getJSONArray("normalised")
        assertTrue(cases.length() >= 3)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val pixels = JvmBase64.getDecoder().decode(case.getString("pixels_b64"))
            assertEquals(FoilNet.CROP_W * FoilNet.CROP_H, pixels.size)
            val expected = case.getJSONArray("expected")
            val got = FoilNet.normalise(pixels)
            var worst = 0.0
            for (n in got.indices) worst = maxOf(worst, kotlin.math.abs(got[n] - expected.getDouble(n)))
            // The server does it in float32, this in doubles rounded to float32: rounding differences only
            assertTrue("crop $i differs by $worst", worst < 1e-5)
        }
    }

    @Test
    fun theSoftmaxIsTheProbabilityOfAFoil() {
        val cases = reference.getJSONArray("softmax")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val logits = case.getJSONArray("logits").let { l -> FloatArray(2) { n -> l.getDouble(n).toFloat() } }
            assertEquals(case.getJSONArray("logits").toString(), case.getDouble("expected"), FoilNet.foilProbability(logits), 1e-6)
        }
    }
}
