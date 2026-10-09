package com.cardscanner

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cardscanner.ai.CardIdentifier
import com.cardscanner.ai.CardReading
import com.cardscanner.ai.Foil
import com.cardscanner.ai.Usage
import com.cardscanner.inventory.ExportFormat
import com.cardscanner.inventory.Finish
import com.cardscanner.inventory.Inventory
import com.cardscanner.inventory.InventoryEntry
import com.cardscanner.inventory.parseInventoryCsv
import com.cardscanner.inventory.ReviewItem
import com.cardscanner.inventory.ReviewQueue
import com.cardscanner.detection.CardTracker
import com.cardscanner.detection.findCardOutline
import com.cardscanner.detection.scaled
import com.cardscanner.detection.warpCard
import com.cardscanner.scryfall.Printing
import com.cardscanner.scryfall.Scryfall
import com.cardscanner.server.ScannerServer
import com.cardscanner.server.ServerOutcome
import com.cardscanner.server.ServerRefused
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One captured card and what became of it */
data class ScanResult(
    val id: Long,
    val thumbnail: Bitmap,
    val status: Status = Status.IDENTIFYING,
    val reading: CardReading? = null,
    val printing: Printing? = null,
    val foil: Boolean? = null,
    /** Why the finish was chosen: from the printing data (certain) or from the ★/• marker */
    val foilReason: String? = null,
    val seconds: Double? = null,
    val error: String? = null,
    /** Tokens used by the AI requests for this card */
    val usage: Usage? = null,
    /** The inventory entry this card was added to (null: not added) */
    val inventoryId: Long? = null,
    /** The review queue item it went to (uncertain while adding automatically), or null */
    val reviewId: Long? = null,
    /** Client mode: what the server made of the card (null in standalone mode, and until it answers) */
    val server: ServerOutcome? = null,
    /** Client mode: the server's add was taken back (Undo) */
    val undone: Boolean = false,
    /** What is going on while IDENTIFYING, when it isn't the AI ("Sending…", "Server not reachable…") */
    val note: String? = null,
) {
    /** The finish it is added as: surge foil printings' foils are surge foils */
    val finish get() = when {
        foil != true -> Finish.REGULAR
        printing?.surgeFoil == true -> Finish.SURGE
        else -> Finish.FOIL
    }

    enum class Status { IDENTIFYING, DONE, FAILED }
}

class ScannerViewModel(application: Application) : AndroidViewModel(application) {
    private val http = CardIdentifier.httpClient()
    private val cardData = com.cardscanner.scryfall.CardDatabase(application, http)
    private val scryfall = Scryfall(http, cardData)

    /** Offline card data: what is downloaded, or the download's progress / error */
    data class CardDataState(val printings: Int? = null, val updatedAt: String? = null, val busy: Boolean = false,
                             val message: String? = null, val updateAvailable: Boolean? = null)

    private val _cardDataState = MutableStateFlow(CardDataState())
    val cardDataState: StateFlow<CardDataState> = _cardDataState.asStateFlow()

    private fun refreshCardData(message: String? = null) {
        val info = cardData.info()
        _cardDataState.value = CardDataState(info?.first, info?.second, message = message)
    }

    /** Check Scryfall for newer card data (Settings) */
    fun checkCardDataUpdate() {
        viewModelScope.launch(Dispatchers.IO) {
            _cardDataState.update { it.copy(updateAvailable = cardData.updateAvailable()) }
        }
    }

    /** Download (or update) the offline card data - a few minutes, ~80 MB */
    fun downloadCardData() {
        if (_cardDataState.value.busy) return
        viewModelScope.launch(Dispatchers.IO) {
            _cardDataState.update { it.copy(busy = true, message = "Downloading card data from Scryfall…") }
            val started = System.currentTimeMillis()
            try {
                cardData.download { progress -> _cardDataState.update { it.copy(message = progress) } }
                refreshCardData("Done in ${(System.currentTimeMillis() - started) / 1000} s")
            } catch (e: Exception) {
                Log.e(TAG, "Card data download failed", e)
                refreshCardData("Download failed: ${e.message}")
            }
        }
    }

    fun deleteCardData() {
        viewModelScope.launch(Dispatchers.IO) { cardData.delete(); refreshCardData() }
    }
    private val nextId = AtomicLong()
    private val inventory = Inventory(application)
    private val reviewQueue = ReviewQueue(application)

