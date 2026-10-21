package com.cardscanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cardscanner.reading.ImageIndex
import com.cardscanner.reading.PictureReader
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
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * The phone reads a card by its picture as the server does (reading/ImageIndex.kt, reading/PictureReader.kt; the server's
 * docs/plans/ANDROID_PICTURE_STANDALONE_PLAN.md): the owner's 41 Pixel captures, against the server checkout's own picture index, give the decisions the
 * server's code gives - which way up, the nearest printings, rule A2's path and printing, the guards' answer, and what is added. The reference, the index
 * and the pictures are made by app/src/test/resources/picture_reference.py into assets/picture/ (ignored by git: ~46 MB - run the script first; without
 * them this test fails and says so). Run it with
 *   adb -s <serial> shell am instrument -w -e class com.cardscanner.PictureReaderParityTest com.cardscanner.debug.test/androidx.test.runner.AndroidJUnitRunner
 * (the debug build is com.cardscanner.debug: it installs beside the release app, never over it - so this may also run on a phone).
 *
 * The fingerprint may differ from the Python one by a few bits on another CPU (FingerprintParityTest's tolerance); a candidate's similarity then differs
 * by 2 / 768 per bit. Tolerated in the similarities; NOT in the decisions - a decision that flips on a few bits is reported with its numbers.
 */
@RunWith(AndroidJUnit4::class)
class PictureReaderParityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before fun openCv() {
        assertTrue("OpenCV did not load", OpenCVLoader.initLocal())
    }

    private fun asset(name: String) = instrumentation.context.assets.open("picture/$name")

    /**
     * The index of the assets, copied where the app's loader reads files - as a download leaves them. (The build stores an asset named *.gz unpacked and
     * without its ".gz": the printings table is gzipped again here.)
     */
    private fun copyIndex(folder: File) {
        folder.deleteRecursively(); folder.mkdirs()
        for (name in instrumentation.context.assets.list("picture/index")!!) {
            asset("index/$name").use { input ->
                if (name == "printings.tsv") File(folder, ImageIndex.PRINTINGS).outputStream().use { out -> GZIPOutputStream(out).use { input.copyTo(it) } }
                else File(folder, name).outputStream().use { input.copyTo(it) }
            }
        }
    }

    private fun index(): ImageIndex {
        val folder = File(instrumentation.targetContext.cacheDir, "picture_index_parity")
        copyIndex(folder)
        val opened = ImageIndex.open(folder)
        assertNotNull("the index in assets/picture/index does not load: ${opened.why} (files: ${folder.list()?.toList()})", opened.index)
        return opened.index!!
    }

    private fun rgba(name: String): Mat {
        val options = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = asset("pictures/$name").use { BitmapFactory.decodeStream(it, null, options) }!!
        return Mat().also { Utils.bitmapToMat(bitmap, it) }
    }

    @Test fun thePhoneDecidesAsTheServer() {
        val names = try { instrumentation.context.assets.list("picture")!!.toList() } catch (e: Exception) { emptyList() }
        assertTrue("no assets/picture/reference.json: run app/src/test/resources/picture_reference.py from the server's checkout first", "reference.json" in names)
        val reference = JSONObject(asset("reference.json").use { String(it.readBytes()) })
        val index = index()
        assertEquals("the reference was made of another index", reference.getString("generation"), index.generation)
        val reader = PictureReader(index)
        val cases = reference.getJSONArray("cases")
        val report = StringBuilder()
        val flipped = ArrayList<String>()
        var added = 0
        var slowest = 0L
        var total = 0L
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("picture")
            val picture = rgba(name)
            val started = System.nanoTime()
            val reading = reader.read(picture, acceptImageAlone = true, indexCovers = true) { true }
            val took = (System.nanoTime() - started) / 1_000_000
            slowest = maxOf(slowest, took); total += took
            picture.release()

            val decision = case.getJSONObject("decision")
            val want = listOf(case.getBoolean("rotated"), decision.getString("path"), decision.optString("printing_id").takeIf { !decision.isNull("printing_id") },
                case.getBoolean("accepted"), case.optString("refused").takeIf { !case.isNull("refused") }, case.optString("added").takeIf { !case.isNull("added") })
            val got = listOf(reading.search.rotated, reading.decision.path, reading.decision.printingId, reading.acceptance.accepted,
                reading.acceptance.refused, reading.printingId)
            // the candidates: the same printings, nearest first, and their similarities within the fingerprint's tolerance
            val wantCandidates = case.getJSONArray("candidates").let { a -> List(a.length()) { a.getJSONObject(it) } }
            // The same printings, except among EXACTLY equal similarities: their order, and which of several rows tied at the fifth place is kept, is numpy's
            // choice on the server (argpartition) and by row here (PictureReader.nearest) - with no strip rule A2 reads only the best similarity and the lead
            // to the second, so a tie cannot change the decision. Each similarity's group of ids must match, but the last (cut at the fifth row)
            val wantGroups = wantCandidates.groupBy({ it.getDouble("similarity") }, { it.getString("id") })
            val gotGroups = reading.search.candidates.groupBy({ it.similarity }, { it.id })
            val lastSimilarity = wantCandidates.lastOrNull()?.getDouble("similarity")
            val sameIds = wantGroups.keys == gotGroups.keys && wantGroups.all { (similarity, ids) ->
                similarity == lastSimilarity || ids.toSet() == gotGroups[similarity]!!.toSet() }
            val worst = wantCandidates.zip(reading.search.candidates).maxOfOrNull { (w, g) -> kotlin.math.abs(w.getDouble("similarity") - g.similarity) } ?: 0.0
            if (reading.printingId != null) added++
            report.append("$name: ${got.joinToString()} - candidates ${if (sameIds) "the same" else "DIFFER"}, similarity off by at most %.4f, %d ms\n".format(worst, took))
            if (want != got || !sameIds || worst > SIMILARITY_TOLERANCE) {
                flipped += "$name\n  server: $want ${wantCandidates.map { it.getString("id").take(8) + "@" + "%.4f".format(it.getDouble("similarity")) }}" +
                    "\n  phone:  $got ${reading.search.candidates.map { it.id.take(8) + "@" + "%.4f".format(it.similarity) }}"
            }
        }
        android.util.Log.i("Scanner", "PictureReaderParityTest: $added of ${cases.length()} added by the picture alone; per card ${total / cases.length()} ms " +
            "on average, $slowest ms at most\n$report")
        assertTrue("${flipped.size} of ${cases.length()} pictures read differently from the server:\n${flipped.joinToString("\n")}", flipped.isEmpty())
    }

    @Test fun anIndexThatDoesNotHoldTogetherIsNoIndex() {
        val folder = File(instrumentation.targetContext.cacheDir, "picture_index_broken")
        copyIndex(folder)
        assertNotNull("the copy loads before it is broken", ImageIndex.load(folder))
        val hashes = folder.listFiles()!!.first { it.name.startsWith("hashes-") }
        val bytes = hashes.readBytes().also { it[0] = (it[0].toInt() xor 1).toByte() }      // one bit: the checksum no longer holds
        hashes.writeBytes(bytes)
        assertEquals(null, ImageIndex.load(folder))
        folder.deleteRecursively()
    }

    companion object {
        /** 8 bits (FingerprintParityTest.TOLERANCE_BITS) of 768, as a similarity */
        const val SIMILARITY_TOLERANCE = 2.0 * 8 / 768
    }
}
