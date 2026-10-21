package com.cardscanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import java.security.MessageDigest

/**
 * Step 0 of the server's docs/plans/ANDROID_OCR_PLAN.md: light-ocr's two networks (PaddleOCR PP-OCRv6 small - assets/ocr/det.onnx, rec.onnx, copies of the
 * bundle the server's OCR reader uses) load in the app's OpenCV dnn module on the phone and give the shapes measured on the desktop:
 *   det 1x3xHxW -> a probability map 1x1xHxW, at three sizes (dynamic input);
 *   rec 1x3x48xW -> 1 x (W/8) x 18710 (blank + the dictionary's 18,709 characters), a softmax (each step's row sums to 1), at three widths.
 * The inputs are zeros (only the shapes, the softmax and that it runs are asked here; the answers are the later steps' parity tests). Logs each forward's time.
 *
 * History (the owner's Pixel 10, 2026-10-21): OpenCV 4.14 REFUSED the recognizer ("Node [Shape]: !isDynamicShape" - its input width follows the line), so
 * it first ran on onnxruntime-android 1.30.0 (31.5 MB of native library); OpenCV 5.0.0.1 loads it, and gives onnxruntime's answers on a random input to
 * 2.3e-5 of a probability with the same character at every step (320 / 640 / 1280 wide: dnn 71 / 125 / 212 ms, onnxruntime 42 / 62 / 104 ms) - so the app
 * runs both networks on OpenCV 5 and has no second engine. The detector is faster on 5.0 (a strip-sized 640 x 192 input: 38 ms, 97 on 4.14).
 *   adb shell am instrument -w -e class com.cardscanner.OcrModelsLoadTest com.cardscanner.debug.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class OcrModelsLoadTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun openCv() { assertTrue("OpenCV did not load", OpenCVLoader.initLocal()) }

    private fun bytes(name: String) = context.assets.open("ocr/$name").use { it.readBytes() }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** One forward of [net] on a zero input of [shape]; returns (the output's shape, the output, milliseconds) */
    private fun forward(net: Net, shape: IntArray): Triple<IntArray, Mat, Long> {
        val input = Mat(shape, CvType.CV_32F).apply { setTo(org.opencv.core.Scalar(0.0)) }
        val t = System.nanoTime()
        net.setInput(input)
        val out = net.forward()
        val ms = (System.nanoTime() - t) / 1_000_000
        input.release()
        return Triple(IntArray(out.dims()) { out.size(it) }, out, ms)
    }

    @Test fun bothNetworksLoadAndGiveTheMeasuredShapes() {
        // the copies are the bundle's files (its SHA256SUMS)
        val detBytes = bytes("det.onnx")
        val recBytes = bytes("rec.onnx")
        assertEquals("d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e", sha256(detBytes))
        assertEquals("5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634", sha256(recBytes))
        val report = StringBuilder("OpenCV ${org.opencv.core.Core.VERSION}\n")

        var t = System.nanoTime()
        val det = Dnn.readNetFromONNX(MatOfByte(*detBytes))
        report.append("det loaded in ${(System.nanoTime() - t) / 1_000_000} ms\n")
        for ((h, w) in listOf(960 to 704, 64 to 64, 192 to 640)) {          // a 1015 x 1419 capture becomes 704 x 960; a tiny one; a strip-sized one
            val (shape, out, ms) = forward(det, intArrayOf(1, 3, h, w))
            out.release()
            assertArrayEquals("det at ${h}x$w", intArrayOf(1, 1, h, w), shape)
            report.append("det 1x3x${h}x$w -> ${shape.joinToString("x")} in $ms ms\n")
        }

        t = System.nanoTime()
        val rec = Dnn.readNetFromONNX(MatOfByte(*recBytes))
        report.append("rec loaded in ${(System.nanoTime() - t) / 1_000_000} ms\n")
        for (w in listOf(320, 640, 1280)) {
            val (shape, out, ms) = forward(rec, intArrayOf(1, 3, 48, w))
            assertArrayEquals("rec at 48x$w", intArrayOf(1, w / 8, 18710), shape)
            val row = FloatArray(18710)                                   // a softmax: the first step's probabilities sum to 1
            out.reshape(1, shape[1] * shape[2]).get(0, 0, row)
            assertEquals("rec's first step sums to 1", 1.0, row.sum().toDouble(), 1e-3)
            out.release()
            report.append("rec 1x3x48x$w -> ${shape.joinToString("x")} in $ms ms\n")
        }
        android.util.Log.i("Scanner", "OcrModelsLoadTest:\n$report")
    }
}
