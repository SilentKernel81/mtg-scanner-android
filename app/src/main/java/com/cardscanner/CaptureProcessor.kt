package com.cardscanner

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.cardscanner.ai.CardIdentifier
import com.cardscanner.ai.CardReading
import com.cardscanner.ai.Foil
import com.cardscanner.inventory.CollectionRepository
import com.cardscanner.inventory.FinishPolicy
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
) {
    private val outbox = Outbox(context)
    private val aiSlots = Semaphore(1)
    private val serverSlots = Semaphore(3)
    private val activeCaptures = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val _serverUndo = MutableStateFlow<Long?>(null)
    val serverUndo = _serverUndo.asStateFlow()
    private val serverUndoMutex = Mutex()
    private val _waiting = MutableStateFlow(0)
    val waiting = _waiting.asStateFlow()
    private fun refreshWaiting() = synchronized(outbox) { _waiting.value = outbox.count() }

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

    fun warmUp() { scope.launch(Dispatchers.IO) { identifier().warmUp() } }

    /** A capture: shown in the list, saved in the outbox and processed (see process) */
    fun enqueue(card: Bitmap, foilCard: Bitmap?) {
        val id = showScan(card)
        try {
            // This runs on the capture executor. Save before launching any queued work,
            // so pending coroutines retain only a disk item, never full-resolution photos.
            synchronized(outbox) {
                val item = outbox.add(card, foilCard)
                refreshWaiting()
                Log.i(TAG, "Capture #$id saved: ${card.width}x${card.height}")
                process(id, item)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not save capture #$id", e)
            fail(id, "Capture could not be saved: ${e.message}")
            onSaveFailure()
        } finally {
            if (foilCard != null && foilCard !== card) foilCard.recycle()
            if (history.scans.value.none { it.thumbnail === card }) card.recycle()
        }
    }

    private fun showScan(card: Bitmap) = history.show(card, settings().serverMode)

    /** Retry disk-backed captures. Permits bound decoded photos, including during server polls. */
    private fun process(id: Long, item: Outbox.Item) {
        if (!activeCaptures.add(item.captureId)) return
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
                        } else if (collection.hasSettled(item.captureId)) {
                            history.update { list -> list.filterNot { it.id == id } }
                            true
                        } else {
                            attempt(id, item, serverMode, start)
                        }
                    }
                    if (settled) {
                        outbox.remove(item)
                        refreshWaiting()
                        break
                    }
                    kotlinx.coroutines.delay(wait)
                    wait = minOf(wait * 2, 30_000L)
                }
            } finally {
                activeCaptures.remove(item.captureId)
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
            return if (serverMode) sendOnce(id, photo, foilPhoto, item.captureId, start)
                else identifyOnce(id, photo, foilPhoto, item.captureId, start)
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
    private suspend fun identifyOnce(id: Long, card: Bitmap, foilCard: Bitmap?, captureId: String, start: Long): Boolean {
        val thumbnail = history.scans.value.firstOrNull { it.id == id }?.thumbnail ?: emptyBitmap
        lateinit var reading: CardReading
        var marker = Foil.UNKNOWN
        val settings = settings()
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
                seconds = (System.nanoTime() - start) / 1e9, usage = identifier.usage,
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
        collection.settleCapture(id, card, reading, marker, result, captureId, settings)
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
            if (outcome.status == ServerOutcome.Status.PENDING || outcome.status == ServerOutcome.Status.ERROR) {
                note(id, outcome.message ?: "The server is still reading it - capture saved for retry")
                return false
            }
            serverAnswered(id, outcome, (System.nanoTime() - start) / 1e9)
        } catch (e: ServerRefused) {
            Log.e(TAG, "The server refused capture #$id: ${e.message}")
            note(id, "Server: ${e.message} - capture saved; update Settings to retry")
            return false
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Capture #$id not sent (${e.message}) - waiting for the server")
            note(id, "Server not reachable - waiting to send…")
            return false
        }
        return true
    }

    private fun note(id: Long, text: String) = history.note(id, text)

    private fun serverAnswered(id: Long, outcome: ServerOutcome, seconds: Double) {
        Log.i(TAG, "Server #$id: ${outcome.status} ${outcome.card?.name ?: outcome.reason ?: outcome.message.orEmpty()}" +
            " (read by ${outcome.reader}) in %.1f s".format(seconds))
        val reading = CardReading(outcome.readName, outcome.readNumber, outcome.readSet)
        when (outcome.status) {
            ServerOutcome.Status.ADDED, ServerOutcome.Status.REVIEW -> {
                history.update { list -> list.map {
                    if (it.id == id) it.copy(status = ScanResult.Status.DONE, server = outcome, reading = reading, seconds = seconds, note = null) else it
                } }
                if (outcome.status == ServerOutcome.Status.ADDED) _serverUndo.value = id
                val soundSettings = settings()
                if (outcome.status == ServerOutcome.Status.ADDED) {
                    if (soundSettings.addedSound) Sounds.added()
                } else if (soundSettings.reviewSound) Sounds.review()
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
    }
}
