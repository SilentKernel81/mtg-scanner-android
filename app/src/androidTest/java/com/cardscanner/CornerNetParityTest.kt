package com.cardscanner

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cardscanner.detection.CornerNet
import com.cardscanner.detection.Pt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import kotlin.math.abs

/**
 * The corner network in the app (OpenCV 4.x on Android) gives what the station's does
 * (station/corner_net.py, OpenCV in Python): same file, same windows. Reference corners in
 * assets/parity/reference.json were made by the Python code on these very JPEGs. Run it with
 *   adb -s <device> shell am instrument -w -e class com.cardscanner.CornerNetParityTest \
 *     com.cardscanner.debug.test/androidx.test.runner.AndroidJUnitRunner
 * (never connectedDebugAndroidTest: it uninstalls the app and wipes its data).
 */
@RunWith(AndroidJUnit4::class)
class CornerNetParityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before fun openCv() {
        assertTrue("OpenCV did not load", OpenCVLoader.initLocal())
    }

    private fun frame(name: String): Mat {
        val bitmap = instrumentation.context.assets.open("parity/$name").use { BitmapFactory.decodeStream(it) }
        return Mat().also { Utils.bitmapToMat(bitmap, it) }  // RGBA
    }

    private fun corners(json: org.json.JSONArray) = List(4) { Pt(json.getJSONArray(it).getDouble(0), json.getJSONArray(it).getDouble(1)) }

    @Test fun theNetworkGivesThePythonCorners() {
        val net = CornerNet.load(instrumentation.targetContext)
        assertNotNull("corner_net.onnx could not be loaded", net)
        val reference = JSONObject(instrumentation.context.assets.open("parity/reference.json").use { String(it.readBytes()) })
        val names = reference.keys().asSequence().toList()
        assertTrue("no reference frames", names.isNotEmpty())
        val report = StringBuilder()
        for (name in names) {
            val ref = reference.getJSONObject(name)
            val mat = frame(name)
            val (cold, score) = net!!.find(mat)
            val want = corners(ref.getJSONArray("cold"))
            val worst = cold.zip(want).maxOf { (a, b) -> maxOf(abs(a.x - b.x), abs(a.y - b.y)) }
            // the tracked path: the same outline, a few pixels off, followed
            val trackedStart = want.map { Pt(it.x + 3.0, it.y + 3.0) }
            val (tracked, _) = net.find(mat, previous = trackedStart)
            val wantTracked = corners(ref.getJSONArray("tracked"))
            val worstTracked = tracked.zip(wantTracked).maxOf { (a, b) -> maxOf(abs(a.x - b.x), abs(a.y - b.y)) }
            report.append("$name ${mat.cols()}x${mat.rows()} score ${"%.3f".format(score)} cold worst ${"%.2f".format(worst)} px, " +
                "tracked worst ${"%.2f".format(worstTracked)} px\n")
            assertTrue("$name: a card should be found (score $score)", score > 0.5)
            assertEquals("$name cold: corners differ from the Python reference by $worst px", 0.0, worst, 3.0)
            assertEquals("$name tracked: corners differ from the Python reference by $worstTracked px", 0.0, worstTracked, 3.0)
            mat.release()
        }
        android.util.Log.i("Scanner", "CornerNetParityTest:\n$report")
    }
}
