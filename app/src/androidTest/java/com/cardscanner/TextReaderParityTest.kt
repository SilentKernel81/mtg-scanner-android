package com.cardscanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cardscanner.reading.CardText
import com.cardscanner.reading.TextReader
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
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Step 3 of the server's docs/plans/ANDROID_OCR_PLAN.md: the phone's OCR reader (reading/TextReader.kt) reads what the server's Python reference of
 * light-ocr reads (scripts/experiments/ocr_port/ppocr_reference.py) on the same pictures - the strips of 6 Pixel captures (the confetti Lightning Bolt and
 * Madame Hydra among them) and 2 station captures, and 2 whole cards (halved): the PNGs and ocr_lines.json in assets/parity/ocr/, made by
 * app/src/test/resources/ocr_reference.py. Per picture: (a) the asset files are the reference's (SHA-256); (b) the detector's input size; the lines - the
 * same count, the SAME TEXTS, boxes within 2 px per coordinate, confidence within 0.02; (c) what the parsers make of them (parse_strip and the marker of a
 * strip, parse_magic of a card) the reference's; (d) two threads reading at once do not crash. Logs the time per picture.
 *   adb shell am instrument -w -e class com.cardscanner.TextReaderParityTest com.cardscanner.debug.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class TextReaderParityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val reference by lazy { JSONObject(instrumentation.context.assets.open("parity/ocr/ocr_lines.json").use { String(it.readBytes()) }) }
    private val pictures by lazy { reference.getJSONArray("pictures").let { a -> List(a.length()) { a.getJSONObject(it) } } }

    @Before fun openCv() { assertTrue("OpenCV did not load", OpenCVLoader.initLocal()) }

    private fun rgba(name: String): Mat {
        val options = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = instrumentation.context.assets.open("parity/ocr/$name").use { BitmapFactory.decodeStream(it, null, options) }!!
        return Mat().also { Utils.bitmapToMat(bitmap, it) }
    }

    private fun reader(): TextReader {
        val reader = TextReader.load(instrumentation.targetContext)
        assertNotNull("the OCR reader did not load", reader)
        return reader!!
    }

    @Test fun theAssetsAreTheReferences() {
        val sums = reference.getJSONObject("assets_sha256")
        for (name in sums.keys()) {
            val bytes = instrumentation.targetContext.assets.open("ocr/$name").use { it.readBytes() }
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(name, sums.getString(name), sha)
        }
    }

    @Test fun thePhoneReadsWhatThePythonReferenceReads() {
        val reader = reader()
        val report = StringBuilder()
        val problems = ArrayList<String>()
        for (case in pictures) {
            val name = case.getString("picture")
            val picture = rgba(name)
            val trace = TextReader.Trace()
            val started = System.nanoTime()
            val lines = reader.read(picture, trace)
            val ms = (System.nanoTime() - started) / 1_000_000
            picture.release()
            val size = case.getJSONArray("detection_size")
            if (trace.detectionSize != size.getInt(0) to size.getInt(1)) problems += "$name: detector input ${trace.detectionSize}, the reference's $size"
            val want = case.getJSONArray("lines").let { a -> List(a.length()) { a.getJSONObject(it) } }
            if (want.size != lines.size) problems += "$name: ${lines.size} lines, the reference ${want.size}: ${lines.map { it.text }} / ${want.map { it.getString("text") }}"
            var worstBox = 0.0
            var worstConfidence = 0.0
            for ((w, got) in want.zip(lines)) {
                if (w.getString("text") != got.text) problems += "$name: '${got.text}', the reference '${w.getString("text")}'"
                val box = w.getJSONArray("box")
                for (i in 0 until 4) {
                    val p = box.getJSONArray(i)
                    worstBox = maxOf(worstBox, abs(p.getDouble(0) - got.box[i].x), abs(p.getDouble(1) - got.box[i].y))
                }
                worstConfidence = maxOf(worstConfidence, abs(w.getDouble("confidence") - got.confidence))
            }
            if (worstBox > 2.0) problems += "$name: a box corner $worstBox px off"
            if (worstConfidence > 0.02) problems += "$name: a confidence $worstConfidence off"
            // what the parsers make of the lines
            if (case.getString("kind") == "strip") {
                val strip = case.getJSONArray("parse_strip")
                val parsed = CardText.parseStrip(lines)
                if (parsed != (strip.getString(0) to strip.getString(1))) problems += "$name: parse_strip $parsed, the reference $strip"
                val symbol = CardText.setLine(lines)?.let { CardText.symbolRead(it) }
                val wantSymbol = if (case.isNull("symbol_read")) null else case.getString("symbol_read")
                if (symbol != wantSymbol) problems += "$name: the marker $symbol, the reference $wantSymbol"
            } else {
                val magic = CardText.parseMagic(lines, case.getInt("width"), case.getInt("height"))
                val w = case.optJSONObject("parse_magic")
                val wanted = w?.let { CardText.MagicRead(it.getString("name"), it.getString("collector_number"), it.getString("set_code"), it.getString("foil")) }
                if (magic != wanted) problems += "$name: parse_magic $magic, the reference $wanted"
            }
            report.append("$name: ${lines.size} lines in $ms ms, boxes within %.1f px, confidences within %.4f: ${lines.map { it.text }}\n".format(worstBox, worstConfidence))
        }
        android.util.Log.i("Scanner", "TextReaderParityTest:\n$report")
        assertTrue("${problems.size} differences from the Python reference:\n${problems.joinToString("\n")}", problems.isEmpty())
    }

    /**
     * Native memory stays bounded while reading (a Codex review of 2026-10-21: the networks' outputs were reshaped into views that were never released,
     * so each read left megabytes of native memory to the garbage collector): the 10-line card read 30 times after a warm-up, with no collection
     * forced in between - the native heap may not grow by more than a few reads' worth. Logs the figures.
     */
    @Test fun nativeMemoryStaysBoundedOverManyReads() {
        val reader = reader()
        val card = pictures.first { it.getString("kind") == "card" && it.getJSONArray("lines").length() >= 8 }.getString("picture")
        val picture = rgba(card)
        repeat(3) { reader.read(picture) }                              // warm-up: the networks' own buffers
        Runtime.getRuntime().gc(); System.runFinalization(); Runtime.getRuntime().gc()
        val before = android.os.Debug.getNativeHeapAllocatedSize()
        repeat(30) { reader.read(picture) }
        val after = android.os.Debug.getNativeHeapAllocatedSize()
        picture.release()
        val grownMb = (after - before) / 1048576.0
        android.util.Log.i("Scanner", "TextReaderParityTest: native heap %.1f MB before 30 reads of $card, %.1f MB after (%+.1f MB)".format(before / 1048576.0, after / 1048576.0, grownMb))
        assertTrue("the native heap grew by %.1f MB over 30 reads".format(grownMb), grownMb < LEAK_LIMIT_MB)
    }

    companion object {
        /** 30 reads of a 10-line card: one read's outputs are ~3 MB a line in the recognizer - a leak would be tens of MB; the allocator's slack is a few */
        const val LEAK_LIMIT_MB = 15.0
    }

    @Test fun twoThreadsReadingAtOnceDoNotCrash() {
        val reader = reader()
        val name = pictures.first().getString("picture")
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads = List(2) {
            Thread {
                try { repeat(3) { val p = rgba(name); reader.read(p); p.release() } } catch (e: Throwable) { errors.add(e) }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue(errors.joinToString(), errors.isEmpty())
    }
}
