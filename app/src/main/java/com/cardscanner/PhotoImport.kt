package com.cardscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.IOException
import android.util.Log
import com.cardscanner.detection.Corners
import com.cardscanner.detection.MultiCardFinder
import com.cardscanner.detection.Pt
import com.cardscanner.detection.findCardOutline
import com.cardscanner.detection.warpCard
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Rect
import kotlin.math.max

/**
 * A photo picked for import and the card outlines found in it, in the photo's pixels. `retry`: the
 * outlines are the cards a partly failed import could not save - the rest are already in the capture path.
 */
class PhotoImportState(val photo: Bitmap, val detected: List<Corners>, val retry: Boolean = false)

/**
 * Several cards from one photo (a binder page, cards on a table): decode it, find the cards, and -
 * after the user has checked the outlines - cut them out one at a time and hand each to the
 * capture path (`enqueue`), so each is saved, read and settled like a live capture.
 */
internal class PhotoImporter(private val context: Context) {
    /**
     * The photo upright (EXIF orientation applied) and at most MAX_SIDE px on its long side.
     * ImageDecoder needs API 28 and the app runs on API 26: below that BitmapFactory decodes it and
     * the EXIF turn is applied by hand. `useImageDecoder` is only for the test of the second path.
     */
    fun decode(uri: Uri, useImageDecoder: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P): Bitmap =
        if (useImageDecoder) decodeWithImageDecoder(uri) else decodeWithBitmapFactory(uri)

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE  // OpenCV reads the pixels
            var sample = 1
            while (max(info.size.width, info.size.height) / sample > MAX_SIDE) sample *= 2
            if (sample > 1) decoder.setTargetSampleSize(sample)
        }
    }

    private fun decodeWithBitmapFactory(uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("The photo could not be decoded")
        val orientation = runCatching {
            resolver.openInputStream(uri).use {
                ExifInterface(it!!).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = exifMatrix(orientation) ?: return decoded
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { decoded.recycle() }
    }

    fun detect(photo: Bitmap): List<Corners> {
        val mat = Mat()
        try {
            Utils.bitmapToMat(photo, mat)  // RGBA
            return MultiCardFinder.find(mat)
        } finally { mat.release() }
    }

    /**
     * Cut each outline out of the photo and enqueue it. One card is cut, enqueued and released at a
     * time (a photo of a full page is large); `onProgress` gets the count done after each.
     * `enqueue` says whether the card was durably saved. Returns the outlines of the cards that
     * were not (cut failed or save failed), so that only those can be tried again.
     */
    fun importCards(photo: Bitmap, outlines: List<Corners>, enqueue: (Bitmap) -> Boolean, onProgress: (Int) -> Unit): List<Corners> {
        val failed = ArrayList<Corners>()
        val full = Mat()
        try {
            Utils.bitmapToMat(photo, full)
            outlines.forEachIndexed { index, outline ->
                try {
                    val flat = warpCard(full, refine(full, outline), outH = flatHeight(outline))
                    val card = Bitmap.createBitmap(flat.cols(), flat.rows(), Bitmap.Config.ARGB_8888)
                        .also { Utils.matToBitmap(flat, it) }
                    flat.release()
                    // The flat card is also the picture for the ★/• check, as with an outline capture
                    if (!enqueue(card)) failed += outline
                } catch (e: Exception) {
                    Log.e(TAG, "Card ${index + 1} of the photo could not be imported", e)
                    failed += outline
                }
                onProgress(index + 1)
            }
        } finally { full.release() }
        return failed
    }

    private fun flatHeight(outline: Corners): Int {
        val (tl, tr, br, bl) = outline
        return minOf(MAX_CARD_HEIGHT, max(max((bl - tl).norm(), (br - tr).norm()), max((tr - tl).norm(), (br - bl).norm())).toInt())
    }

    /**
     * Find the card's exact edges again on a padded crop of the full photo, starting from the
     * outline given (a grid cell is only approximate). Kept only if it lies where the given one
     * does: in a crop with neighbours the search could latch onto another card. The corner network
     * is not used here - the live camera's analysis thread owns it.
     */
    private fun refine(full: Mat, outline: Corners): Corners {
        val size = max((outline[3] - outline[0]).norm(), (outline[1] - outline[0]).norm())
        val pad = 0.08 * size
        val x0 = (outline.minOf { it.x } - pad).toInt().coerceIn(0, full.cols() - 2)
        val y0 = (outline.minOf { it.y } - pad).toInt().coerceIn(0, full.rows() - 2)
        val x1 = (outline.maxOf { it.x } + pad).toInt().coerceIn(x0 + 1, full.cols())
        val y1 = (outline.maxOf { it.y } + pad).toInt().coerceIn(y0 + 1, full.rows())
        val crop = full.submat(Rect(x0, y0, x1 - x0, y1 - y0))
        try {
            val shifted = outline.map { Pt(it.x - x0, it.y - y0) }
            val found = findCardOutline(crop, allowLandscape = true, previous = shifted)?.corners ?: return outline
            // The same corner order is needed to compare: the outline's own order is the finder's
            val moved = found.maxOf { f -> shifted.minOf { (f - it).norm() } }
            val covered = shifted.maxOf { s -> found.minOf { (s - it).norm() } }
            if (max(moved, covered) > 0.08 * size) return outline
            return found.map { Pt(it.x + x0, it.y + y0) }
        } finally { crop.release() }
    }

    companion object {
        /** The turn / flip an EXIF orientation asks for, or null for none */
        internal fun exifMatrix(orientation: Int): Matrix? = Matrix().let { m ->
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
                else -> return null
            }
            m
        }

        private const val TAG = "Scanner"
        /** A 12 MP photo is kept whole; larger ones are decoded smaller */
        const val MAX_SIDE = 4096
        /** A card's cut-out is never taller than this: what a live capture of a card measures */
        const val MAX_CARD_HEIGHT = 1600
    }
}