    private val _review = MutableStateFlow<List<ReviewItem>>(emptyList())
    val review: StateFlow<List<ReviewItem>> = _review.asStateFlow()

    private val _inventoryEntries = MutableStateFlow<List<InventoryEntry>>(emptyList())
    val inventoryEntries: StateFlow<List<InventoryEntry>> = _inventoryEntries.asStateFlow()

    /** Files to share (an export): the screen opens the share sheet */
    private val _share = MutableStateFlow<Intent?>(null)
    val share: StateFlow<Intent?> = _share.asStateFlow()

    init {
        refreshInventory()
        viewModelScope.launch(Dispatchers.IO) { refreshCardData() }
    }

    private val _settings = MutableStateFlow(AppSettings.load(application))
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _detection = MutableStateFlow<CardTracker.State?>(null)
    val detection: StateFlow<CardTracker.State?> = _detection.asStateFlow()

    private val _scans = MutableStateFlow<List<ScanResult>>(emptyList())
    val scans: StateFlow<List<ScanResult>> = _scans.asStateFlow()

    private val _autoCapture = MutableStateFlow(false)
    val autoCapture: StateFlow<Boolean> = _autoCapture.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val tracker = CardTracker(_settings.value.stableFrames, _settings.value.minSharpness.toDouble())
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val captureExecutor = Executors.newSingleThreadExecutor()
    // One AI request at a time, in capture order
    private val aiDispatcher = Dispatchers.IO.limitedParallelism(1)
    // Client mode: a few uploads at once (the server reads them in its own queues)
    private val serverDispatcher = Dispatchers.IO.limitedParallelism(3)

    /** Taking the still image of a capture (the next auto-capture waits for it) */
    private val capturing = AtomicBoolean(false)
    @Volatile private var pendingReset = false

    var imageCapture: ImageCapture? = null

    /** Captures not settled yet, on disk: they wait for the network, also across a restart */
    private val outbox = Outbox(application)

    /** How many captures are waiting (no network, or the server / AI is still busy with them) */
    private val _waiting = MutableStateFlow(0)
    val waiting: StateFlow<Int> = _waiting.asStateFlow()

    init {
        // Captures that were still waiting when the app was closed
        viewModelScope.launch(Dispatchers.IO) {
            val items = outbox.all()
            _waiting.value = items.size
            if (items.isEmpty()) return@launch
            Log.i(TAG, "${items.size} capture(s) waiting from before")
            for (item in items) {
                val photo = item.card()
                if (photo == null) { outbox.remove(item); continue }
                process(showScan(photo), null, null, item)
            }
        }
    }

    fun updateSettings(newSettings: AppSettings) {
        var settings = newSettings
        if (settings.rotation != _settings.value.rotation) {
            pendingReset = true  // outlines of the old orientation
            if (settings.fixedAreaEnabled) {
                // An area drawn for the old orientation no longer fits: draw it again
                settings = settings.copy(fixedAreaEnabled = false, fixedArea = null)
                _message.value = "Fixed area off - draw it again"
            }
        }
        _settings.value = settings
        settings.save(getApplication())
        tracker.requiredStableFrames = settings.stableFrames
        tracker.minSharpness = settings.minSharpness.toDouble()
    }

    /** Turn the camera image a quarter turn clockwise (like the Python scanner's rotate button) */
    fun rotate() = updateSettings(_settings.value.let { it.copy(rotation = (it.rotation + 90) % 360) })

    /** Draw a fixed area (fractions of the frame) and switch to it */
    fun setFixedArea(area: List<Double>) =
        updateSettings(_settings.value.copy(fixedArea = area.map { it.coerceIn(0.0, 1.0) }, fixedAreaEnabled = true))

    fun setFixedAreaEnabled(enabled: Boolean) = updateSettings(_settings.value.copy(fixedAreaEnabled = enabled))

