package com.cardscanner

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

/**
 * Standalone, Read by the picture first, end to end: a capture handed to the app (ScannerViewModel.identify, as the camera hands it) goes through
 * CaptureProcessor.identifyOnce -> readByPicture -> settleCapture, on isolated data (ReliabilityTest's way: a context whose files, databases and
 * settings are a temporary folder), with the vision AI switched OFF and an HTTP client that fails any request - so nothing but the picture can read it.
 * The index and two pictures come from assets/picture/ (picture_reference.py, as PictureReaderParityTest); the card data is a small cards.db written here
 * with the printings of the reference's candidates. Checked: a picture the server's code adds is added to the inventory with reader "picture" and the
 * very printing; one it does not settle waits for review with the picture's best guess; no network request is made.
 *   adb shell am instrument -w -e class com.cardscanner.PictureFirstTest com.cardscanner.debug.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class PictureFirstTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var root: File
    private val stores = ArrayList<ViewModelStore>()
    private val jobs = ArrayList<Job>()
    private val requests = AtomicInteger()

    @Before
    fun setUp() {
        assumeTrue("no assets/picture/: run picture_reference.py first", "reference.json" in (instrumentation.context.assets.list("picture") ?: emptyArray()))
        assertTrue(OpenCVLoader.initLocal())
        root = File(instrumentation.targetContext.cacheDir, "picture-first-${UUID.randomUUID()}").apply { check(mkdirs()) }
        context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(root, "databases/$name").apply { parentFile!!.mkdirs() }
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("${root.name}.$name", mode)
        }
        // the picture alone (no collector line): the first test; the second reads the collector line too (it saves its own settings)
        AppSettings(pictureFirst = true, readCollectorLine = false, aiEnabled = false, autoAdd = true, detectFoil = true,
            captureSound = false, addedSound = false, reviewSound = false).save(context)
    }

    @After
    fun tearDown() {
        if (!::root.isInitialized) return
        instrumentation.runOnMainSync { stores.forEach { it.clear() } }
        runBlocking { withTimeout(20_000) { jobs.joinAll() } }
        instrumentation.targetContext.deleteSharedPreferences("${root.name}.settings")
        root.deleteRecursively()
    }

    private fun asset(name: String) = instrumentation.context.assets.open("picture/$name")

    /** The parity index, where the app keeps its own (files/picture_index), as a download leaves it */
    private fun installIndex() {
        val folder = File(context.filesDir, "picture_index").apply { mkdirs() }
        for (name in instrumentation.context.assets.list("picture/index")!!) {
            asset("index/$name").use { input ->
                if (name == "printings.tsv") File(folder, "printings.tsv.gz").outputStream().use { out -> GZIPOutputStream(out).use { input.copyTo(it) } }
                else File(folder, name).outputStream().use { input.copyTo(it) }
            }
        }
    }

    /** A cards.db (CardDatabase's schema) with the printings of [ids], their facts from the index's table */
    private fun installCardData(ids: Set<String>) {
        val folder = File(context.filesDir, "picture_index")
        val facts = com.cardscanner.reading.ImageIndex.readTable(File(folder, "printings.tsv.gz"))!!
        val db = SQLiteDatabase.openOrCreateDatabase(File(context.filesDir, "cards.db"), null)
        db.execSQL("""CREATE TABLE cards (
            id TEXT PRIMARY KEY, name TEXT NOT NULL, name_key TEXT, front_key TEXT, flavor_key TEXT,
            set_code TEXT, set_name TEXT, number TEXT, rarity TEXT, type_line TEXT, mana_cost TEXT, colors TEXT,
            finishes TEXT, surge INTEGER, image_url TEXT, scryfall_uri TEXT,
            usd TEXT, usd_foil TEXT, eur TEXT, eur_foil TEXT, released TEXT)""")
        db.execSQL("CREATE TABLE info (key TEXT PRIMARY KEY, value TEXT)")
        db.execSQL("INSERT INTO info VALUES ('updated_at', '2026-10-21T09:05:44.591+00:00')")
        for (id in ids) {
            val f = facts[id] ?: continue
            db.execSQL("INSERT INTO cards (id, name, set_code, set_name, number, rarity, type_line, mana_cost, colors, finishes, surge, scryfall_uri) " +
                "VALUES (?, ?, ?, ?, ?, '', '', '', '', ?, '0', '')", arrayOf(id, f.name, f.set, f.set.uppercase(), f.number, f.finishes.joinToString(",")))
        }
        db.close()
    }

    /** No network: any request fails the capture's reading (and is counted) - the picture must read without one */
    private val noNetwork = OkHttpClient.Builder().addInterceptor { requests.incrementAndGet(); throw IOException("no network in this test") }.build()

    private fun model(): ScannerViewModel {
        val application = object : Application() { init { attachBaseContext(context) } }
        lateinit var viewModel: ScannerViewModel
        val store = ViewModelStore()
        instrumentation.runOnMainSync {
            viewModel = ScannerViewModel(application, noNetwork)
            store.put("scanner", viewModel)
        }
        stores.add(store)
        jobs.add(viewModel.viewModelScope.coroutineContext[Job]!!)
        return viewModel
    }

    private fun await(what: String, condition: () -> Boolean) = runBlocking {
        try { withTimeout(30_000) { while (!condition()) delay(50) } } catch (e: Exception) { throw AssertionError("timed out waiting for $what") }
    }

    private fun bitmap(name: String): Bitmap {
        val options = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return asset("pictures/$name").use { BitmapFactory.decodeStream(it, null, options) }!!
    }

    /**
     * With the collector line read by the phone's OCR (Settings: Read the collector line): a capture whose line names the printing the picture agrees with
     * ("strong" on the server) is added with the reader "picture+strip"; one whose finish nothing read (the star / dot unread, the network not sure, the AI
     * off) waits for review with its printing. The reference's with_strip says which capture is which.
     */
    @Test fun theCollectorLineConfirmsThePrintingAndAnUnreadFinishWaitsForReview() {
        AppSettings(pictureFirst = true, readCollectorLine = true, aiEnabled = false, autoAdd = true, detectFoil = true,
            captureSound = false, addedSound = false, reviewSound = false).save(context)
        val cases = JSONObject(asset("reference.json").use { String(it.readBytes()) }).getJSONArray("cases").let { a -> List(a.length()) { a.getJSONObject(it) } }
        // a strong decision with a finish the strip settles, and one accepted whose finish nothing read (skipping the two halved pictures whose strip differs)
        val skip = setOf("phone-0240eb84_1041_1792505189.png", "phone-0240eb84_110_1792204606.png")
        val strong = cases.first { it.getString("picture") !in skip && it.getJSONObject("with_strip").let { w ->
            w.getBoolean("accepted") && w.getJSONObject("decision").getString("path") == "strong" && w.optJSONObject("finish")?.getString("finish") != "unknown" } }
        val unread = cases.first { it.getString("picture") !in skip && it.getJSONObject("with_strip").let { w ->
            w.getBoolean("accepted") && w.optJSONObject("finish")?.getString("finish") == "unknown" } }
        installIndex()
        installCardData(cases.flatMap { c -> c.getJSONArray("candidates").let { a -> List(a.length()) { a.getJSONObject(it).getString("id") } } }.toSet())
        val viewModel = model()
        await("the index to load and cover the card data") { viewModel.pictureIndexState.value.count != null && viewModel.pictureIndexState.value.missing == 0 }

        val one = bitmap(strong.getString("picture"))
        assertTrue(viewModel.identify(one, one))
        await("the strong card to be read") { viewModel.scans.value.firstOrNull()?.status == ScanResult.Status.DONE }
        val first = viewModel.scans.value.first()
        assertEquals(CaptureProcessor.STRIP_READER, first.reader)
        assertEquals(strong.getJSONObject("with_strip").getJSONObject("decision").getString("printing_id"), first.printing?.id)
        await("the strong card to be added") { viewModel.scans.value.first().inventoryId != null }

        val two = bitmap(unread.getString("picture"))
        assertTrue(viewModel.identify(two, two))
        await("the card with an unread finish to go to review") { viewModel.scans.value.size == 2 && viewModel.scans.value.first().reviewId != null }
        assertNull(viewModel.scans.value.first().inventoryId)
        assertEquals("network requests (the AI is off; the picture and the OCR need none)", 0, requests.get())
    }

    @Test fun thePictureAddsWhatItSettlesAndTheRestWaitsForReviewWithoutTheAi() {
        val cases = JSONObject(asset("reference.json").use { String(it.readBytes()) }).getJSONArray("cases").let { a -> List(a.length()) { a.getJSONObject(it) } }
        val added = cases.first { !it.isNull("added") }
        val notSettled = cases.first { it.isNull("added") && it.getJSONObject("decision").getString("path") == "review" }
        installIndex()
        installCardData(cases.flatMap { c -> c.getJSONArray("candidates").let { a -> List(a.length()) { a.getJSONObject(it).getString("id") } } }.toSet())
        val viewModel = model()
        await("the index to load and cover the card data") { viewModel.pictureIndexState.value.count != null && viewModel.pictureIndexState.value.missing == 0 }

        // a picture the server's code adds: added to the inventory by the picture, the very printing
        val one = bitmap(added.getString("picture"))
        assertTrue(viewModel.identify(one, one))
        await("the first card to be read") { viewModel.scans.value.firstOrNull()?.status == ScanResult.Status.DONE }
        val first = viewModel.scans.value.first()
        assertEquals(CaptureProcessor.PICTURE_READER, first.reader)
        assertEquals(added.getString("added"), first.printing?.id)
        assertEquals("picture", first.printing?.match)
        await("the first card to be added") { viewModel.scans.value.first().inventoryId != null }

        // one the picture does not settle: with the AI off it waits for review, the picture's best guess suggested
        val two = bitmap(notSettled.getString("picture"))
        assertTrue(viewModel.identify(two, two))
        await("the second card to go to review") { viewModel.scans.value.size == 2 && viewModel.scans.value.first().reviewId != null }
        val second = viewModel.scans.value.first()
        assertNull(second.inventoryId)
        assertTrue(second.error.orEmpty(), second.error.orEmpty().contains("AI is off"))
        val guess = viewModel.review.value.firstOrNull { it.id == second.reviewId }
        assertNotNull(guess)
        assertEquals(notSettled.getJSONArray("candidates").getJSONObject(0).getString("id"), guess!!.suggestedId)

        // one the picture settles whose finish nothing reads (printed in both, the network not sure, the AI off): waits for review with its printing - never
        // added as regular (the owner's choice, 2026-10-21)
        val unread = cases.first { !it.isNull("added") && it.optJSONObject("finish")?.getString("finish") == "unknown" }
        val three = bitmap(unread.getString("picture"))
        assertTrue(viewModel.identify(three, three))
        await("the third card to go to review") { viewModel.scans.value.size == 3 && viewModel.scans.value.first().reviewId != null }
        val third = viewModel.scans.value.first()
        assertNull("added although its finish is unknown", third.inventoryId)
        assertEquals(unread.getString("added"), third.printing?.id)
        assertEquals("Finish not read: foil or non-foil?", viewModel.review.value.first { it.id == third.reviewId }.reason)

        assertEquals("network requests (the AI is off, the picture needs none)", 0, requests.get())
    }
}
