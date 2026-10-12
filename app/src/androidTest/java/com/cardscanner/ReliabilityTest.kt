package com.cardscanner

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cardscanner.ai.Foil
import com.cardscanner.ai.Provider
import com.cardscanner.inventory.Finish
import com.cardscanner.inventory.Inventory
import com.cardscanner.inventory.ReviewQueue
import com.cardscanner.scryfall.CardDatabase
import com.cardscanner.scryfall.Scryfall
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic captures and intercepted HTTP only; every database/pref is isolated from app data. */
@RunWith(AndroidJUnit4::class)
class ReliabilityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var root: File
    private val stores = ArrayList<ViewModelStore>()
    private val jobs = ArrayList<Job>()

    @Before
    fun setUp() {
        // Instrumentation runs with the target UID, so use its cache for isolated fixtures.
        root = File(instrumentation.targetContext.cacheDir, "reliability-${UUID.randomUUID()}").apply { check(mkdirs()) }
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
        AppSettings(apiKeys = mapOf(Provider.GEMINI to "test-key"), autoAdd = false, detectFoil = false, captureSound = false, addedSound = false, reviewSound = false).save(context)
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { stores.forEach { it.clear() } }
        runBlocking { withTimeout(20_000) { jobs.joinAll() } }
        instrumentation.targetContext.deleteSharedPreferences("${root.name}.settings")
        root.deleteRecursively()
    }

    @Test fun dismissTargetsDisplayedScanAndKeepsNewArrivals() {
        val history = ScanHistory()
        val source = photo()
        try {
            val older = history.show(source, serverMode = false)
            val displayed = history.show(source, serverMode = false)
            // A capture arrives between rendering the button and executing its click.
            val incoming = history.show(source, serverMode = false)
            history.dismiss(displayed)
            assertEquals(listOf(incoming, older), history.scans.value.map { it.id })
            history.dismiss(displayed) // Repeated taps cannot dismiss a different capture.
            assertEquals(listOf(incoming, older), history.scans.value.map { it.id })
            history.dismiss(incoming)
            assertEquals(listOf(older), history.scans.value.map { it.id })
            assertNull(runBlocking { history.latestScan.first() })
            // Late results for older cards must not repopulate the cleared main-screen row.
            history.note(older, "Still identifying")
            assertNull(runBlocking { history.latestScan.first() })
            val next = history.show(source, serverMode = false)
            assertEquals(next, runBlocking { history.latestScan.first() }?.id)
            history.clear()
            assertNull(runBlocking { history.latestScan.first() })
            assertTrue(history.scans.value.isEmpty())
            history.dismiss(older)
            assertTrue(history.scans.value.isEmpty())
        } finally {
            source.recycle()
        }
    }

    private fun photo() = Bitmap.createBitmap(64, 88, Bitmap.Config.ARGB_8888)

    private fun card(id: String = "sol-ring", set: String = "tst", number: String = "1") = JSONObject()
        .put("id", id).put("name", "Sol Ring").put("set", set).put("set_name", "Test Set")
        .put("collector_number", number).put("finishes", JSONArray().put("nonfoil"))

    private fun printing() = Scryfall.printingOf(card(), "set_number")

    private fun client(handler: (Request) -> Pair<Int, JSONObject>) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val (code, json) = handler(request)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Test")
            .body(json.toString().toResponseBody("application/json".toMediaType())).build()
    }.build()

    private fun identifyingClient(requests: AtomicInteger = AtomicInteger()) = client { request ->
        requests.incrementAndGet()
        200 to if (request.url.host == "generativelanguage.googleapis.com") {
            JSONObject().put("candidates", JSONArray().put(JSONObject().put("content", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", "NAME: Sol Ring\nNUMBER: 1\nSET: TST"))))))
        } else card()
    }

    private fun model(http: OkHttpClient): Pair<ScannerViewModel, ViewModelStore> {
        val application = object : Application() { init { attachBaseContext(context) } }
        lateinit var viewModel: ScannerViewModel
        val store = ViewModelStore()
        instrumentation.runOnMainSync {
            viewModel = ScannerViewModel(application, http)
            store.put("scanner", viewModel)
        }
        stores.add(store)
        jobs.add(viewModel.viewModelScope.coroutineContext[Job]!!)
        return viewModel to store
    }

    private fun await(condition: () -> Boolean) = runBlocking {
        withTimeout(20_000) { while (!condition()) delay(20) }
    }

    @Test
    fun manualAddsSurviveClearingTheListAndRestarting() {
        val (vm, store) = model(identifyingClient())
        repeat(105) { vm.identify(photo(), null) }
        await { vm.waiting.value == 0 && vm.review.value.size == 105 }
        assertEquals(100, vm.scans.value.size)
        assertTrue(vm.scans.value.all { it.pendingAdd })
        assertTrue(vm.inventoryEntries.value.isEmpty())
        vm.clearScans()
        instrumentation.runOnMainSync { store.clear() }
        val (restarted, _) = model(identifyingClient())
        await { restarted.review.value.size == 105 }
        assertTrue(restarted.review.value.all { it.reason == "Waiting for Add" && it.imageFile.isFile })
        assertTrue(restarted.inventoryEntries.value.isEmpty())
    }

    @Test
    fun repeatedAddReviewAndUndoChangeQuantityOnlyOnce() {
        Inventory(context).use { it.add(printing(), Finish.REGULAR, quantity = 2) }
        val (vm, _) = model(identifyingClient())
        vm.identify(photo(), null)
        await { vm.waiting.value == 0 && vm.review.value.size == 1 }
        val scanId = vm.scans.value.single().id
        val review = vm.review.value.single()
        repeat(20) { vm.addScan(scanId); vm.addFromReview(review, printing(), Finish.REGULAR) }
        await { vm.review.value.isEmpty() && vm.inventoryEntries.value.singleOrNull()?.quantity == 3 }
        // Allow queued duplicate taps to run after the first operation completes.
        Thread.sleep(250)
        assertEquals(3, vm.inventoryEntries.value.single().quantity)
        repeat(20) { vm.undoScan(scanId) }
        await { vm.inventoryEntries.value.singleOrNull()?.quantity == 2 }
        Thread.sleep(250)
        assertNull(vm.scans.value.single().inventoryId)
        assertEquals(2, vm.inventoryEntries.value.single().quantity)
    }

    @Test
    fun inventoryCommitBeforeOutboxRemovalIsNotReplayedEvenAfterModeChange() {
        val outbox = Outbox(context)
        val image = photo()
        val item = outbox.add(image, null)
        image.recycle()
        Inventory(context).use { it.add(printing(), Finish.REGULAR, operationId = "capture:${item.captureId}") }
        AppSettings(serverMode = true, serverUrl = "http://unused.test", captureSound = false, addedSound = false, reviewSound = false).save(context)
        val requests = AtomicInteger()
        val (vm, _) = model(client { requests.incrementAndGet(); error("A committed capture must not be uploaded") })
        await { outbox.count() == 0 && vm.inventoryEntries.value.size == 1 }
        assertEquals(1, vm.inventoryEntries.value.single().quantity)
        assertEquals(0, requests.get())
    }

    @Test
    fun reviewCommitBeforeOutboxRemovalIsNotRecreated() {
        val outbox = Outbox(context)
        val image = photo()
        val item = outbox.add(image, null)
        ReviewQueue(context).use { queue ->
            val id = queue.add(image, "Sol Ring", "1", "TST", Foil.UNKNOWN, "sol-ring", "Waiting for Add", item.captureId)
            queue.remove(id) // A user already resolved/skipped it before outbox cleanup.
        }
        image.recycle()
        val requests = AtomicInteger()
        val (vm, _) = model(client { requests.incrementAndGet(); error("A settled capture must not be identified again") })
        await { outbox.count() == 0 }
        assertTrue(vm.review.value.isEmpty())
        assertEquals(0, requests.get())
    }

    @Test
    fun interruptedReviewHandoffIsFinishedWithoutAddingAgain() {
        val image = photo()
        val reviewId = ReviewQueue(context).use {
            it.add(image, "Sol Ring", "1", "TST", Foil.UNKNOWN, "sol-ring", "Waiting for Add", "capture-test")
        }
        image.recycle()
        Inventory(context).use { it.add(printing(), Finish.REGULAR, operationId = "review:$reviewId") }
        val (vm, _) = model(identifyingClient())
        await { vm.inventoryEntries.value.size == 1 }
        assertTrue(vm.review.value.isEmpty())
        assertEquals(1, vm.inventoryEntries.value.single().quantity)
        ReviewQueue(context).use { assertFalse(it.contains(reviewId)) }
    }

    @Test
    fun inventoryReceiptsAreAtomicAndSurviveUndoAndReopen() {
        val inventory = Inventory(context)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val ids = pool.invokeAll((1..12).map { Callable {
                inventory.add(printing(), Finish.REGULAR, operationId = "capture:one")
            } }).map { it.get() }
            assertEquals(1, ids.distinct().size)
            assertEquals(1, inventory.all().single().quantity)
            inventory.remove(ids.first())
            inventory.close()
            Inventory(context).use {
                it.add(printing(), Finish.REGULAR, operationId = "capture:one")
                assertTrue(it.all().isEmpty())
                assertNotNull(it.receipt("capture:one"))
            }
        } finally { pool.shutdownNow(); inventory.close() }
    }

    @Test
    fun versionOneDatabasesUpgradeWithoutLosingCardsOrReviewPhotos() {
        Inventory(context).use { it.add(printing(), Finish.REGULAR, quantity = 3) }
        val image = photo()
        ReviewQueue(context).use { it.add(image, "Sol Ring", "1", "TST", Foil.UNKNOWN, null, "Check") }
        image.recycle()
        for ((name, receiptTable) in listOf("inventory.db" to "inventory_receipts", "review.db" to "review_receipts")) {
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("DROP TABLE $receiptTable")
                it.version = 1
            }
        }
        Inventory(context).use {
            assertEquals(3, it.all().single().quantity)
            it.add(printing(), Finish.REGULAR, operationId = "new-capture")
            assertNotNull(it.receipt("new-capture"))
        }
        ReviewQueue(context).use {
            assertNotNull(it.all().single().image())
            assertNull(it.receipt("new-capture"))
        }
    }

    @Test
    fun serverConcurrencyStaysBoundedAcrossSuspendingPolls() {
        AppSettings(serverMode = true, serverUrl = "http://unused.test", captureSound = false, addedSound = false, reviewSound = false).save(context)
        val uploads = AtomicInteger()
        val (vm, _) = model(client { request ->
            if (request.method == "POST") 200 to JSONObject().put("status", "pending").put("capture", uploads.incrementAndGet())
            else 200 to JSONObject().put("status", "added").put("card", JSONObject().put("name", "Sol Ring"))
        })
        repeat(12) { vm.identify(photo(), null) }
        await { uploads.get() >= 3 }
        Thread.sleep(250) // All three workers are now suspended in the two-second poll delay.
        assertEquals(3, uploads.get())
        await { vm.waiting.value == 0 }
        assertEquals(12, uploads.get())
        assertTrue(vm.inventoryEntries.value.isEmpty())
        assertTrue(vm.review.value.isEmpty())
    }

    @Test
    fun serverRemovalTargetsCaptureAndKeepsRowOnFailure() {
        AppSettings(serverMode = true, serverUrl = "http://unused.test", captureSound = false, addedSound = false, reviewSound = false).save(context)
        val uploads = AtomicInteger()
        val removed = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val allow = java.util.concurrent.atomic.AtomicBoolean(false)
        val (vm, _) = model(client { request ->
            when (request.method) {
                "POST" -> {
                    val number = uploads.incrementAndGet()
                    200 to JSONObject().put("status", "added").put("capture", number).put("sync_key", "key-$number")
                }
                "DELETE" -> {
                    val number = request.url.pathSegments[4].toInt()
                    assertEquals("key-$number", request.url.queryParameter("sync_key"))
                    if (!allow.get()) 503 to JSONObject().put("message", "Test unavailable")
                    else { removed.add(number); 200 to JSONObject().put("success", true).put("state", "removed") }
                }
                else -> 200 to JSONObject().put("success", true).put("state", "scanned")
            }
        })
        repeat(2) { vm.identify(photo(), null) }
        await { vm.waiting.value == 0 && vm.scans.value.size == 2 }
        val displayed = vm.scans.value.first()
        vm.dismissScan(displayed.id)
        await { !vm.scans.value.first().deleting && vm.message.value?.contains("Could not remove scan") == true }
        assertEquals(2, vm.scans.value.size)
        assertTrue(removed.isEmpty())
        allow.set(true)
        vm.dismissScan(displayed.id)
        await { vm.scans.value.size == 1 }
        assertEquals(listOf(displayed.server!!.capture), removed.toList())
        await { vm.latestScan.value == null }
    }

    @Test
    fun captureTheServerCannotFollowLeavesThePhoneOnTheSecondRemove() {
        // 409: the server knows the capture but cannot follow it (scanned before it could, ...).
        // The first Remove changes nothing and says so; the second takes the row off the phone.
        AppSettings(serverMode = true, serverUrl = "http://unused.test", captureSound = false, addedSound = false, reviewSound = false).save(context)
        val deletes = AtomicInteger()
        val (vm, _) = model(client { request ->
            when (request.method) {
                "POST" -> 200 to JSONObject().put("status", "added").put("capture", 1)   // an answer without sync_key
                "DELETE" -> {
                    deletes.incrementAndGet()
                    409 to JSONObject().put("success", false).put("message", "This capture cannot be followed")
                }
                else -> 409 to JSONObject().put("success", false).put("message", "This capture cannot be followed")
            }
        })
        vm.identify(photo(), null)
        await { vm.waiting.value == 0 && vm.scans.value.singleOrNull()?.server != null }
        val id = vm.scans.value.single().id
        vm.dismissScan(id)
        await { vm.scans.value.single().serverCannotFollow && !vm.scans.value.single().deleting }
        assertTrue(vm.message.value!!.contains("cannot follow"))
        assertEquals(1, vm.scans.value.size)                    // still there after one tap
        vm.dismissScan(id)
        await { vm.scans.value.isEmpty() }
        assertEquals(1, deletes.get())                          // the server was not asked again
    }

    @Test
    fun aRemovalThatFailedForAnotherReasonKeepsItsRow() {
        // A wrong token (401) is not "cannot follow": the row stays, however often Remove is tapped
        AppSettings(serverMode = true, serverUrl = "http://unused.test", captureSound = false, addedSound = false, reviewSound = false).save(context)
        val (vm, _) = model(client { request ->
            if (request.method == "POST") 200 to JSONObject().put("status", "added").put("capture", 1).put("sync_key", "key")
            else if (request.method == "DELETE") 401 to JSONObject().put("success", false).put("message", "Wrong or missing station token")
            else 200 to JSONObject().put("success", true).put("state", "scanned")
        })
        vm.identify(photo(), null)
        await { vm.waiting.value == 0 && vm.scans.value.singleOrNull()?.server != null }
        val id = vm.scans.value.single().id
        repeat(2) {
            vm.dismissScan(id)
            await { !vm.scans.value.single().deleting && vm.message.value?.contains("Could not remove scan") == true }
        }
        assertEquals(1, vm.scans.value.size)
        assertFalse(vm.scans.value.single().serverCannotFollow)
    }

    @Test
    fun collectedServerCaptureDisappearsFromPhone() {
        AppSettings(serverMode = true, serverUrl = "http://unused.test", captureSound = false, addedSound = false, reviewSound = false).save(context)
        val collected = java.util.concurrent.atomic.AtomicBoolean(false)
        val (vm, _) = model(client { request ->
            if (request.method == "POST") 200 to JSONObject().put("status", "added").put("capture", 1).put("sync_key", "key")
            else {
                assertEquals("GET", request.method)
                assertEquals("key", request.url.queryParameter("sync_key"))
                200 to JSONObject().put("success", true).put("state", if (collected.get()) "resolved" else "scanned")
            }
        })
        vm.identify(photo(), null)
        await { vm.waiting.value == 0 && vm.scans.value.singleOrNull()?.server != null }
        collected.set(true)
        await { vm.scans.value.isEmpty() }
        assertTrue(vm.inventoryEntries.value.isEmpty())
    }

    @Test
    fun pendingRemovalSurvivesRestart() {
        AppSettings(serverMode = true, serverUrl = "http://unused.test", captureSound = false, addedSound = false, reviewSound = false).save(context)
        val uploads = AtomicInteger()
        val (vm, store) = model(client { request ->
            check(request.method == "POST")
            uploads.incrementAndGet()
            throw UnknownHostException("Test offline")
        })
        vm.identify(photo(), null)
        await { uploads.get() > 0 }
        vm.dismissScan(vm.scans.value.single().id)
        await { Outbox(context).all().single().deleteRequested }
        instrumentation.runOnMainSync { store.clear() }
        val deletes = AtomicInteger()
        val (restored, _) = model(client { request ->
            if (request.method == "POST") 200 to JSONObject().put("status", "added").put("capture", 7).put("sync_key", "key")
            else {
                assertEquals("DELETE", request.method)
                assertEquals("key", request.url.queryParameter("sync_key"))
                deletes.incrementAndGet()
                200 to JSONObject().put("success", true).put("state", "removed")
            }
        })
        await { deletes.get() == 1 && restored.waiting.value == 0 }
        assertTrue(restored.scans.value.isEmpty())
    }

    @Test
    fun cancellationAndBadServerSettingsKeepCapturesOnDisk() {
        AppSettings(serverMode = true, serverUrl = "", captureSound = false, addedSound = false, reviewSound = false).save(context)
        val (vm, store) = model(client { error("An invalid URL must not reach HTTP") })
        vm.identify(photo(), null)
        await { vm.scans.value.single().note?.contains("No server address") == true }
        assertEquals(1, Outbox(context).count())
        instrumentation.runOnMainSync { store.clear() }
        assertEquals(1, Outbox(context).count())
    }

    @Test
    fun outboxRestoresFoilIdentityAndUsesSmallThumbnails() {
        val image = Bitmap.createBitmap(1600, 2200, Bitmap.Config.ARGB_8888)
        val outbox = Outbox(context)
        outbox.add(image, image)
        image.recycle()
        val restored = Outbox(context).all().single()
        val thumbnail = restored.thumbnail()!!
        assertTrue(thumbnail.height < 560)
        thumbnail.recycle()
        val full = restored.card()!!
        assertSame(full, restored.foil(full))
        full.recycle()
        outbox.remove(restored)
        assertTrue(outbox.all().isEmpty())
    }

    private fun seedCardData() {
        SQLiteDatabase.openOrCreateDatabase(File(context.filesDir, "cards.db"), null).use { db ->
            db.execSQL("""CREATE TABLE cards (
                id TEXT PRIMARY KEY, name TEXT, name_key TEXT, front_key TEXT, flavor_key TEXT,
                set_code TEXT, set_name TEXT, number TEXT, rarity TEXT, type_line TEXT, mana_cost TEXT, colors TEXT,
                finishes TEXT, surge INTEGER, image_url TEXT, scryfall_uri TEXT,
                usd TEXT, usd_foil TEXT, eur TEXT, eur_foil TEXT, released TEXT)""")
            db.execSQL("CREATE TABLE info (key TEXT PRIMARY KEY, value TEXT)")
            db.execSQL("INSERT INTO info VALUES ('updated_at', 'test')")
            db.beginTransaction()
            try {
                repeat(250) { i ->
                    db.execSQL("""INSERT INTO cards (id, name, name_key, front_key, set_code, set_name, number,
                        colors, finishes, surge, released) VALUES (?, 'Sol Ring', 'sol ring', 'sol ring', 'TST',
                        'Test Set', ?, '', 'nonfoil', 0, ?)""", arrayOf("card-$i", i.toString(), "2026-${i.toString().padStart(3, '0')}"))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }

    @Test
    fun offlineSearchIncludesOlderPrintingsBeyondTwoHundred() {
        seedCardData()
        val db = CardDatabase(context, identifyingClient())
        try {
            assertEquals(250, db.printingsOf("Sol Ring").size)
            val found = Scryfall(identifyingClient(), db).find("Sol Ring", "0", "TST")
            assertEquals("card-0", found?.id)
            assertEquals("set_number", found?.match)
        } finally { db.close() }
    }

    @Test
    fun weakOfflineMatchChecksOnlineButStillWorksWithoutNetwork() {
        seedCardData()
        val online = client { 200 to card("new-printing", "new", "999") }
        val db = CardDatabase(context, online)
        try {
            assertEquals("new-printing", Scryfall(online, db).find("Sol Ring", "999", "NEW")?.id)
            val offline = OkHttpClient.Builder().addInterceptor { throw UnknownHostException("Test offline") }.build()
            val cached = Scryfall(offline, db).find("Sol Ring", "999", "NEW")
            assertEquals("card-249", cached?.id)
            assertEquals("name", cached?.match)
        } finally { db.close() }
    }

    @Test
    fun deletingCardDataWaitsForAnActiveLookup() {
        seedCardData()
        val db = CardDatabase(context, identifyingClient())
        val pool = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val reading = pool.submit(Callable {
                db.read {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    it.printingsOf("Sol Ring").size
                }
            })
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val deleting = pool.submit(Callable { db.delete() })
            Thread.sleep(100)
            assertFalse(deleting.isDone)
            release.countDown()
            assertEquals(250, reading.get(10, TimeUnit.SECONDS))
            deleting.get(10, TimeUnit.SECONDS)
            assertFalse(db.ready)
            assertTrue(db.printingsOf("Sol Ring").isEmpty())
        } finally { release.countDown(); pool.shutdownNow(); db.close() }
    }
}