    /** The detected card plus 5% as the fixed area (scanner.py:detected_area); false without a card */
    fun useDetectedCard(margin: Double = 0.05): Boolean {
        val state = _detection.value
        val corners = state?.corners ?: return false
        val x1 = corners.minOf { it.x }; val x2 = corners.maxOf { it.x }
        val y1 = corners.minOf { it.y }; val y2 = corners.maxOf { it.y }
        val dx = (x2 - x1) * margin; val dy = (y2 - y1) * margin
        setFixedArea(listOf((x1 - dx) / state.frameWidth, (y1 - dy) / state.frameHeight,
            (x2 + dx) / state.frameWidth, (y2 + dy) / state.frameHeight))
        return true
    }

    fun setAutoCapture(enabled: Boolean) {
        _autoCapture.value = enabled
        if (enabled) {
            pendingReset = true  // the card lying there now counts as new
            if (!_settings.value.serverMode) viewModelScope.launch(Dispatchers.IO) { identifier().warmUp() }
        }
    }

    fun clearMessage() { _message.value = null }

    fun clearScans() { _scans.value = emptyList() }

    // ------------------------------------------------------------------------
    // Inventory
    // ------------------------------------------------------------------------

    private fun refreshInventory() {
        viewModelScope.launch(Dispatchers.IO) {
            _inventoryEntries.value = inventory.all()
            _review.value = reviewQueue.all()
        }
    }

    // ------------------------------------------------------------------------
    // Review queue
    // ------------------------------------------------------------------------

    /** Queue an uncertain card with its photo; scanning goes on */
    private fun queueForReview(scanId: Long, photo: Bitmap, reading: CardReading?, marker: Foil, printing: Printing?, reason: String) {
        val reviewId = reviewQueue.add(photo, reading?.name.orEmpty(), reading?.collectorNumber.orEmpty(),
            reading?.setCode.orEmpty(), marker, printing?.id, reason)
        _scans.update { list -> list.map { if (it.id == scanId) it.copy(reviewId = reviewId) else it } }
        _review.value = reviewQueue.all()
        if (_settings.value.sounds) Sounds.review()
        Log.i(TAG, "Review queue: #$scanId ($reason)")
    }

