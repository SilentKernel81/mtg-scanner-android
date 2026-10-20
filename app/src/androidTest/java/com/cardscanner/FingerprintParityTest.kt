package com.cardscanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cardscanner.reading.Fingerprint
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * The fingerprint of a card picture in the app (Fingerprint.hash: OpenCV 4.x on Android, INTER_AREA, DCT) is
 * the one the scanner server's Python code makes (core/image_index.py hash_card, OpenCV in Python): the same
 * recipe on the same pictures. The pictures are PNGs (lossless: no decoder can differ) in assets/parity/ -
 * two synthetic cards and one real capture - and the reference hashes in fingerprint_hashes.json were made by
 * the Python code reading those very files; reading_reference.py makes both. Run it with
 *   adb -s <EMULATOR serial> shell am instrument -w -e class com.cardscanner.FingerprintParityTest \
 *     com.cardscanner.test/androidx.test.runner.AndroidJUnitRunner
 * (never connectedDebugAndroidTest: it uninstalls the app and wipes its data - and not on the user's phone).
 *
 * A few bits may differ: the DCT of OpenCV on another CPU can land a frequency exactly on the median the other
 * way. A wrong recipe (BGR for RGB, a missed crop, a different resize) differs by hundreds of the 768 bits, so
 * the tolerance is small; the test logs the real figure.
 */
@RunWith(AndroidJUnit4::class)
class FingerprintParityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before fun openCv() {
        assertTrue("OpenCV did not load", OpenCVLoader.initLocal())
    }

    private fun rgba(name: String): Mat {
        val options = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = instrumentation.context.assets.open("parity/$name").use { BitmapFactory.decodeStream(it, null, options) }!!
        return Mat().also { Utils.bitmapToMat(bitmap, it) }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun fromHex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val references: JSONObject by lazy {
        JSONObject(instrumentation.context.assets.open("parity/fingerprint_hashes.json").use { String(it.readBytes()) })
    }

    @Test fun theFingerprintIsThePythonOne() {
        val report = StringBuilder()
        for (name in references.keys().asSequence().toList()) {
            val reference = references.getJSONObject(name)
            val picture = rgba(name)
            assertEquals("$name width", reference.getInt("width"), picture.cols())
            assertEquals("$name height", reference.getInt("height"), picture.rows())
            val got = Fingerprint.hash(picture)
            picture.release()
            assertEquals(Fingerprint.HASH_BYTES, got.size)
            val want = fromHex(reference.getString("hash_hex"))
            val differing = Fingerprint.distance(want, got)
            report.append("$name: $differing of ${Fingerprint.HASH_BITS} bits differ from the Python fingerprint\n")
            assertTrue("$name: $differing bits differ (the recipe differs somewhere)\n  want ${hex(want)}\n  got  ${hex(got)}", differing <= TOLERANCE_BITS)
        }
        android.util.Log.i("Scanner", "FingerprintParityTest:\n$report")
    }

    @Test fun anRgbaAndAnRgbPictureGiveTheSameFingerprint() {
        val rgbaPicture = rgba("fp_capture.png")
        val rgb = Mat()
        Imgproc.cvtColor(rgbaPicture, rgb, Imgproc.COLOR_RGBA2RGB)
        assertEquals(CvType.CV_8UC3, rgb.type())
        assertEquals(hex(Fingerprint.hash(rgbaPicture)), hex(Fingerprint.hash(rgb)))
        rgbaPicture.release()
        rgb.release()
    }

    @Test fun theFingerprintTellsPicturesApart() {
        // a sanity check of the test itself: two different cards differ in about half their bits
        val one = rgba("fp_synthetic_1234.png")
        val two = rgba("fp_synthetic_99.png")
        val distance = Fingerprint.distance(Fingerprint.hash(one), Fingerprint.hash(two))
        one.release()
        two.release()
        assertTrue("two different cards differ by only $distance bits", distance > 100)
    }

    companion object {
        /** Of 768 bits: a frequency on the median may fall the other way on another CPU; a wrong recipe differs by hundreds */
        const val TOLERANCE_BITS = 8
    }
}
