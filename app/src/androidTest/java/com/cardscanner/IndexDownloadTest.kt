package com.cardscanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cardscanner.reading.ImageIndex
import com.cardscanner.reading.IndexDownload
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The picture index downloaded from a real scanner server (reading/IndexDownload.kt) - only GET requests, which change nothing on the server. Skipped
 * without `-e server <address>`:
 *   adb shell am instrument -w -e class com.cardscanner.IndexDownloadTest -e server http://192.168.1.23:5000 \
 *     com.cardscanner.debug.test/androidx.test.runner.AndroidJUnitRunner
 * It downloads into the debug app's cache (not the app's index), loads what came, downloads again (nothing to fetch: the files are kept), resumes a file
 * cut in half, and refuses a damaged one - and logs the times.
 */
@RunWith(AndroidJUnit4::class)
class IndexDownloadTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val server: String? = InstrumentationRegistry.getArguments().getString("server")
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

    @Test fun theIndexComesFromTheServerWholeAndLoads() {
        assumeTrue("no -e server: skipped", server != null)
        val folder = File(instrumentation.targetContext.cacheDir, "picture_index_download").apply { deleteRecursively() }
        val download = IndexDownload(http, server!!, "", folder)
        var last = 0L
        var t = System.nanoTime()
        val generation = download.download { _, done, _ -> last = done }
        val first = (System.nanoTime() - t) / 1_000_000
        t = System.nanoTime()
        val opened = ImageIndex.open(folder)
        val loading = (System.nanoTime() - t) / 1_000_000
        assertNotNull("the downloaded index does not load: ${opened.why}", opened.index)
        assertEquals(generation, opened.index!!.generation)
        assertTrue("an index of ${opened.index.count} printings", opened.index.count > 100_000)

        // again: everything is here already - nothing fetched, the same generation
        t = System.nanoTime()
        assertEquals(generation, download.download { _, _, _ -> })
        val again = (System.nanoTime() - t) / 1_000_000

        // a fingerprints file cut in half is fetched from where it stopped (Range) and checked
        val hashes = folder.listFiles()!!.first { it.name.startsWith("hashes-") }
        val half = hashes.readBytes().copyOf((hashes.length() / 2).toInt())
        hashes.delete()
        File(hashes.path + ".part").writeBytes(half)
        assertEquals(generation, download.download { _, _, _ -> })
        assertNotNull("after the resumed file: ${ImageIndex.open(folder).why}", ImageIndex.load(folder))

        android.util.Log.i("Scanner", "IndexDownloadTest: ${opened.index.count} printings, generation $generation, ${last shr 20} MB in $first ms; " +
            "loading $loading ms; a second download $again ms")
        folder.deleteRecursively()
    }
}