    /** The suggested printing of a review item (fetched again: only its id is kept) */
    suspend fun suggestion(item: ReviewItem): Printing? = item.suggestedId?.let { id ->
        kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { scryfall.byId(id) }.getOrNull() }
    }

    /** Ask the AI again about a review item's photo (it failed, e.g. no network): what it read, or null */
    suspend fun identifyAgain(item: ReviewItem): CardReading? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val photo = item.image() ?: return@withContext null
        identifier().identify(photo).second
    }

    /** Printings to choose from in a review (Scryfall search) */
    suspend fun searchPrintings(name: String, setCode: String, number: String): List<Printing> =
        kotlinx.coroutines.withContext(Dispatchers.IO) { scryfall.printings(name, setCode, number) }

    /** The finish a printing is suggested in, from its finishes and the ★/• marker */
    fun suggestedFinish(printing: Printing, marker: Foil): Finish =
        ScanResult(0, emptyBitmap, printing = printing, foil = finish(printing, marker).first).finish

    /** Add a reviewed card and resolve the item */
    fun addFromReview(item: ReviewItem, printing: Printing, finish: Finish) {
        viewModelScope.launch(Dispatchers.IO) {
            val entryId = inventory.add(printing, finish)
            if (_settings.value.sounds) Sounds.added()
            reviewQueue.remove(item.id)
            _scans.update { list -> list.map { if (it.reviewId == item.id) it.copy(reviewId = null, inventoryId = entryId, printing = printing) else it } }
            _inventoryEntries.value = inventory.all()
            _review.value = reviewQueue.all()
        }
    }

    /** Drop a review item (the card is not added) */
    fun skipReview(item: ReviewItem) {
        viewModelScope.launch(Dispatchers.IO) {
            reviewQueue.remove(item.id)
            _scans.update { list -> list.map { if (it.reviewId == item.id) it.copy(reviewId = null) else it } }
            _review.value = reviewQueue.all()
        }
    }

    /** Add a scanned card to the inventory (unconfirmed cards: after the user checked it) */
    fun addScan(scanId: Long) {
        val scan = _scans.value.firstOrNull { it.id == scanId } ?: return
        val printing = scan.printing ?: return
        if (scan.inventoryId != null) return
        viewModelScope.launch(Dispatchers.IO) {
            val entryId = inventory.add(printing, scan.finish)
            if (_settings.value.sounds) Sounds.added()
            _scans.update { list -> list.map { if (it.id == scanId) it.copy(inventoryId = entryId) else it } }
            _inventoryEntries.value = inventory.all()
        }
    }

    /** Take a scanned card back out of the inventory */
    fun undoScan(scanId: Long) {
        val entryId = _scans.value.firstOrNull { it.id == scanId }?.inventoryId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            inventory.remove(entryId)
            _scans.update { list -> list.map { if (it.id == scanId) it.copy(inventoryId = null) else it } }
            _inventoryEntries.value = inventory.all()
        }
    }

    fun updateEntry(id: Long, quantity: Int, condition: String, finish: Finish) {
        viewModelScope.launch(Dispatchers.IO) {
            inventory.update(id, quantity, condition, finish)
            _inventoryEntries.value = inventory.all()
        }
    }

    fun deleteEntry(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            inventory.delete(id)
            _scans.update { list -> list.map { if (it.inventoryId == id) it.copy(inventoryId = null) else it } }
            _inventoryEntries.value = inventory.all()
        }
    }

    /** Correct the printing of an inventory entry (search in the inventory's edit dialog) */
    fun changePrinting(id: Long, printing: Printing) {
        viewModelScope.launch(Dispatchers.IO) {
            inventory.changePrinting(id, printing)
            _inventoryEntries.value = inventory.all()
        }
    }

    /** Progress / result of a CSV import, shown on the inventory screen */
    private val _importStatus = MutableStateFlow<String?>(null)
    val importStatus: StateFlow<String?> = _importStatus.asStateFlow()

    fun clearImportStatus() { _importStatus.value = null }

    /**
     * Import an inventory CSV (this app's / the Python scanner's export, or Moxfield's): every row
     * is looked up on Scryfall by set + number (then by name) and merged into the inventory with
     * its quantity, condition and finish, at today's price.
     */
    fun importCsv(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _importStatus.value = "Reading the file…"
                val text = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                val rows = parseInventoryCsv(text)
                if (rows.isEmpty()) { _importStatus.value = "No cards in the file"; return@launch }
                _importStatus.value = "Looking up ${rows.size} cards on Scryfall…"
                val identifiers = rows.map { Scryfall.Identifier(scryfall.resolveSet(it.set).orEmpty(), it.number, it.name) }
                val found = scryfall.collection(identifiers).toMutableList()
                // Not found by set + number (a number typed differently, an unknown set): by name
                val retry = found.indices.filter { found[it] == null }
                if (retry.isNotEmpty()) {
                    val byName = scryfall.collection(retry.map { identifiers[it].copy(number = "") })
                    retry.forEachIndexed { i, index -> found[index] = byName[i]?.copy(match = "imported_by_name") }
                }
                var cards = 0
                val missing = ArrayList<String>()
                rows.forEachIndexed { i, row ->
                    val printing = found[i]
                    if (printing == null) missing.add(row.name)
                    else { inventory.add(printing, row.finish, row.condition, row.quantity, row.timestamp ?: Inventory.now()); cards += row.quantity }
                }
                _inventoryEntries.value = inventory.all()
                val byName = found.count { it?.match == "imported_by_name" }
                _importStatus.value = "Imported $cards cards (${rows.size - missing.size} of ${rows.size} rows)" +
                    (if (byName > 0) ". $byName found by name only - check their printing" else "") +
                    (if (missing.isNotEmpty()) ". Not found: ${missing.take(10).joinToString(", ")}" + (if (missing.size > 10) ", …" else "") else "")
            } catch (e: Exception) {
                Log.e(TAG, "Import failed", e)
                _importStatus.value = "Import failed: ${e.message}"
            }
        }
    }

    fun clearInventory() {
        viewModelScope.launch(Dispatchers.IO) {
            inventory.clear()
            _scans.update { list -> list.map { it.copy(inventoryId = null) } }
            _inventoryEntries.value = inventory.all()
        }
    }

    /**
     * Write the inventory in an export format to the app's cache and hand it to the share sheet
     * (save to Files / Drive, e-mail, ...)
     */
    fun export(format: ExportFormat) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val dir = java.io.File(app.cacheDir, "exports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }  // only the latest export is kept
            val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val file = java.io.File(dir, "${format.filePrefix}_$stamp.csv")
            file.writeText(format.write(inventory.all()))
            val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            _share.value = Intent.createChooser(send, "Export ${format.label}")
        }
    }

    fun shareHandled() { _share.value = null }

    private fun identifier() = _settings.value.let {
        CardIdentifier(it.provider, it.model(), it.apiKey(), it.localUrl, http)
    }

    // ------------------------------------------------------------------------
    // Camera frames
    // ------------------------------------------------------------------------

    val analyzer = ImageAnalysis.Analyzer { image -> analyze(image) }
    val analyzerExecutor get() = analysisExecutor

    private fun analyze(image: ImageProxy) {
        try {
            if (pendingReset) { pendingReset = false; tracker.reset() }
            tracker.setArea(_settings.value.activeArea)
            val frame = image.toRgbaMat(_settings.value.rotation)
            val (state, trigger) = tracker.process(frame, _autoCapture.value, capturing.get())
            frame.release()
            _detection.value = state
            state.message?.let { Log.i(TAG, it) }
            if (trigger) capture(state)
        } catch (e: Exception) {
            Log.e(TAG, "Frame analysis failed", e)
        } finally {
            image.close()
        }
    }

    /** Capture button: the card detected now */
    fun manualCapture() {
        val state = _detection.value
        // Fixed area: the photo is the area, whatever the detector sees
        if (state == null || (state.corners == null && state.area == null)) {
            _message.value = "No card detected"
            return
        }
        if (!capturing.get()) {
            analysisExecutor.execute { tracker.markCaptured() }
            capture(state)
        }
    }

    /**
     * Take a full-resolution still, find the card in it again (starting from the outline in the
     * analysis frame) and identify it in the background.
     */
    private fun capture(state: CardTracker.State) {
        val capture = imageCapture ?: return
        if (!capturing.compareAndSet(false, true)) return
        if (_settings.value.sounds) Sounds.capture()
        capture.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val card = try {
                    cutOutCard(image, state)
                } catch (e: Exception) {
                    Log.e(TAG, "Capture failed", e)
                    null
                } finally {
                    image.close()
                    capturing.set(false)
                }
                if (card == null) _message.value = "Capture failed" else identify(card.first, card.second)
            }

            override fun onError(exception: ImageCaptureException) {
                capturing.set(false)
                Log.e(TAG, "Capture failed", exception)
                _message.value = "Capture failed: ${exception.message}"
            }
        })
    }

    /**
     * The photo (the flat card; in fixed-area mode the area as drawn) and the flat card for the
     * ★/• check (null in fixed-area mode without an outline inside the area)
     */
    private fun cutOutCard(image: ImageProxy, state: CardTracker.State): Pair<Bitmap, Bitmap?> {
        var bitmap = image.toBitmap()
        val rotation = (image.imageInfo.rotationDegrees + _settings.value.rotation) % 360
        if (rotation != 0) {
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        }
        val full = Mat()
        Utils.bitmapToMat(bitmap, full)  // RGBA
        // Same 4:3 view as the analysis frame, so its outline and area only need scaling; the
        // still is searched again for the exact edges (the card may have shifted a pixel or two)
        val scaleX = full.cols().toDouble() / state.frameWidth
        val scaleY = full.rows().toDouble() / state.frameHeight
        val flat = state.corners?.let { corners ->
            val guess = corners.scaled(scaleX, scaleY)
            val warped = warpCard(full, findCardOutline(full, previous = guess)?.corners ?: guess)
            warped.toBitmap().also { warped.release() }
        }
        val photo = state.area?.let { (x1, y1, x2, y2) ->
            // The photo is the area as drawn: nothing inside it is cut off (a holo streak once
            // passed for a card's top edge and the outline cut off the name)
            val left = (x1 * scaleX).toInt().coerceIn(0, full.cols() - 1)
            val top = (y1 * scaleY).toInt().coerceIn(0, full.rows() - 1)
            val right = (x2 * scaleX).toInt().coerceIn(left + 1, full.cols())
            val bottom = (y2 * scaleY).toInt().coerceIn(top + 1, full.rows())
            val region = full.submat(org.opencv.core.Rect(left, top, right - left, bottom - top))
            region.toBitmap()
        } ?: flat ?: throw IllegalStateException("No card outline")
        full.release()
        return photo to flat
    }

    // ------------------------------------------------------------------------
    // Identification
    // ------------------------------------------------------------------------

    /** A capture: shown in the list, saved in the outbox and processed (see process) */
    private fun identify(card: Bitmap, foilCard: Bitmap?) {
        val id = showScan(card)
        Log.i(TAG, "Capture #$id: ${card.width}x${card.height}" + if (foilCard == null) " (no outline: no foil check)" else "")
        process(id, card, foilCard, null)
    }

    /** Put a capture at the top of the list; returns its id there */
    private fun showScan(card: Bitmap): Long {
        val thumbHeight = 280
        val thumbnail = Bitmap.createScaledBitmap(card, thumbHeight * card.width / card.height, thumbHeight, true)
        val id = nextId.incrementAndGet()
        val note = if (_settings.value.serverMode) "Sending to the server…" else null
        _scans.update { (listOf(ScanResult(id, thumbnail, note = note)) + it).take(MAX_SCANS) }
        return id
    }

    /**
     * See a capture through: sent to the server (client mode) or identified here (standalone) -
     * whichever the app is set to when its turn comes. It is saved in the outbox first and stays
     * there until it is settled; while there is no network it is tried again (2 s, 4 s, ... 30 s
     * apart), and after a restart it starts over from the outbox (`saved`, without bitmaps).
     */
    private fun process(id: Long, firstCard: Bitmap?, firstFoil: Bitmap?, saved: Outbox.Item?) {
        viewModelScope.launch(Dispatchers.IO) {
            var card = firstCard
            var foil = firstFoil
            val item = saved ?: try {
                outbox.add(card!!, foil)
            } catch (e: Exception) {
                Log.e(TAG, "Capture #$id could not be saved - it is processed, but not kept if that fails", e)
                null
            }
            _waiting.value = outbox.count()
            val captureId = item?.captureId ?: java.util.UUID.randomUUID().toString()
            val start = System.nanoTime()
            var wait = 2_000L
            while (true) {
                val photo = card ?: item?.card()
                if (photo == null) { fail(id, "The saved capture is gone"); break }
                val foilPhoto = if (card != null) foil else item!!.foil(photo)
                val settled =
                    if (_settings.value.serverMode) kotlinx.coroutines.withContext(serverDispatcher) { sendOnce(id, photo, foilPhoto, captureId, start) }
                    else kotlinx.coroutines.withContext(aiDispatcher) { identifyOnce(id, photo, foilPhoto, start) }
                if (settled) break
                // Waiting for the network: the pictures are on disk, not kept in memory meanwhile
                if (item != null) { card = null; foil = null }
                kotlinx.coroutines.delay(wait)
                wait = minOf(wait * 2, 30_000L)
            }
            item?.let { outbox.remove(it) }
            _waiting.value = outbox.count()
        }
    }

    /**
     * Standalone: identify a capture and add it, queue it for review or leave it for Add.
     * False when there is no network (nothing happened to it - try again), else true.
     */
    private fun identifyOnce(id: Long, card: Bitmap, foilCard: Bitmap?, start: Long): Boolean {
        val thumbnail = _scans.value.firstOrNull { it.id == id }?.thumbnail ?: emptyBitmap
        lateinit var reading: CardReading
        var marker = Foil.UNKNOWN
        val settings = _settings.value
        val result = try {
            val identifier = identifier()
            val (answer, read) = identifier.identify(card)
            if (read == null) {
                val error = "The AI could not read the card" + (if (answer.isNotBlank()) ": \"${answer.take(120)}\"" else "")
                fail(id, error)
                if (settings.autoAdd) queueForReview(id, card, null, Foil.UNKNOWN, null, "The AI could not read the card")
                return true
            }
            reading = read
            // The ★/• corner is only at a known place on the flat, tightly cropped card
            marker = if (settings.detectFoil && foilCard != null) identifier.readFoilSymbol(foilCard) else Foil.UNKNOWN
            val printing = scryfall.find(reading.name, reading.collectorNumber, reading.setCode)
            val (foil, reason) = finish(printing, marker)
            ScanResult(id, thumbnail, ScanResult.Status.DONE, reading, printing, foil, reason,
                seconds = (System.nanoTime() - start) / 1e9, usage = identifier.usage,
                error = if (printing == null) "Not found on Scryfall" else null)
        } catch (e: Exception) {
            if (isOffline(e)) {
                // The AI or Scryfall can't be reached: the capture waits in the outbox
                Log.w(TAG, "Capture #$id not identified (${e.message}) - waiting for the network")
                note(id, "No network - waiting to identify…")
                return false
            }
            Log.e(TAG, "Identification failed", e)
            fail(id, e.message ?: e.toString())
            // Not lost: the photo waits in the review queue (a wrong key or a quota error, say)
            if (settings.autoAdd) queueForReview(id, card, null, marker, null, "Identification failed: ${e.message?.take(80)}")
            return true
        }
        Log.i(TAG, "Identified #$id: ${reading.name} #${reading.collectorNumber} [${reading.setCode}] -> " +
            (result.printing?.let { "${it.name} ${it.setCode} #${it.collectorNumber} (${it.match})" } ?: "not found") +
            " foil=${result.foil} in %.1f s".format(result.seconds) +
            " - ${settings.provider.id}/${settings.model()} tokens ${result.usage}")
        // Confirmed printings go into the inventory right away (the Python scanner's auto_add);
        // anything uncertain goes to the review queue and scanning goes on. With automatic
        // adds off, every card waits for Add in the list.
        val added = if (settings.autoAdd && result.printing?.confirmed == true) inventory.add(result.printing, result.finish) else null
        _scans.update { list -> list.map { if (it.id == id) result.copy(inventoryId = added) else it } }
        if (added != null) {
            _inventoryEntries.value = inventory.all()
            if (settings.sounds) Sounds.added()
        }
        if (settings.autoAdd && added == null) {
            val why = result.printing?.let { "Check: matched by ${it.match.replace('_', ' ')}" } ?: "Not found on Scryfall"
            queueForReview(id, card, reading, marker, result.printing, why)
        }
        return true
    }

    /** No connection to whatever was asked (as opposed to an answer that is an error) */
    private fun isOffline(e: Exception) = e is java.net.UnknownHostException || e is java.net.SocketException ||
        e is java.net.SocketTimeoutException || e is javax.net.ssl.SSLException

    // ------------------------------------------------------------------------
    // Client mode: the scanner server does the rest
    // ------------------------------------------------------------------------

    private fun server() = _settings.value.let {
        ScannerServer(it.serverUrl, it.stationId, it.stationName.ifBlank { it.stationId }, it.stationToken, http)
    }

    /** This phone's page on the server (its cards and its review queue), or null without an address */
    val serverPageUrl get() = server().takeIf { it.baseUrl.isNotEmpty() }?.pageUrl

    /** Settings: is the server there? */
    suspend fun testServer(): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try { server().test() } catch (e: Exception) { "Not reachable: ${e.message}" }
    }

    /** The newest card the server added for this phone and that wasn't taken back: the one Undo is for */
    private val _serverUndo = MutableStateFlow<Long?>(null)
    val serverUndo: StateFlow<Long?> = _serverUndo.asStateFlow()

    /**
     * Client mode: send a capture to the server and show what became of it. False when the
     * server can't be reached (try again - under the same capture id it is one card however often
     * it is sent), else true.
     */
    private suspend fun sendOnce(id: Long, card: Bitmap, foilCard: Bitmap?, captureId: String, start: Long): Boolean {
        try {
            val server = server()
            var outcome = server.sendCapture(card, foilCard, captureId)
            // Still being read after the wait (a long AI queue): ask until it is settled
            var asked = 0
            while (outcome.status == ServerOutcome.Status.PENDING && outcome.capture != null && asked++ < 90) {
                note(id, "The server is reading it…")
                kotlinx.coroutines.delay(2_000)
                outcome = server.outcome(outcome.capture)
            }
            serverAnswered(id, outcome, (System.nanoTime() - start) / 1e9)
        } catch (e: ServerRefused) {
            Log.e(TAG, "The server refused capture #$id: ${e.message}")
            fail(id, "Server: ${e.message}")
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Capture #$id not sent (${e.message}) - waiting for the server")
            note(id, "Server not reachable - waiting to send…")
            return false
        }
        return true
    }

    private fun note(id: Long, text: String) {
        _scans.update { list -> list.map { if (it.id == id && it.status == ScanResult.Status.IDENTIFYING) it.copy(note = text) else it } }
    }

    private fun serverAnswered(id: Long, outcome: ServerOutcome, seconds: Double) {
        Log.i(TAG, "Server #$id: ${outcome.status} ${outcome.card?.name ?: outcome.reason ?: outcome.message.orEmpty()}" +
            " (read by ${outcome.reader}) in %.1f s".format(seconds))
        val reading = CardReading(outcome.readName, outcome.readNumber, outcome.readSet)
        when (outcome.status) {
            ServerOutcome.Status.ADDED, ServerOutcome.Status.REVIEW -> {
                _scans.update { list -> list.map {
                    if (it.id == id) it.copy(status = ScanResult.Status.DONE, server = outcome, reading = reading, seconds = seconds, note = null) else it
                } }
                if (outcome.status == ServerOutcome.Status.ADDED) _serverUndo.value = id
                if (_settings.value.sounds) { if (outcome.status == ServerOutcome.Status.ADDED) Sounds.added() else Sounds.review() }
            }
            ServerOutcome.Status.PENDING -> fail(id, "The server is still reading it - see its page")
            ServerOutcome.Status.ERROR -> fail(id, "Server: ${outcome.message ?: "error"}")
        }
    }

    /** Take back the last card the server added for this phone (the server keeps one Undo per station) */
    fun undoOnServer() {
        val id = _serverUndo.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val name = server().undo()
                if (name == null) _message.value = "Nothing to undo on the server"
                else _scans.update { list -> list.map { if (it.id == id) it.copy(undone = true) else it } }
                _serverUndo.value = null
            } catch (e: Exception) {
                _message.value = "Undo failed: ${e.message}"
            }
        }
    }

    private fun fail(id: Long, error: String) {
        _scans.update { list -> list.map { if (it.id == id) it.copy(status = ScanResult.Status.FAILED, error = error) else it } }
    }

    /**
     * Foil or not: printings that exist in only one finish are known for certain; otherwise the
     * ★/• marker decides (Game.suggested_finish / scanner.js:suggestedFinish)
     */
    private fun finish(printing: Printing?, marker: Foil): Pair<Boolean?, String?> {
        val finishes = printing?.finishes.orEmpty()
        val hasFoil = "foil" in finishes || "etched" in finishes
        val hasNonFoil = "nonfoil" in finishes
        return when {
            hasFoil && !hasNonFoil -> true to "only printed in foil"
            hasNonFoil && !hasFoil -> false to "only printed non-foil"
            marker == Foil.FOIL -> true to "★ marker"
            marker == Foil.NON_FOIL -> false to "• marker"
            else -> null to null
        }
    }

    override fun onCleared() {
        analysisExecutor.shutdown()
        captureExecutor.shutdown()
    }

    companion object {
        private val emptyBitmap: Bitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }
        private const val TAG = "Scanner"
        private const val MAX_SCANS = 100
    }
}

