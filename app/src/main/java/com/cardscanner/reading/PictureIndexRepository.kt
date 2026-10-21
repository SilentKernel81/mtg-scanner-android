package com.cardscanner.reading

import android.content.Context
import android.util.Log
import com.cardscanner.scryfall.CardDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

/**
 * What Settings shows of the picture index: its size and dates, how many printings of the phone's card data it lacks (while not 0 the picture alone adds
 * nothing), why there is none, and a download's progress.
 */
data class PictureIndexState(
    val count: Int? = null,
    val built: String? = null,
    val generation: String? = null,
    /** Printings of the phone's card data the index lacks: 0 = it covers it; null = cannot be said (no card data, or no index) */
    val missing: Int? = null,
    val why: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

/**
 * The picture index of standalone mode (docs/plans/ANDROID_PICTURE_STANDALONE_PLAN.md on the server): loaded from the app's files at the start, downloaded
 * again from a scanner server on request, deleted on request. [reader] and [covers] are what a capture asks: the reader (null without an index) and whether
 * the index has every printing of the phone's card data - the server's `PrintingCheck.missing_printings` guard: a printing the index lacks can never be the
 * picture's best match, so a card of it would be added as another printing. Counted once per index and card data, then remembered.
 */
internal class PictureIndexRepository(context: Context, private val http: OkHttpClient, private val cardData: CardDatabase, private val scope: CoroutineScope) {
    private val folder = File(context.filesDir, "picture_index")
    private val _state = MutableStateFlow(PictureIndexState())
    val state: StateFlow<PictureIndexState> = _state.asStateFlow()

    @Volatile private var index: ImageIndex? = null
    @Volatile private var pictureReader: PictureReader? = null
    // (index generation, card data date) -> missing: the coverage count is a pass over every printing - not for every card
    @Volatile private var coverage: Pair<Pair<String, String>, Int?>? = null

    /** The reader of the loaded index, or null (no index: the card is read as before) */
    fun reader(): PictureReader? = pictureReader

    /** Whether the index has every printing of the phone's card data (null: cannot be said - no card data or no index: not a yes) */
    fun covers(): Boolean? = missing()?.let { it == 0 }

    /** Printings of the card data the index lacks (see [covers]) */
    fun missing(): Int? {
        val loaded = index ?: return null
        val cardDate = cardData.info()?.second ?: return null
        val key = loaded.generation to cardDate
        coverage?.let { (k, v) -> if (k == key) return v }
        val counted = cardData.countMissing { loaded.row(it) != null }
        coverage = key to counted
        return counted
    }

    private fun load(message: String? = null) {
        val opened = ImageIndex.open(folder)
        index = opened.index
        pictureReader = opened.index?.let { PictureReader(it) }
        coverage = null
        val loaded = opened.index
        _state.value = PictureIndexState(count = loaded?.count, built = loaded?.built, generation = loaded?.generation,
            missing = if (loaded != null) missing() else null, why = opened.why, message = message)
    }

    fun start() { scope.launch(Dispatchers.IO) { load() } }

    /** The card data changed (downloaded, deleted): count the coverage again */
    fun cardDataChanged() { scope.launch(Dispatchers.IO) { coverage = null; _state.update { it.copy(missing = missing()) } } }

    /** Download the index from the scanner server at [serverUrl] (client mode's address and token) - ~22 MB, resumable */
    fun download(serverUrl: String, token: String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = "Asking the server for its picture index…") }
        scope.launch(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            try {
                IndexDownload(http, serverUrl, token, folder).download { what, done, total ->
                    _state.update { it.copy(message = "Downloading the picture index: %d of %d MB (%s)…".format(done shr 20, total shr 20, what)) }
                }
                load("Downloaded in ${(System.currentTimeMillis() - started) / 1000} s")
            } catch (e: Exception) {
                Log.e(TAG, "Picture index download failed", e)
                load("Download failed: ${e.message}")     // whatever was published before is still there, or nothing
            }
        }
    }

    fun delete() {
        scope.launch(Dispatchers.IO) {
            index = null; pictureReader = null
            folder.deleteRecursively()
            load()
        }
    }

    companion object { private const val TAG = "Scanner" }
}
