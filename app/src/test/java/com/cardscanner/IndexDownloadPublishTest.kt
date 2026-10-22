package com.cardscanner

import com.cardscanner.reading.ImageIndex
import com.cardscanner.reading.IndexDownload
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

/**
 * How a downloaded picture index is published (reading/IndexDownload.kt), against a stand-in server (an OkHttp interceptor answering the server's routes):
 * a good index is published and loads; an update whose files arrive whole (the checksums hold) but do not hold together - a count that does not match
 * them - is NOT published: the index that worked stays the one that loads, and the update's own files are removed (a Codex review of 2026-10-21: the
 * manifest was renamed into place first and checked after, so such an update left the phone with no usable index, after a restart too).
 */
class IndexDownloadPublishTest {
    /** One generation as the server would serve it: its three files and the listing that names them */
    private class Generation(val generation: String, rows: Int, countClaimed: Int = rows, building: Boolean = false, cardDataUpdating: Boolean = false) {
        val hashes = ByteArray(rows * ImageIndex.HASH_BYTES) { (it * 7 + generation.length).toByte() }
        val ids = (0 until rows).joinToString("\n") { "id-$it" }.toByteArray()
        val printings = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { gz ->
                gz.write((listOf(ImageIndex.TABLE_COLUMNS.joinToString("\t")) +
                    (0 until rows).map { "id-$it\ts\t$it\to\tnonfoil\tCard $it\t2024-01-01\t2015" }).joinToString("\n").plus("\n").toByteArray())
            }
        }.toByteArray()
        val files = mapOf("hashes" to hashes, "ids" to ids, "printings" to printings)
        val listing = JSONObject().put("success", true).put("descriptor", ImageIndex.DESCRIPTOR).put("generation", generation)
            .put("count", countClaimed).put("hash_bytes", ImageIndex.HASH_BYTES).put("built", "2026-10-21 12:00:00")
            .apply {
                // the two fields of a server that says when its index is changing (2026-10-22); left out, the listing is an older server's
                if (building) put("building", JSONObject().put("running", true).put("done", 1200).put("total", 3592))
                if (cardDataUpdating) put("card_data_updating", true)
            }
            .put("files", JSONArray(files.map { (kind, bytes) ->
                JSONObject().put("kind", kind).put("bytes", bytes.size).put("sha256", ImageIndex.sha256(bytes))
                    .put("url", "/api/image_index/download/$kind?generation=$generation" + if (kind == "printings") "&version=${ImageIndex.sha256(bytes)}" else "")
            }))
    }

    /** A server that serves [current] (changed by the test); [served] is told the path of each request it answered (a test changes the index there) */
    private fun server(served: (String) -> Unit = {}, current: () -> Generation) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val served = current()
        val path = request.url.encodedPath
        val (code, type, body) = when {
            path == "/api/image_index/download" -> Triple(200, "application/json", served.listing.toString().toByteArray())
            path.startsWith("/api/image_index/download/") -> {
                val kind = path.substringAfterLast('/')
                if (request.url.queryParameter("generation") != served.generation) Triple(409, "application/json", "{}".toByteArray())
                else Triple(200, "application/octet-stream", served.files.getValue(kind))
            }
            else -> Triple(404, "application/json", "{}".toByteArray())
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Test")
            .body(body.toResponseBody(type.toMediaType())).build()
            .also { if (code == 200) served(path) }
    }.build()

    @Test fun anUpdateThatDoesNotLoadLeavesTheIndexThatWorked() {
        val folder = Files.createTempDirectory("picture_index").toFile()
        var served = Generation("g1", rows = 3)
        val http = server { served }
        val download = IndexDownload(http, "http://server.test:5000", "", folder)

        assertEquals("g1", download.download { _, _, _ -> })
        assertEquals("g1", ImageIndex.load(folder)?.generation)

        // g2: every file arrives as sent (its checksum holds) but the listing claims 5 rows of fingerprints for 4 - it cannot load
        served = Generation("g2", rows = 4, countClaimed = 5)
        try {
            download.download { _, _, _ -> }
            fail("an index that does not load was published")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("is kept"))
        }
        val still = ImageIndex.load(folder)
        assertNotNull("the index that worked no longer loads: ${ImageIndex.open(folder).why}", still)
        assertEquals("g1", still!!.generation)
        assertEquals(3, still.count)
        // nothing of g2 is left (its candidate manifest, its files); g1's files are all there
        val left = folder.list()!!.toSet()
        assertTrue(left.toString(), left.none { "g2" in it || it.endsWith(".new") })
        assertTrue(left.toString(), "hashes-g1.bin" in left && "ids-g1.txt" in left)

        // and a good update after it is published as before
        served = Generation("g3", rows = 4)
        assertEquals("g3", download.download { _, _, _ -> })
        assertEquals(4, ImageIndex.load(folder)?.count)
        assertTrue(folder.list()!!.none { "g1" in it })              // the old generation's files go once the new one is published
        folder.deleteRecursively()
    }

    @Test fun anIndexChangedDuringTheDownloadIsAskedForAgainAndTheNewOneIsPublished() {
        // The server publishes g2 right after sending g1's fingerprints: g1's ids are refused (409), the listing is asked again, g2 is downloaded whole
        val folder = Files.createTempDirectory("picture_index").toFile()
        var current = Generation("g1", rows = 3)
        val http = server(served = { path -> if (path.endsWith("/hashes") && current.generation == "g1") current = Generation("g2", rows = 4) }) { current }
        assertEquals("g2", IndexDownload(http, "http://server.test:5000", "", folder).download { _, _, _ -> })
        assertEquals(4, ImageIndex.load(folder)?.count)
        assertTrue(folder.list()!!.toString(), folder.list()!!.none { "g1" in it })     // what was fetched of g1 is gone with it
        folder.deleteRecursively()
    }

    @Test fun anIndexThatKeepsChangingIsGivenUpAndTheIndexThatWorkedStays() {
        // Two changes in one download (an automatic update: the card data, then the new generation) - after the second 409 it stops; g0 is still loaded
        val folder = Files.createTempDirectory("picture_index").toFile()
        var current = Generation("g0", rows = 3)
        var changing = false
        val http = server(served = { path ->
            if (changing && path.endsWith("/hashes")) current = Generation(if (current.generation == "g1") "g2" else "g3", rows = 4)
        }) { current }
        val download = IndexDownload(http, "http://server.test:5000", "", folder)
        assertEquals("g0", download.download { _, _, _ -> })
        changing = true
        current = Generation("g1", rows = 4)
        try {
            download.download { _, _, _ -> }
            fail("an index that kept changing was published")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("kept changing"))
        }
        assertEquals("g0", ImageIndex.load(folder)?.generation)
        folder.deleteRecursively()
    }

    @Test fun whileTheServerChangesItsIndexNothingIsDownloadedAndTheIndexThatWorkedStays() {
        val folder = Files.createTempDirectory("picture_index").toFile()
        var current = Generation("g1", rows = 3)
        val asked = mutableListOf<String>()
        val download = IndexDownload(server(served = { asked += it }) { current }, "http://server.test:5000", "", folder)
        assertEquals("g1", download.download { _, _, _ -> })

        for ((busy, says) in listOf(Generation("g2", rows = 4, building = true) to "1,200 of 3,592",
                                    Generation("g2", rows = 4, cardDataUpdating = true) to "card data")) {
            current = busy
            asked.clear()
            try {
                download.download { _, _, _ -> }
                fail("downloaded while the server was changing its index")
            } catch (e: IndexDownload.ServerBusy) {
                assertTrue(e.message.orEmpty(), e.message.orEmpty().contains(says) && e.message.orEmpty().contains("try again"))
            }
            assertEquals(listOf("/api/image_index/download"), asked)         // the listing only: not one file
            assertEquals("g1", ImageIndex.load(folder)?.generation)
        }
        // and once it is done, the new one comes as usual
        current = Generation("g2", rows = 4)
        assertEquals("g2", download.download { _, _, _ -> })
        folder.deleteRecursively()
    }

    @Test fun aFirstDownloadThatDoesNotLoadLeavesNoIndexAndNoFiles() {
        val folder = Files.createTempDirectory("picture_index").toFile()
        val bad = Generation("g1", rows = 2, countClaimed = 9)
        val download = IndexDownload(server { bad }, "http://server.test:5000", "", folder)
        try { download.download { _, _, _ -> }; fail("published") } catch (e: IOException) {}
        assertEquals("no picture index downloaded", ImageIndex.open(folder).why)
        assertEquals(emptyList<String>(), folder.list()!!.toList())
        File(folder.path).deleteRecursively()
    }
}