private fun Mat.toBitmap(): Bitmap =
    Bitmap.createBitmap(cols(), rows(), Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(this, it) }

/** RGBA_8888 analysis frame as an upright RGBA Mat, turned `extraRotation` degrees further clockwise */
private fun ImageProxy.toRgbaMat(extraRotation: Int): Mat {
    val plane = planes[0]
    val buffer = plane.buffer.apply { rewind() }
    val mat = Mat(height, width, CvType.CV_8UC4)
    val rowBytes = width * 4
    if (plane.rowStride == rowBytes) {
        val bytes = ByteArray(rowBytes * height)
        buffer.get(bytes)
        mat.put(0, 0, bytes)
    } else {
        // Rows padded to rowStride
        val row = ByteArray(rowBytes)
        for (y in 0 until height) {
            buffer.position(y * plane.rowStride)
            buffer.get(row)
            mat.put(y, 0, row)
        }
    }
    val code = when ((imageInfo.rotationDegrees + extraRotation) % 360) {
        90 -> Core.ROTATE_90_CLOCKWISE
        180 -> Core.ROTATE_180
        270 -> Core.ROTATE_90_COUNTERCLOCKWISE
        else -> return mat
    }
    val rotated = Mat()
    Core.rotate(mat, rotated, code)
    mat.release()
    return rotated
}
