package com.cardscanner

import android.app.Application
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
import com.cardscanner.ai.Foil
import com.cardscanner.detection.CardTracker
import com.cardscanner.detection.findCardOutline
import com.cardscanner.detection.scaled
import com.cardscanner.detection.warpCard
import com.cardscanner.inventory.CollectionRepository
import com.cardscanner.inventory.ExportFormat
import com.cardscanner.inventory.Finish
import com.cardscanner.inventory.ReviewItem
import com.cardscanner.scryfall.CardDatabase
import com.cardscanner.scryfall.CardDataRepository
import com.cardscanner.scryfall.Printing
import com.cardscanner.scryfall.Scryfall
import com.cardscanner.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Camera ownership and screen-facing coordination; workflows and persistence live in services. */
class ScannerViewModel internal constructor(application: Application, http: OkHttpClient) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, CardIdentifier.httpClient())

    private val settingsRepository = SettingsRepository(application, viewModelScope)
    val settings = settingsRepository.settings
    val settingsError = settingsRepository.error
    private val history = ScanHistory()
    val scans = history.scans
    val latestScan = history.latestScan.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _autoCapture = MutableStateFlow(false)
    val autoCapture = _autoCapture.asStateFlow()
    private val _detection = MutableStateFlow<CardTracker.State?>(null)
    val detection = _detection.asStateFlow()

    private val cardData = CardDatabase(application, http)
    private val scryfall = Scryfall(http, cardData)
    private val cardDataRepository = CardDataRepository(cardData, viewModelScope)
    val cardDataState = cardDataRepository.state
    private val collection = CollectionRepository(application, viewModelScope, scryfall, http, history,
        settings = { settings.value }, onMessage = { _message.value = it })
    private val processor = CaptureProcessor(application, viewModelScope, http, scryfall, collection, history,
        settings = { settings.value }, onMessage = { _message.value = it }, onSaveFailure = {
            _autoCapture.value = false
            _message.value = "Capture could not be saved. Free storage and capture this card again."
        })
    val review = collection.review
    val inventoryEntries = collection.inventoryEntries
    val share = collection.share
    val importStatus = collection.importStatus
    val waiting = processor.waiting
    val serverUndo = processor.serverUndo
    val serverPageUrl get() = processor.serverPageUrl

    private val tracker = CardTracker(settings.value.stableFrames, settings.value.minSharpness.toDouble())
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private val capturing = AtomicBoolean(false)
    @Volatile private var pendingReset = false
    var imageCapture: ImageCapture? = null

    init {
        collection.refresh()
        cardDataRepository.start()
        processor.resume()
    }

    fun updateSettings(newSettings: AppSettings) {
        var settings = newSettings
        if (settings.rotation != this.settings.value.rotation) {
            pendingReset = true  // outlines of the old orientation
            if (settings.fixedAreaEnabled) {
                // An area drawn for the old orientation no longer fits: draw it again
                settings = settings.copy(fixedAreaEnabled = false, fixedArea = null)
                _message.value = "Fixed area off - draw it again"
            }
        }
        settingsRepository.update(settings)
        tracker.requiredStableFrames = settings.stableFrames
        tracker.minSharpness = settings.minSharpness.toDouble()
    }

    /** Turn the camera image a quarter turn clockwise (like the Python scanner's rotate button) */
    fun rotate() = updateSettings(settings.value.let { it.copy(rotation = (it.rotation + 90) % 360) })

    /** Draw a fixed area (fractions of the frame) and switch to it */
    fun setFixedArea(area: List<Double>) =
        updateSettings(settings.value.copy(fixedArea = area.map { it.coerceIn(0.0, 1.0) }, fixedAreaEnabled = true))

    fun setFixedAreaEnabled(enabled: Boolean) = updateSettings(settings.value.copy(fixedAreaEnabled = enabled))

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
            if (!settings.value.serverMode) processor.warmUp()
        }
    }

    fun clearMessage() { _message.value = null }

    fun dismissScan(scanId: Long) = processor.dismissScan(scanId)
    fun clearScans() = processor.clearScans()

    fun checkCardDataUpdate() = cardDataRepository.checkCardDataUpdate()
    fun downloadCardData() = cardDataRepository.downloadCardData()
    fun deleteCardData() = cardDataRepository.deleteCardData()
    suspend fun suggestion(item: ReviewItem) = collection.suggestion(item)
    suspend fun identifyAgain(item: ReviewItem) = collection.identifyAgain(item)
    suspend fun searchPrintings(name: String, setCode: String, number: String) = collection.searchPrintings(name, setCode, number)
    fun suggestedFinish(printing: Printing, marker: Foil) = collection.suggestedFinish(printing, marker)
    fun addFromReview(item: ReviewItem, printing: Printing, finish: Finish) = collection.addFromReview(item, printing, finish)
    fun skipReview(item: ReviewItem) = collection.skipReview(item)
    fun addScan(scanId: Long) = collection.addScan(scanId)
    fun undoScan(scanId: Long) = collection.undoScan(scanId)
    fun updateEntry(id: Long, quantity: Int, condition: String, finish: Finish) = collection.updateEntry(id, quantity, condition, finish)
    fun deleteEntry(id: Long) = collection.deleteEntry(id)
    fun changePrinting(id: Long, printing: Printing) = collection.changePrinting(id, printing)
    fun clearImportStatus() = collection.clearImportStatus()
    fun importCsv(uri: android.net.Uri) = collection.importCsv(uri)
    fun clearInventory() = collection.clearInventory()
    fun export(format: ExportFormat) = collection.export(format)
    fun shareHandled() = collection.shareHandled()
    suspend fun testServer() = processor.testServer()
    fun undoOnServer() = processor.undoOnServer()
    internal fun identify(card: Bitmap, foilCard: Bitmap?) = processor.enqueue(card, foilCard)

    // ------------------------------------------------------------------------
    // Camera frames
    // ------------------------------------------------------------------------

    val analyzer = ImageAnalysis.Analyzer { image -> analyze(image) }
    val analyzerExecutor get() = analysisExecutor

    private fun analyze(image: ImageProxy) {
        try {
            if (pendingReset) { pendingReset = false; tracker.reset() }
            tracker.setArea(settings.value.activeArea)
            val frame = image.toRgbaMat(settings.value.rotation)
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
        if (settings.value.captureSound) Sounds.capture()
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
        val rotation = (image.imageInfo.rotationDegrees + settings.value.rotation) % 360
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

    override fun onCleared() {
        analysisExecutor.shutdown()
        captureExecutor.shutdown()
        // Includes the settings writer's final flush and any blocking work still unwinding.
        viewModelScope.coroutineContext[Job]?.invokeOnCompletion {
            collection.close()
            cardData.close()
        }
    }

    companion object { private const val TAG = "Scanner" }
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
