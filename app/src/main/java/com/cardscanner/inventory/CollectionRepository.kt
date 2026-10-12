package com.cardscanner.inventory

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import com.cardscanner.AppSettings
import com.cardscanner.ScanHistory
import com.cardscanner.ScanResult
import com.cardscanner.Sounds
import com.cardscanner.ai.CardIdentifier
import com.cardscanner.ai.CardReading
import com.cardscanner.ai.Foil
import com.cardscanner.scryfall.Printing
import com.cardscanner.scryfall.Scryfall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/** Owns local inventory/review persistence, idempotent handoffs, import/export and their state. */
internal class CollectionRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val scryfall: Scryfall,
    private val http: OkHttpClient,
    private val history: ScanHistory,
    private val settings: () -> AppSettings,
    private val onMessage: (String) -> Unit,
) {
    private val inventory = Inventory(context)
    private val reviewQueue = ReviewQueue(context)
    private val inventoryMutex = Mutex()
    private val _review = MutableStateFlow<List<ReviewItem>>(emptyList())
    val review = _review.asStateFlow()
    private val _inventoryEntries = MutableStateFlow<List<InventoryEntry>>(emptyList())
    val inventoryEntries = _inventoryEntries.asStateFlow()
    private val _share = MutableStateFlow<Intent?>(null)
    val share = _share.asStateFlow()

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            inventoryMutex.withLock {
                // Finish an interrupted review -> inventory handoff without adding again.
                if (!settings().serverMode) {
                    reviewQueue.all().filter { inventory.receipt("review:${it.id}") != null }
                        .forEach { reviewQueue.remove(it.id) }
                }
                _inventoryEntries.value = inventory.all()
                _review.value = reviewQueue.all()
            }
        }
    }

    /** Re-read scan/review state under this lock; a second tap observes the first result. */
    private fun mutateInventory(block: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            inventoryMutex.withLock {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Inventory operation failed", e)
                    onMessage("Could not update inventory: ${e.message}")
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // Review queue
    // ------------------------------------------------------------------------

    /** Queue an uncertain card with its photo; scanning goes on */
    suspend fun queueForReview(scanId: Long, photo: Bitmap, reading: CardReading?, marker: Foil,
                               printing: Printing?, reason: String, captureId: String, pendingAdd: Boolean = false) {
        inventoryMutex.withLock {
            queueForReviewLocked(scanId, photo, reading, marker, printing, reason, captureId, pendingAdd)
        }
    }

    private fun queueForReviewLocked(scanId: Long, photo: Bitmap, reading: CardReading?, marker: Foil, printing: Printing?, reason: String,
                               captureId: String, pendingAdd: Boolean = false) {
        val reviewId = reviewQueue.add(photo, reading?.name.orEmpty(), reading?.collectorNumber.orEmpty(),
            reading?.setCode.orEmpty(), marker, printing?.id, reason, captureId)
        history.update { list -> list.map { if (it.id == scanId) it.copy(reviewId = reviewId, pendingAdd = pendingAdd) else it } }
        _review.value = reviewQueue.all()
        if (settings().reviewSound) Sounds.review()
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
        FinishPolicy.finish(printing, FinishPolicy.suggest(printing, marker).first)

    /** Add a reviewed card and resolve the item */
    fun addFromReview(item: ReviewItem, printing: Printing, finish: Finish) {
        mutateInventory {
            resolveReview(item.id, printing, finish)
        }
    }

    private fun resolveReview(reviewId: Long, printing: Printing, finish: Finish) {
        if (!reviewQueue.contains(reviewId)) return
        val entryId = inventory.add(printing, finish, operationId = "review:$reviewId")
        reviewQueue.remove(reviewId)
        history.update { list -> list.map {
            if (it.reviewId == reviewId) it.copy(reviewId = null, pendingAdd = false, inventoryId = entryId,
                printing = printing, foil = finish != Finish.REGULAR) else it
        } }
        _inventoryEntries.value = inventory.all()
        _review.value = reviewQueue.all()
        if (settings().addedSound) Sounds.added()
    }

    /** Drop a review item (the card is not added) */
    fun skipReview(item: ReviewItem) {
        mutateInventory {
            if (!reviewQueue.contains(item.id)) return@mutateInventory
            reviewQueue.remove(item.id)
            history.update { list -> list.filterNot { it.reviewId == item.id } }
            _review.value = reviewQueue.all()
        }
    }

    /** Add a scanned card to the inventory (unconfirmed cards: after the user checked it) */
    fun addScan(scanId: Long) {
        mutateInventory {
            val scan = history.scans.value.firstOrNull { it.id == scanId } ?: return@mutateInventory
            val printing = scan.printing ?: return@mutateInventory
            if (scan.inventoryId != null) return@mutateInventory
            scan.reviewId?.let {
                resolveReview(it, printing, scan.finish)
                return@mutateInventory
            }
            val entryId = inventory.add(printing, scan.finish)
            if (settings().addedSound) Sounds.added()
            history.update { list -> list.map { if (it.id == scanId) it.copy(inventoryId = entryId) else it } }
            _inventoryEntries.value = inventory.all()
        }
    }

    /** Take a scanned card back out of the inventory */
    fun undoScan(scanId: Long) {
        mutateInventory {
            val entryId = history.scans.value.firstOrNull { it.id == scanId }?.inventoryId ?: return@mutateInventory
            inventory.remove(entryId)
            history.update { list -> list.map { if (it.id == scanId) it.copy(inventoryId = null) else it } }
            _inventoryEntries.value = inventory.all()
        }
    }

    fun updateEntry(id: Long, quantity: Int, condition: String, finish: Finish) {
        mutateInventory {
            inventory.update(id, quantity, condition, finish)
            _inventoryEntries.value = inventory.all()
        }
    }

    fun deleteEntry(id: Long) {
        mutateInventory {
            inventory.delete(id)
            history.update { list -> list.map { if (it.inventoryId == id) it.copy(inventoryId = null) else it } }
            _inventoryEntries.value = inventory.all()
        }
    }

    /** Correct the printing of an inventory entry (search in the inventory's edit dialog) */
    fun changePrinting(id: Long, printing: Printing) {
        mutateInventory {
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
        scope.launch(Dispatchers.IO) {
            try {
                _importStatus.value = "Reading the file…"
                val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
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
        mutateInventory {
            inventory.clear()
            history.update { list -> list.map { it.copy(inventoryId = null) } }
            _inventoryEntries.value = inventory.all()
        }
    }

    /**
     * Write the inventory in an export format to the app's cache and hand it to the share sheet
     * (save to Files / Drive, e-mail, ...)
     */
    fun export(format: ExportFormat) {
        scope.launch(Dispatchers.IO) {
            val app = context
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

    suspend fun settleCapture(id: Long, card: Bitmap, reading: CardReading, marker: Foil,
                              result: ScanResult, captureId: String, settings: AppSettings) {
        inventoryMutex.withLock {
            val added = if (settings.autoAdd && result.printing?.confirmed == true)
                inventory.add(result.printing, result.finish, operationId = "capture:$captureId") else null
            history.update { list -> list.map { if (it.id == id) result.copy(inventoryId = added) else it } }
            if (added != null) {
                _inventoryEntries.value = inventory.all()
                if (settings.addedSound) Sounds.added()
            } else {
                val why = if (!settings.autoAdd && result.printing != null) "Waiting for Add"
                    else result.printing?.let { "Check: matched by ${it.match.replace('_', ' ')}" } ?: "Not found on Scryfall"
                queueForReviewLocked(id, card, reading, marker, result.printing, why, captureId,
                    pendingAdd = !settings.autoAdd && result.printing != null)
            }
        }
    }

    fun hasSettled(captureId: String): Boolean =
        inventory.receipt("capture:$captureId") != null || reviewQueue.receipt(captureId) != null

    private fun identifier() = settings().let {
        CardIdentifier(it.provider, it.model(), it.apiKey(), it.localUrl, http)
    }

    fun close() { inventory.close(); reviewQueue.close() }

    companion object { private const val TAG = "Scanner" }
}
