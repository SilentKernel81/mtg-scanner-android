package com.cardscanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cardscanner.reading.FoilNet
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
        val foilNet = FoilNet.load(instrumentation.targetContext)
        assertNotNull("the foil network (assets/foil_net.onnx) did not load", foilNet)
        var finishes = 0
        var byNetwork = 0
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
            val reading = reader.read(picture, acceptImageAlone = true, indexCovers = true, inData = { true }, foilNet = foilNet!!)
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
            // the finish of an added card: decide_finish's answer on the server (the network asked at the fixed place where the server asks it)
            val wantFinish = case.optJSONObject("finish")?.let { it.getString("finish") to it.getString("source") }
            val gotFinish = reading.finish?.let { it.finish to it.source }
            if (wantFinish != null) finishes++
            if (gotFinish?.second == "network") byNetwork++
            val netOff = if (reading.net != null && !case.isNull("net")) kotlin.math.abs(reading.net!! - case.getDouble("net")) else 0.0
            if (wantFinish != gotFinish || netOff > NET_TOLERANCE) {
                flipped += "$name: finish server $wantFinish (net ${if (case.isNull("net")) null else "%.4f".format(case.getDouble("net"))}), phone $gotFinish (net ${reading.net?.let { "%.4f".format(it) }})"
            }
            report.append("$name: ${got.joinToString()} - candidates ${if (sameIds) "the same" else "DIFFER"}, similarity off by at most %.4f, %d ms\n".format(worst, took))
            if (want != got || !sameIds || worst > SIMILARITY_TOLERANCE) {
                flipped += "$name\n  server: $want ${wantCandidates.map { it.getString("id").take(8) + "@" + "%.4f".format(it.getDouble("similarity")) }}" +
                    "\n  phone:  $got ${reading.search.candidates.map { it.id.take(8) + "@" + "%.4f".format(it.similarity) }}"
            }
        }
        android.util.Log.i("Scanner", "PictureReaderParityTest: $added of ${cases.length()} added by the picture alone, $byNetwork of their finishes from the foil network; per card ${total / cases.length()} ms " +
            "on average, $slowest ms at most\n$report")
        assertTrue("${flipped.size} of ${cases.length()} pictures read differently from the server:\n${flipped.joinToString("\n")}", flipped.isEmpty())
    }

    /**
     * The same captures WITH the strip read by the phone's OCR reader (TextReader: a port of the server's reference of light-ocr) - the server's live_read
     * with that reference as its reader (picture_reference.py: with_strip): the strip's evidence (number, set, symbol, the foil network at the OCR's box),
     * rule A2's path and printing, what the strip's line names, the guards' answer and the finish. The printing at the strip's set and number is the one
     * the server's card data has (the reference records it per capture).
     *
     * The DECISIONS must be the server's on every capture. The strip's symbol (and so the finish) may differ on at most [STRIP_PIXEL_DIFFERENCES] of the 41,
     * for a measured reason: these pictures are HALVED captures (assets: 46 MB, not 100), so the strip is enlarged (foil_marker.strip_of, INTER_CUBIC), and
     * OpenCV's cubic resize is not bit-exact across CPUs - on the Pixel 10 the strip of 1041 and of 110 differs from the desktop's by 1 on 10% of its pixels
     * (dumped and compared, 2026-10-21), enough to flip a star or a dot a few pixels wide. A real capture (~1000 x 1400) is never enlarged.
     */
    @Test fun withTheStripThePhoneDecidesAsTheServer() {
        val reference = JSONObject(asset("reference.json").use { String(it.readBytes()) })
        val reader = PictureReader(index())
        val textReader = com.cardscanner.reading.TextReader.load(instrumentation.targetContext)
        assertNotNull("the OCR reader did not load", textReader)
        val foilNet = FoilNet.load(instrumentation.targetContext)
        val cases = reference.getJSONArray("cases")
        val problems = ArrayList<String>()
        val stripDifferences = ArrayList<String>()
        var strong = 0
        var added = 0
        var total = 0L
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val want = case.getJSONObject("with_strip")
            val name = case.getString("picture")
            val printing = when {
                want.isNull("strip_printing") -> PictureReader.StripPrinting.NotFound      // no whole line: not asked
                want.getString("strip_printing") == "lookup_failed" -> PictureReader.StripPrinting.Failed
                want.getString("strip_printing").isEmpty() -> PictureReader.StripPrinting.NotFound
                else -> PictureReader.StripPrinting.Found(want.getString("strip_printing"))
            }
            val picture = rgba(name)
            val started = System.nanoTime()
            val reading = reader.read(picture, acceptImageAlone = true, indexCovers = true, inData = { true }, foilNet = foilNet,
                textReader = textReader, stripPrinting = { _, _ -> printing })
            total += (System.nanoTime() - started) / 1_000_000
            picture.release()
            val s = want.getJSONObject("strip")
            val wantStrip = listOf(s.getString("number"), s.getString("set"), s.getString("symbol"))
            val gotStrip = listOf(reading.strip?.number, reading.strip?.set, reading.strip?.symbol)
            val stripDiffers = wantStrip != gotStrip
            val wantNet = if (s.isNull("net")) null else s.getDouble("net")
            val gotNet = reading.strip?.net
            val netDiffers = (wantNet == null) != (gotNet == null) || (wantNet != null && kotlin.math.abs(wantNet - gotNet!!) > NET_TOLERANCE)
            val d = want.getJSONObject("decision")
            val wantDecision = listOf(d.getString("path"), d.optString("printing_id").takeIf { !d.isNull("printing_id") },
                want.optString("strip_names").takeIf { !want.isNull("strip_names") }, want.getBoolean("accepted").toString(),
                want.optString("refused").takeIf { !want.isNull("refused") })
            val gotDecision = listOf(reading.decision.path, reading.decision.printingId, reading.stripNames, reading.acceptance.accepted.toString(), reading.acceptance.refused)
            if (wantDecision != gotDecision) problems += "$name: decision $gotDecision, the server $wantDecision"
            val wantFinish = want.optJSONObject("finish")?.let { it.getString("finish") to it.getString("source") }
            val gotFinish = reading.finish?.let { it.finish to it.source }
            if (stripDiffers || netDiffers || wantFinish != gotFinish) {
                stripDifferences += "$name: strip $gotStrip (net $gotNet) finish $gotFinish; the server $wantStrip (net $wantNet) finish $wantFinish"
            }
            if (reading.decision.path == "strong") strong++
            if (reading.printingId != null) added++
        }
        android.util.Log.i("Scanner", "PictureReaderParityTest with the strip: $added of ${cases.length()} added ($strong strong), ${total / cases.length()} ms a card on average; " +
            "${stripDifferences.size} strip reads differ (cubic enlargement):\n${stripDifferences.joinToString("\n")}")
        assertTrue("${stripDifferences.size} strip reads differ from the server (at most $STRIP_PIXEL_DIFFERENCES):\n${stripDifferences.joinToString("\n")}",
            stripDifferences.size <= STRIP_PIXEL_DIFFERENCES)
        assertTrue("${problems.size} differences from the server:\n${problems.joinToString("\n")}", problems.isEmpty())
    }

    /**
     * A collector line naming ANOTHER PRINTING OF THE SAME CARD refuses a decision by the picture alone (a Codex review of 2026-10-21: it was let through,
     * and the picture's printing A added where the line said B) - the strip's half of the server's confirm_by_ocr. A line naming a different card does not
     * (a Secret Lair's printed line), nor the chosen printing itself. On real captures: one the picture decides alone and whose strip has a whole line, the
     * card data's answer for that line replaced by each kind of printing.
     */
    @Test fun aLineNamingAnotherPrintingOfTheSameCardRefusesThePictureAlone() {
        val reference = JSONObject(asset("reference.json").use { String(it.readBytes()) })
        val index = index()
        val reader = PictureReader(index)
        val textReader = com.cardscanner.reading.TextReader.load(instrumentation.targetContext)!!
        val cases = reference.getJSONArray("cases").let { a -> List(a.length()) { a.getJSONObject(it) } }
        val skip = setOf("phone-0240eb84_1041_1792505189.png", "phone-0240eb84_110_1792204606.png")
        // the picture alone decided, the strip read a whole line, and the chosen card has another printing in the index
        val case = cases.first { c ->
            val w = c.getJSONObject("with_strip")
            val chosen = w.getJSONObject("decision").optString("printing_id")
            c.getString("picture") !in skip && w.getBoolean("accepted") && w.getJSONObject("decision").getString("path") == "image" &&
                w.getJSONObject("strip").getString("number").isNotEmpty() && w.getJSONObject("strip").getString("set").isNotEmpty() &&
                index.ids.any { it != chosen && reader.sameCard(it, chosen) }
        }
        val chosen = case.getJSONObject("with_strip").getJSONObject("decision").getString("printing_id")
        val sameCard = index.ids.first { it != chosen && reader.sameCard(it, chosen) }
        val otherCard = index.ids.first { it != chosen && !reader.sameCard(it, chosen) && index.facts.containsKey(it) }
        fun readWith(named: String): PictureReader.Reading {
            val picture = rgba(case.getString("picture"))
            try {
                return reader.read(picture, acceptImageAlone = true, indexCovers = true, inData = { true }, textReader = textReader,
                    stripPrinting = { _, _ -> PictureReader.StripPrinting.Found(named) })
            } finally { picture.release() }
        }
        val refused = readWith(sameCard)
        assertEquals("another printing of the same card (${index.facts[sameCard]?.name}): not accepted", false, refused.acceptance.accepted)
        assertEquals(com.cardscanner.reading.Decide.OCR_DID_NOT_CONFIRM, refused.acceptance.refused)
        assertEquals(null, refused.printingId)
        assertEquals("a different card's line is no evidence (a Secret Lair)", chosen, readWith(otherCard).printingId)
        assertEquals("the chosen printing itself", chosen, readWith(chosen).printingId)
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
        /** The foil network's probability: FoilNetParityTest finds 5e-8 on fixed crops; a crop cut here may differ by a pixel's rounding */
        const val NET_TOLERANCE = 0.01
        /** Strip reads allowed to differ on the halved pictures (cubic enlargement, not bit-exact across CPUs: see withTheStripThePhoneDecidesAsTheServer) */
        const val STRIP_PIXEL_DIFFERENCES = 3
    }
}
