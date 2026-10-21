package com.cardscanner

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cardscanner.reading.FoilNet
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.security.MessageDigest
import kotlin.math.abs

/**
 * The foil network in the app (FoilNet: assets/foil_net.onnx, OpenCV dnn on Android) gives the probabilities the
 * scanner server's Python code gives (core/foil_marker.py FoilMarker.probability, OpenCV in Python) on the same
 * crops of set lines (the server's tests/data/foil_marker_crops.npz). foil_reference.json - the crops, the Python
 * probabilities and the SHA-256 of the network file - is made by reading_reference.py. Run it with
 *   adb -s <EMULATOR serial> shell am instrument -w -e class com.cardscanner.FoilNetParityTest \
 *     com.cardscanner.debug.test/androidx.test.runner.AndroidJUnitRunner
 * What it does NOT cover: finding the set line on a card (the server does it with its OCR reader).
 */
@RunWith(AndroidJUnit4::class)
class FoilNetParityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before fun openCv() {
        assertTrue("OpenCV did not load", OpenCVLoader.initLocal())
    }

    private val reference: JSONObject by lazy {
        JSONObject(instrumentation.context.assets.open("parity/foil_reference.json").use { String(it.readBytes()) })
    }

    @Test fun theNetworkFileIsTheServers() {
        // a copy of core/foil_net.onnx: if the server's is retrained, reading_reference.py copies it again and this hash moves with it
        val bytes = instrumentation.targetContext.assets.open("foil_net.onnx").use { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(reference.getString("onnx_sha256"), sha)
    }

    @Test fun theNetworkGivesThePythonProbabilities() {
        val net = FoilNet.load(instrumentation.targetContext)
        assertNotNull("foil_net.onnx could not be loaded", net)
        val crops = reference.getJSONArray("crops")
        assertTrue("no reference crops", crops.length() >= 12)
        var worst = 0.0
        for (i in 0 until crops.length()) {
            val crop = crops.getJSONObject(i)
            val pixels = Base64.decode(crop.getString("pixels_b64"), Base64.DEFAULT)
            val mat = Mat(FoilNet.CROP_H, FoilNet.CROP_W, CvType.CV_8UC1)
            mat.put(0, 0, pixels)
            val got = net!!.probability(mat)
            mat.release()
            val want = crop.getDouble("probability")
            worst = maxOf(worst, abs(got - want))
            assertEquals("crop $i", want, got, 1e-3)
            // and the answer that counts: foil or not
            assertEquals("crop $i: foil or not", want >= 0.5, got >= 0.5)
        }
        android.util.Log.i("Scanner", "FoilNetParityTest: ${crops.length()} crops, worst difference from the Python probability $worst")
    }
}
