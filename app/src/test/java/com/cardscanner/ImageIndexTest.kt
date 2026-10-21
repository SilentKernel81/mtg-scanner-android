package com.cardscanner

import com.cardscanner.reading.ImageIndex
import com.cardscanner.reading.PictureReader
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

/**
 * The picture index on the phone (reading/ImageIndex.kt), on a small index written here in the server's layout: what loads, what is refused (and why),
 * the distances, and PictureReader.nearest's choice of the five nearest rows. The real index and the server's decisions are PictureReaderParityTest's.
 */
class ImageIndexTest {
    private fun hash(seed: Int) = ByteArray(ImageIndex.HASH_BYTES) { (it * 31 + seed * 17).toByte() }

    /** A folder with an index of [count] rows (row i has hash(i)), its manifest and its printings table */
    private fun write(count: Int = 3, descriptor: String = ImageIndex.DESCRIPTOR, table: String? = null): File {
        val folder = Files.createTempDirectory("index").toFile()
        val hashes = ByteArray(count * ImageIndex.HASH_BYTES).also { all -> for (i in 0 until count) hash(i).copyInto(all, i * ImageIndex.HASH_BYTES) }
        val ids = (0 until count).joinToString("\n") { "id-$it" } + "\n"
        File(folder, "hashes-g1.bin").writeBytes(hashes)
        File(folder, "ids-g1.txt").writeText(ids)
        val rows = table ?: (listOf(ImageIndex.TABLE_COLUMNS.joinToString("\t")) +
            (0 until count).map { "id-$it\tset$it\t$it\toracle-$it\tnonfoil,foil\tCard $it" }).joinToString("\n") + "\n"
        File(folder, ImageIndex.PRINTINGS).outputStream().use { out -> GZIPOutputStream(out).use { it.write(rows.toByteArray()) } }
        File(folder, ImageIndex.MANIFEST).writeText(JSONObject()
            .put("descriptor", descriptor).put("count", count).put("hash_bytes", ImageIndex.HASH_BYTES).put("built", "2026-10-21 12:00:00")
            .put("generation", "g1").put("hashes", "hashes-g1.bin").put("ids", "ids-g1.txt")
            .put("hashes_sha256", ImageIndex.sha256(hashes)).put("ids_sha256", ImageIndex.sha256(ids.toByteArray())).toString())
        return folder
    }

    @Test fun anIndexLoadsWithItsTable() {
        val index = ImageIndex.load(write())
        assertNotNull(index)
        assertEquals(3, index!!.count)
        assertEquals("g1", index.generation)
        assertEquals(1, index.row("id-1"))
        assertNull(index.row("id-9"))
        assertEquals(ImageIndex.Facts("set2", "2", "oracle-2", listOf("nonfoil", "foil"), "Card 2"), index.facts["id-2"])
    }

    @Test fun theDistancesAreTheBitsThatDiffer() {
        val index = ImageIndex.load(write())!!
        val query = hash(1).copyOf().also { it[0] = (it[0].toInt() xor 0b111).toByte(); it[95] = (it[95].toInt() xor 0b1).toByte() }   // 4 bits off row 1
        val distances = index.distances(query)
        assertEquals(4, distances[1])
        for (row in 0 until 3) {
            val expected = hash(row).indices.sumOf { Integer.bitCount((hash(row)[it].toInt() xor query[it].toInt()) and 0xFF) }
            assertEquals("row $row", expected, distances[row])          // the byte-wise popcount, as the server counts
        }
    }

    @Test fun whatDoesNotHoldTogetherIsNoIndexAndSaysWhy() {
        assertEquals("no picture index downloaded", ImageIndex.open(Files.createTempDirectory("none").toFile()).why)
        assertNull(ImageIndex.load(write(descriptor = "phash_rgb_v2")))
        val broken = write()
        File(broken, "hashes-g1.bin").writeBytes(File(broken, "hashes-g1.bin").readBytes().also { it[5] = (it[5] + 1).toByte() })
        assertEquals("the fingerprints' checksum does not hold", ImageIndex.open(broken).why)
        assertNull(ImageIndex.load(write(table = "something else\n")))
    }

    @Test fun theNearestRowsAreTheSmallestDistancesEqualOnesByRow() {
        assertEquals(listOf(3, 1, 4, 0, 2), PictureReader.nearest(intArrayOf(9, 5, 9, 1, 5, 20, 9), 5))
        assertEquals(listOf(1, 0), PictureReader.nearest(intArrayOf(2, 1), 5))
        assertEquals(emptyList<Int>(), PictureReader.nearest(IntArray(0), 5))
    }

    @Test fun theRealIndexOfTheParityTestLoadsWhenItIsThere() {
        // app/src/androidTest/assets/picture/index (picture_reference.py): skipped without it
        val folder = listOf(File("src/androidTest/assets/picture/index"), File("app/src/androidTest/assets/picture/index")).firstOrNull { it.isDirectory } ?: return
        val opened = ImageIndex.open(folder)
        assertNull("the parity test's index: ${opened.why}", opened.why)
    }
}
