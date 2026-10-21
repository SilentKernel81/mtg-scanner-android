package com.cardscanner

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.cardscanner.ai.CardIdentifier
import com.cardscanner.ai.CardReading
import com.cardscanner.ai.Foil
import com.cardscanner.inventory.CollectionRepository
import com.cardscanner.inventory.FinishPolicy
import com.cardscanner.reading.PictureIndexRepository
import com.cardscanner.reading.PictureReader
import com.cardscanner.scryfall.CardDatabase
import com.cardscanner.scryfall.Printing
import com.cardscanner.server.SendPolicy
import com.cardscanner.server.ScannerServer
import com.cardscanner.server.ServerOutcome
import com.cardscanner.server.ServerRefused
import com.cardscanner.scryfall.Scryfall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import org.opencv.android.Utils
import org.opencv.core.Mat

/** Disk-backed capture workflow: bounded processing, retries and standalone/server settlement. */
internal class CaptureProcessor(
    context: Context,
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
    private val scryfall: Scryfall,
    private val collection: CollectionRepository,
    private val history: ScanHistory,
    private val settings: () -> AppSettings,
    private val onMessage: (String) -> Unit,
    private val onSaveFailure: () -> Unit,
    /** The server answered a capture with CARD_BACK: a face-down card was dropped, the auto-capture stops (ScannerViewModel) */
    private val onCardBack: () -> Unit = {},
    /** Standalone: the picture index and its reader (reading by the picture first); null: never read by the picture */
    private val pictureIndex: PictureIndexRepository? = null,
    /** The offline card data: a card the picture decides is taken from it by its id */
    private val cardData: CardDatabase? = null,
) {
    private val outbox = Outbox(context)
    private val aiSlots = Semaphore(1)
    private val serverSlots = Semaphore(3)
    private val pendingItems = java.util.concurrent.ConcurrentHashMap<Long, Outbox.Item>()
    private val activeCaptures = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    // Odd answers of the server per capture (SendPolicy), and the captures set aside because of them
    private val oddAnswers = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val parked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val _serverUndo = MutableStateFlow<Long?>(null)
    val serverUndo = _serverUndo.asStateFlow()
    private val serverUndoMutex = Mutex()
    private val removalSlots = Semaphore(3)
    private val _waiting = MutableStateFlow(0)
    val waiting = _waiting.asStateFlow()
    private fun refreshWaiting() = synchronized(outbox) { _waiting.value = outbox.count() }

    init {
        scope.launch(Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(10_000)
                for (scan in history.scans.value) {
                    // (a row the server cannot follow is not asked about again: the answer stays the same)
                    if (scan.deleting || scan.serverCannotFollow || scan.server?.syncKey == null || scan.server.capture == null) continue
                    try {
                        val state = serverFor(scan).syncCapture(scan.server.capture, syncKey = scan.server.syncKey)
                        if (state == "resolved" || state == "removed") history.dismiss(scan.id)
                        else if (state == "scanned" && scan.server.status == ServerOutcome.Status.REVIEW) {
                            history.update { list -> list.map {
                                if (it.id == scan.id) it.copy(server = it.server?.copy(status = ServerOutcome.Status.ADDED)) else it
                            } }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Keep the row until the server confirms it left staging. */ }
                }
            }
        }
    }

    private fun serverFor(scan: ScanResult): ScannerServer {
        val current = settings()
        if (scan.serverUrl != ScannerServer.normalize(current.serverUrl) || scan.stationId != current.stationId)
            throw ServerRefused("Restore this scan's server and station in Settings to remove it")
        return ScannerServer(current.serverUrl, current.stationId, current.stationName.ifBlank { current.stationId },
            current.stationToken, http)
    }

    /** Remove the same capture from the server before dismissing its phone row. */
    fun dismissScan(id: Long) {
        val scan = history.scans.value.firstOrNull { it.id == id } ?: return
        if (!scan.serverMode && scan.server == null) { history.dismiss(id); return }
        if (scan.deleting) return
        // The server already said it cannot follow this capture, and the user was told: this
        // second Remove is the choice to take it off the phone's list only
        if (scan.serverCannotFollow) { history.dismiss(id); _serverUndo.compareAndSet(id, null); return }
        // A card back: the server kept no copy of it (its sync route refuses it) - the row is only the phone's
        if (scan.server?.status == ServerOutcome.Status.CARD_BACK) { history.dismiss(id); return }
        val capture = scan.server?.capture
        if (capture == null) {
            val item = pendingItems[id]
            if (item == null) { onMessage("The capture is being saved; try removing it again shortly"); return }
            scope.launch(Dispatchers.IO) {
                try {
                    val queued = synchronized(outbox) {
                        if (!item.metaFile.isFile) false else {
                            item.requestDeletion()
                            history.update { list -> list.map { if (it.id == id) it.copy(deleting = true) else it } }
                            true
                        }
                    }
                    if (!queued) dismissScan(id)
                } catch (e: Exception) { onMessage("Could not queue removal: ${e.message}") }
            }
            return
        }
        history.update { list -> list.map { if (it.id == id) it.copy(deleting = true) else it } }
        scope.launch(Dispatchers.IO) {
            try {
                when (removalSlots.withPermit { serverFor(scan).syncCapture(capture, remove = true, syncKey = scan.server?.syncKey) }) {
                    "removed", "resolved" -> { history.dismiss(id); _serverUndo.compareAndSet(id, null) }
                    else -> onMessage("The server is still processing this capture; try removing it again shortly")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: ServerRefused) {
                if (e.cannotFollow) {
                    // Not a failure that another try can mend: nothing on the server is removed,
                    // and the row would stay for ever. It is marked, and the next Remove takes
                    // it off the phone - a second tap, so that a card left on the server is
                    // never the result of a single one.
                    history.update { list -> list.map { if (it.id == id) it.copy(serverCannotFollow = true) else it } }
                    onMessage("The server cannot follow this capture: ${e.message}. " +
                        "Remove again takes it off the phone only - check the server for its card.")
                } else onMessage("Could not remove scan: ${e.message}. It is still on the phone.")
            }
            catch (e: Exception) { onMessage("Could not remove scan: ${e.message}. It is still on the phone.") }
            finally { history.update { list -> list.map { if (it.id == id) it.copy(deleting = false) else it } } }
        }
    }

    fun clearScans() {
        // Snapshot the list: captures arriving after the tap are never removed.
        history.scans.value.forEach { dismissScan(it.id) }
    }

    fun resume() {
        // Captures that were still waiting when the app was closed
        scope.launch(Dispatchers.IO) {
            val items = outbox.all()
            refreshWaiting()
            if (items.isEmpty()) return@launch
            Log.i(TAG, "${items.size} capture(s) waiting from before")
            for (item in items) {
                synchronized(outbox) {
                    // A new capture can be saved while startup is reading the outbox.
                    if (!item.metaFile.isFile || item.captureId in activeCaptures) return@synchronized
                    val photo = item.thumbnail()
                    val id = showScan(photo ?: emptyBitmap)
                    if (photo != null && photo !== emptyBitmap && history.scans.value.none { it.thumbnail === photo }) photo.recycle()
                    process(id, item)
                }
            }
        }
    }

    private fun identifier() = settings().let {
        CardIdentifier(it.provider, it.model(), it.apiKey(), it.localUrl, http)
    }

    fun warmUp() { if (settings().askAi) scope.launch(Dispatchers.IO) { identifier().warmUp() } }

    /** What the picture said of a capture: the printing to add (accepted by the guards), or only its best guess (for review), and why not; the marker the
     * foil network read at the fixed place (Foil.UNKNOWN: not asked, or not sure) */
    private class ByPicture(val printing: Printing?, val guess: Printing?, val why: String?, val marker: Foil = Foil.UNKNOWN,
                            /** CaptureProcessor.PICTURE_READER, or STRIP_READER when the collector line the OCR read named the printing ("strong") */
                            val reader: String = PICTURE_READER)

    // The OCR reader (assets/ocr: light-ocr's networks, reading/TextReader.kt), loaded at the first card that reads its collector line - null when it
    // cannot be read (the picture reads as without OCR). ~30 MB of networks: loaded only when the setting asks for it
    private val textReader by lazy { com.cardscanner.reading.TextReader.load(appContext) }

    /**
     * What the card data has at the set and number the strip's OCR read (PrintingCheck._strip_printing: database.get_card_by_set_number with its number
     * variants - "0330" may be "330", "0330", "330s", "0330s")
     */
    private fun stripPrinting(data: CardDatabase, set: String, number: String): PictureReader.StripPrinting = try {
        val digits = Regex("\\d+").find(number)?.value
        if (digits == null) PictureReader.StripPrinting.NotFound
        else {
            val base = digits.trimStart('0').ifEmpty { "0" }
            data.bySetNumber(set, listOf(base, digits, base + "s", digits + "s").distinct())?.let { PictureReader.StripPrinting.Found(it.id) }
                ?: PictureReader.StripPrinting.NotFound
        }
    } catch (e: Exception) {
        PictureReader.StripPrinting.Failed
    }

    // The foil network (assets/foil_net.onnx), loaded at the first card the picture decides - null when it cannot be read (the finish is then decided as before)
    private val appContext = context.applicationContext
    private val foilNet by lazy { com.cardscanner.reading.FoilNet.load(appContext) }

    /**
     * Standalone, Read by the picture first: the flat card's picture against the index (reading/PictureReader.kt - the server's reading with OCR off). Null
     * when it cannot speak at all (no index, no flat card, a failure: the card is read as before - never lost to this). The printing it accepts is taken from
     * the card data BY ITS ID (tag "picture", one of Scryfall.CONFIRMED), never looked up again by name / number / set.
     */
    private fun readByPicture(foilCard: Bitmap?): ByPicture? {
        val reader = pictureIndex?.reader() ?: return null
        val data = cardData ?: return null
        if (foilCard == null) return null                     // no outline (a fixed area): the picture is not a card - the index is of whole cards
        val mat = Mat()
        return try {
            Utils.bitmapToMat(foilCard, mat)                  // RGBA: Fingerprint.hash drops the alpha
            // the collector line read by OCR (Settings: Read the collector line) - the strip of the card: rule A2's "strong" decision, the star / dot for the finish
            val ocr = if (settings().readCollectorLine) textReader else null
            val reading = reader.read(mat, acceptImageAlone = true, indexCovers = pictureIndex.covers(), inData = { data.byId(it) != null }, foilNet = foilNet,
                textReader = ocr, stripPrinting = { set, number -> stripPrinting(data, set, number) })
            val accepted = reading.printingId?.let { data.byId(it) }?.copy(match = "picture")
            val guess = reading.search.candidates.firstOrNull()?.let { data.byId(it.id) }
            val why = when {
                accepted != null -> null
                reading.acceptance.refused != null -> reading.acceptance.refused!!.replace('_', ' ')
                else -> "the picture looks like several printings"
            }
            Log.i(TAG, "Picture: ${reading.decision.path} ${accepted?.let { "${it.name} ${it.setCode} #${it.collectorNumber}" } ?: "-"} " +
                "(similarity ${reading.decision.similarity?.let { "%.3f".format(it) }}, lead ${reading.decision.lead?.let { "%.3f".format(it) }}" +
                (why?.let { ", $it" } ?: "") + ")")
            reading.strip?.let { Log.i(TAG, "Collector line: ${it.set} ${it.number}, symbol ${it.symbol}${it.net?.let { n -> ", network %.3f".format(n) } ?: ""}") }
            // the marker as the OCR read it (symbol) or the network (at the OCR's box, or at the fixed place without OCR) - only when the finish came from one of
            // them (the printing's own finishes, when they decide, are FinishPolicy's)
            val marker = when {
                reading.finish?.source != "network" && reading.finish?.source != "symbol" -> Foil.UNKNOWN
                reading.finish.finish == "foil" -> Foil.FOIL
                reading.finish.finish == "regular" -> Foil.NON_FOIL
                else -> Foil.UNKNOWN
            }
            if (reading.net != null) Log.i(TAG, "Foil network: %.3f -> ${reading.finish?.finish}".format(reading.net))
            ByPicture(accepted, guess, why, marker, if (reading.decision.path == "strong") STRIP_READER else PICTURE_READER)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {          // anything - an Error too (out of memory, a native library): the card is read as before, never lost to this
            Log.w(TAG, "Reading by the picture failed (${e.message}) - read as before", e)
            null
        } finally {
            mat.release()
        }
    }

    /**
     * A capture: shown in the list, saved in the outbox and processed (see process). reviewOnly
     * (a photo import): standalone mode queues the card for review instead of adding it; client
     * mode follows the server's rules. True when the capture is durably saved in the outbox.
     */
    fun enqueue(card: Bitmap, foilCard: Bitmap?, frame: CapturedFrame? = null, reviewOnly: Boolean = false): Boolean {
        val id = showScan(card)
        var saved = false  // the disk item exists: a failure after that is not a failed save
        try {
            // This runs on the capture executor. Save before launching any queued work,
            // so pending coroutines retain only a disk item, never full-resolution photos.
            synchronized(outbox) {
                val item = outbox.add(card, foilCard, frame, reviewOnly)
                saved = true
                refreshWaiting()
                Log.i(TAG, "Capture #$id saved: ${card.width}x${card.height}")
                process(id, item)
            }
            return true
        } catch (e: Exception) {
            if (saved) {
                Log.e(TAG, "Capture #$id saved but could not be started", e)
                note(id, "Saved - it will be tried again at the next start: ${e.message}")
                return true
            }
            Log.e(TAG, "Could not save capture #$id", e)
            fail(id, "Capture could not be saved: ${e.message}")
            onSaveFailure()
            return false
        } finally {
            if (foilCard != null && foilCard !== card) foilCard.recycle()
            if (history.scans.value.none { it.thumbnail === card }) card.recycle()
        }
    }

    private fun showScan(card: Bitmap) = history.show(card, settings().serverMode)

    /** Retry disk-backed captures. Permits bound decoded photos, including during server polls. */
    private fun process(id: Long, item: Outbox.Item) {
        if (!activeCaptures.add(item.captureId)) return
        pendingItems[id] = item
        if (item.deleteRequested) history.update { list -> list.map { if (it.id == id) it.copy(deleting = true) else it } }
        scope.launch(Dispatchers.IO) {
            val start = System.nanoTime()
            var wait = 2_000L
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val serverMode = settings().serverMode
                    val slots = if (serverMode) serverSlots else aiSlots
                    val settled = slots.withPermit {
                        // Check before choosing a mode: a crash after a local commit must not
                        // send that same capture to a server after settings have changed.
                        if (settings().serverMode != serverMode) {
                            false // Settings changed while this capture was waiting for a slot.
                        } else if (item.deleteRequested && !serverMode) {
                            note(id, "Switch back to server mode to finish removing this capture")
                            false
                        } else if (collection.hasSettled(item.captureId)) {
                            history.update { list -> list.filterNot { it.id == id } }
                            true
                        } else {
                            attempt(id, item, serverMode, start)
                        }
                    }
                    if (settled) {
                        val finished = synchronized(outbox) {
                            // A deletion may arrive after the server answered but before this cleanup.
                            if (item.deleteRequested && history.scans.value.any { it.id == id && it.server != null }) false
                            else { outbox.remove(item); refreshWaiting(); true }
                        }
                        if (finished) break
                    }
                    // Set aside by sendOnce (SendPolicy): it stays in the outbox, the next start tries it again
                    if (item.captureId in parked) break
                    kotlinx.coroutines.delay(wait)
                    wait = minOf(wait * 2, 30_000L)
                }
            } finally {
                activeCaptures.remove(item.captureId)
                pendingItems.remove(id)
                // (a parked capture is tried again by the next start: its marks go with this run)
                parked.remove(item.captureId)
                oddAnswers.remove(item.captureId)
            }
        }
    }

    private suspend fun attempt(id: Long, item: Outbox.Item, serverMode: Boolean, start: Long): Boolean {
        val photo = item.card()
        if (photo == null) {
            fail(id, "The saved capture is unreadable; capture this card again")
            // Preserve the disk item for recovery rather than silently deleting its photo.
            return false
        }
        var foilPhoto: Bitmap? = null
        try {
            foilPhoto = item.foil(photo)
            return if (serverMode) sendOnce(id, photo, foilPhoto, item, start)
                else identifyOnce(id, photo, foilPhoto, item.captureId, start, item.reviewOnly)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Capture #$id processing failed; keeping it for retry", e)
            note(id, "Processing failed - capture saved for retry: ${e.message}")
            return false
        } finally {
            if (foilPhoto != null && foilPhoto !== photo) foilPhoto.recycle()
            photo.recycle()
        }
    }

    /**
     * Standalone: identify a capture and add it, queue it for review or leave it for Add.
     * False when there is no network; true only after a durable inventory/review write.
     */
    private suspend fun identifyOnce(id: Long, card: Bitmap, foilCard: Bitmap?, captureId: String, start: Long,
                                     reviewOnly: Boolean = false): Boolean {
        val thumbnail = history.scans.value.firstOrNull { it.id == id }?.thumbnail ?: emptyBitmap
        lateinit var reading: CardReading
        var marker = Foil.UNKNOWN
        val settings = settings()
        // The picture first (Read by the picture first): what it settles is added without the AI - the server's order, picture then the rest
        val picture = if (settings.pictureFirst) readByPicture(foilCard) else null
        picture?.printing?.let { printing ->
            val read = CardReading(printing.name, printing.collectorNumber, printing.setCode)
            // The finish: the printing's own when it exists in one only; else the ★ / • marker the foil network read at the fixed place (picture.marker - a
            // recent, modern-frame card the search turned upright); else the AI's foil check (when the AI and the foil check are on); else unknown
            val both = ("foil" in printing.finishes || "etched" in printing.finishes) && "nonfoil" in printing.finishes
            val pictureMarker = when {
                picture.marker != Foil.UNKNOWN -> picture.marker
                both && settings.askAi && settings.detectFoil && foilCard != null ->
                    try { identifier().readFoilSymbol(foilCard) } catch (e: CancellationException) { throw e } catch (e: Exception) { Foil.UNKNOWN }
                else -> Foil.UNKNOWN
            }
            val (foil, reason) = FinishPolicy.suggest(printing, pictureMarker)
            val result = ScanResult(id, thumbnail, ScanResult.Status.DONE, read, printing, foil, reason,
                seconds = (System.nanoTime() - start) / 1e9, reader = picture.reader)
            Log.i(TAG, "Identified #$id by the picture: ${printing.name} ${printing.setCode} #${printing.collectorNumber} foil=$foil in %.2f s".format(result.seconds))
            collection.settleCapture(id, card, read, pictureMarker, result, captureId, settings, reviewOnly)
            return true
        }
        if (!settings.askAi) {
            // The vision AI is switched off: what the picture did not settle waits for review, with the picture's best guess as the suggestion
            val why = when {
                picture == null && settings.pictureFirst -> "Not read: the AI is off and there is no picture index to read by"
                picture == null -> "Not read: the AI and the reading by the picture are off"
                else -> "The picture is not sure (${picture.why}) and the AI is off"
            }
            val guess = picture?.guess
            fail(id, why)
            collection.queueForReview(id, card, guess?.let { CardReading(it.name, it.collectorNumber, it.setCode) }, Foil.UNKNOWN, guess, why, captureId)
            return true
        }
        val result = try {
            val identifier = identifier()
            val (answer, read) = identifier.identify(card)
            if (read == null) {
                val error = "The AI could not read the card" + (if (answer.isNotBlank()) ": \"${answer.take(120)}\"" else "")
                fail(id, error)
                collection.queueForReview(id, card, null, Foil.UNKNOWN, null, "The AI could not read the card", captureId)
                return true
            }
            reading = read
            // The ★/• corner is only at a known place on the flat, tightly cropped card
            marker = if (settings.detectFoil && foilCard != null) identifier.readFoilSymbol(foilCard) else Foil.UNKNOWN
            val printing = scryfall.find(reading.name, reading.collectorNumber, reading.setCode)
            val (foil, reason) = FinishPolicy.suggest(printing, marker)
            ScanResult(id, thumbnail, ScanResult.Status.DONE, reading, printing, foil, reason,
                seconds = (System.nanoTime() - start) / 1e9, usage = identifier.usage, reader = settings.model(),
                error = if (printing == null) "Not found on Scryfall" else null)
        } catch (e: CancellationException) {
            throw e
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
            collection.queueForReview(id, card, null, marker, null, "Identification failed: ${e.message?.take(80)}", captureId)
            return true
        }
        Log.i(TAG, "Identified #$id: ${reading.name} #${reading.collectorNumber} [${reading.setCode}] -> " +
            (result.printing?.let { "${it.name} ${it.setCode} #${it.collectorNumber} (${it.match})" } ?: "not found") +
            " foil=${result.foil} in %.1f s".format(result.seconds) +
            " - ${settings.provider.id}/${settings.model()} tokens ${result.usage}")
        collection.settleCapture(id, card, reading, marker, result, captureId, settings, reviewOnly)
        return true
    }

    /** No connection to whatever was asked (as opposed to an answer that is an error) */
    private fun isOffline(e: Exception) = e is java.net.UnknownHostException || e is java.net.SocketException ||
        e is java.net.SocketTimeoutException || e is javax.net.ssl.SSLException

    // ------------------------------------------------------------------------
    // Client mode: the scanner server does the rest
    // ------------------------------------------------------------------------

    private fun server() = settings().let {
        ScannerServer(it.serverUrl, it.stationId, it.stationName.ifBlank { it.stationId }, it.stationToken, http)
    }

    /** This phone's page on the server (its cards and its review queue), or null without an address */
    val serverPageUrl get() = server().takeIf { it.baseUrl.isNotEmpty() }?.pageUrl

    /** Settings: is the server there? */
    suspend fun testServer(): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try { server().test() } catch (e: Exception) { "Not reachable: ${e.message}" }
    }

    /**
     * Client mode: send a capture to the server and show what became of it. False when the
     * server can't be reached (try again - under the same capture id it is one card however often
     * it is sent). Pending/error/refused captures are kept for retry as well.
     */
    private suspend fun sendOnce(id: Long, card: Bitmap, foilCard: Bitmap?, item: Outbox.Item, start: Long): Boolean {
        try {
            val server = server()
            ScannerServer.validatedUrl(server.baseUrl)
            item.bindServer(server.baseUrl, server.stationId)
            var outcome = server.sendCapture(card, foilCard, item.captureId, frame = item.frame())
            // Still being read after the wait (a long AI queue): ask until it is settled
            var asked = 0
            while (outcome.status == ServerOutcome.Status.PENDING && outcome.capture != null && asked++ < 90) {
                note(id, "The server is reading it…")
                kotlinx.coroutines.delay(2_000)
                outcome = server.outcome(outcome.capture)
            }
            if (outcome.status == ServerOutcome.Status.PENDING || outcome.status == ServerOutcome.Status.ERROR) {
                note(id, outcome.message ?: "The server is still reading it - capture saved for retry")
                return false
            }
            if (item.deleteRequested) {
                val capture = outcome.capture ?: throw ServerRefused("Server did not return a capture number")
                try {
                    when (server.syncCapture(capture, remove = true, syncKey = outcome.syncKey)) {
                        "removed", "resolved" -> history.dismiss(id)
                        else -> return false
                    }
                } catch (e: ServerRefused) {
                    if (!e.cannotFollow) throw e
                    // Removed on the phone before it was sent, and the server cannot take back
                    // what it made of it: the capture is settled there, so it leaves the outbox
                    // (sending it again would change nothing) and its row shows what happened,
                    // for the user to decide (dismissScan)
                    serverAnswered(id, outcome, (System.nanoTime() - start) / 1e9, server)
                    history.update { list -> list.map {
                        if (it.id == id) it.copy(serverCannotFollow = true, deleting = false) else it
                    } }
                    onMessage("The server could not remove this capture: ${e.message}. " +
                        "Remove again takes it off the phone only - check the server for its card.")
                }
            } else serverAnswered(id, outcome, (System.nanoTime() - start) / 1e9, server)
        } catch (e: ServerRefused) {
            Log.e(TAG, "The server refused capture #$id: ${e.message}")
            if (setAside(id, item, e)) return false
            note(id, "Server: ${e.message} - capture saved; update Settings to retry")
            return false
        } catch (e: java.io.IOException) {
            if (SendPolicy.oddAnswer(e)) {
                Log.w(TAG, "Capture #$id: the server answered oddly (${e.message})")
                if (setAside(id, item, e)) return false
                note(id, "Server: ${e.message?.take(80)} - capture saved, trying again")
                return false
            }
            Log.w(TAG, "Capture #$id not sent (${e.message}) - waiting for the server")
            note(id, "Server not reachable - waiting to send…")
            return false
        }
        return true
    }

    /**
     * Whether this capture stops being tried now (SendPolicy): the server will never take it, or has
     * answered oddly [SendPolicy.PARK_AFTER_ANSWERS] times. Its photo and note stay in the outbox; the
     * next start of the app tries it again, and the other captures are not held up.
     */
    private fun setAside(id: Long, item: Outbox.Item, e: Exception): Boolean {
        val forGood = SendPolicy.refusedForGood(e)
        val times = if (SendPolicy.oddAnswer(e)) oddAnswers.merge(item.captureId, 1, Int::plus) ?: 1 else 0
        if (!forGood && times < SendPolicy.PARK_AFTER_ANSWERS) return false
        parked.add(item.captureId)
        fail(id, if (forGood) "The server will not take this capture (${e.message}) - kept on the phone, tried again when the app starts"
            else "The server kept answering oddly (${e.message?.take(80)}) - kept on the phone, tried again when the app starts")
        Log.e(TAG, "Capture #$id set aside: ${e.message}")
        return true
    }

    private fun note(id: Long, text: String) = history.note(id, text)

    private fun serverAnswered(id: Long, outcome: ServerOutcome, seconds: Double, server: ScannerServer) {
        Log.i(TAG, "Server #$id: ${outcome.status} ${outcome.card?.name ?: outcome.reason ?: outcome.message.orEmpty()}" +
            " (read by ${outcome.reader}) in %.1f s".format(seconds))
        val reading = CardReading(outcome.readName, outcome.readNumber, outcome.readSet)
        when (outcome.status) {
            ServerOutcome.Status.ADDED, ServerOutcome.Status.REVIEW -> {
                history.update { list -> list.map {
                    if (it.id == id) it.copy(status = ScanResult.Status.DONE, server = outcome, serverUrl = server.baseUrl, stationId = server.stationId, reading = reading, seconds = seconds, note = null) else it
                } }
                if (outcome.status == ServerOutcome.Status.ADDED) _serverUndo.value = id
                val soundSettings = settings()
                if (outcome.status == ServerOutcome.Status.ADDED) {
                    if (soundSettings.addedSound) Sounds.added()
                } else if (soundSettings.reviewSound) Sounds.review()
            }
            ServerOutcome.Status.CARD_BACK -> {
                // Nothing was added: the row says so, the review sound is the signal, and the auto-capture stops like the server's camera pages do
                history.update { list -> list.map {
                    if (it.id == id) it.copy(status = ScanResult.Status.DONE, server = outcome, serverUrl = server.baseUrl, stationId = server.stationId, seconds = seconds, note = null) else it
                } }
                if (settings().reviewSound) Sounds.review()
                onCardBack()
            }
            ServerOutcome.Status.PENDING -> fail(id, "The server is still reading it - see its page")
            ServerOutcome.Status.ERROR -> fail(id, "Server: ${outcome.message ?: "error"}")
        }
    }

    /** Take back the last card the server added for this phone (the server keeps one Undo per station) */
    fun undoOnServer() {
        scope.launch(Dispatchers.IO) {
            serverUndoMutex.withLock {
                val id = _serverUndo.value ?: return@withLock
                try {
                    val name = server().undo()
                    if (name == null) onMessage("Nothing to undo on the server")
                    else history.update { list -> list.map { if (it.id == id) it.copy(undone = true) else it } }
                    _serverUndo.compareAndSet(id, null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onMessage("Undo failed: ${e.message}")
                }
            }
        }
    }

    private fun fail(id: Long, error: String) = history.fail(id, error)

    companion object {
        private val emptyBitmap: Bitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }
        private const val TAG = "Scanner"
        /** The reader of a card the picture decided (ScanResult.reader; the server's PICTURE_READER, "picture") */
        const val PICTURE_READER = "picture"
        /** ... and when the collector line the OCR read named the printing the picture agrees with (the server's EVIDENCE_READER, "picture+strip") */
        const val STRIP_READER = "picture+strip"
    }
}
