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
 * Step 0 of the server's docs/plans/ANDROID_OCR_PLAN.md: do light-ocr's two networks (PaddleOCR PP-OCRv6 small - assets/ocr/det.onnx, rec.onnx, copies of the
 * bundle the server's OCR reader uses) load in the app's OpenCV 4.14 dnn module on the phone, and give the shapes measured on the desktop (OpenCV 5.0)?
 *   det 1x3xHxW -> a probability map 1x1xHxW, at two sizes (dynamic input);
 *   rec 1x3x48xW -> 1 x (W/8) x 18710 (blank + the dictionary's 18,709 characters), a softmax (each step's row sums to 1), at two widths.
 * The inputs are zeros (only the shapes and that it runs are asked here; the answers are the later steps' parity tests). Logs each forward's time. Temporary:
 * it becomes part of TextReaderParityTest (step 3).
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
        val report = StringBuilder()

        var t = System.nanoTime()
        val det = Dnn.readNetFromONNX(MatOfByte(*detBytes))
        report.append("det loaded in ${(System.nanoTime() - t) / 1_000_000} ms\n")
        for ((h, w) in listOf(960 to 704, 64 to 64, 192 to 640)) {          // a 1015 x 1419 capture becomes 704 x 960; a tiny one; a strip-sized one
            val (shape, out, ms) = forward(det, intArrayOf(1, 3, h, w))
            out.release()
            assertArrayEquals("det at ${h}x$w", intArrayOf(1, 1, h, w), shape)
            report.append("det 1x3x${h}x$w -> ${shape.joinToString("x")} in $ms ms\n")
        }

        // OpenCV 4.14 refuses the recognizer (a Shape node on a dynamic input: its width follows the line) - recorded here, and onnxruntime is asked instead
        val refused = try { Dnn.readNetFromONNX(MatOfByte(*recBytes)); null } catch (e: Exception) { e.message?.substringAfter("error: ")?.take(120) }
        report.append("rec in OpenCV dnn: ${refused ?: "loads"}\n")
        recOnOnnxRuntime(recBytes, report)
        android.util.Log.i("Scanner", "OcrModelsLoadTest:\n$report")
    }

    /** The recognizer on onnxruntime (the plan's fallback route): it loads, and 1x3x48xW gives 1 x W/8 x 18710, a softmax, at two widths */
    private fun recOnOnnxRuntime(recBytes: ByteArray, report: StringBuilder) {
        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        var t = System.nanoTime()
        val session = env.createSession(recBytes, ai.onnxruntime.OrtSession.SessionOptions())
        report.append("rec loaded in onnxruntime in ${(System.nanoTime() - t) / 1_000_000} ms; input ${session.inputNames}, output ${session.outputNames}\n")
        val inputName = session.inputNames.first()
        for (w in listOf(320, 640, 1280)) {
            val data = java.nio.FloatBuffer.allocate(3 * 48 * w)
            ai.onnxruntime.OnnxTensor.createTensor(env, data, longArrayOf(1, 3, 48, w.toLong())).use { input ->
                t = System.nanoTime()
                session.run(mapOf(inputName to input)).use { result ->
                    val ms = (System.nanoTime() - t) / 1_000_000
                    val out = result[0] as ai.onnxruntime.OnnxTensor
                    val shape = out.info.shape
                    assertArrayEquals("rec at 48x$w", longArrayOf(1, (w / 8).toLong(), 18710), shape)
                    val probabilities = out.floatBuffer
                    var sum = 0.0
                    for (i in 0 until 18710) sum += probabilities.get(i)
                    assertEquals("rec's first step sums to 1", 1.0, sum, 1e-3)
                    report.append("rec (onnxruntime) 1x3x48x$w -> ${shape.joinToString("x")} in $ms ms\n")
                }
            }
        }
        session.close()
    }
}
