package com.cardscanner.reading

import android.content.Context
import android.util.Log
import org.opencv.core.CvType
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The foil-marker network: a small network (about 100,000 parameters, run with OpenCV's dnn module) that
 * tells whether the set line of a card carries a foil's star or a regular card's dot - a port of
 * FoilMarker.probability in core/foil_marker.py in the scanner server's repository (the file is the same:
 * assets/foil_net.onnx, trained there; measured on 13 recent sets only).
 *
 * What this class does is the NETWORK: a grey crop of the set line, 32 high x 128 wide, in; the
 * probability of a foil out. The server finds the set line with its OCR reader (foil_marker.set_line), which
 * the phone does not have; what the phone has (since 2026-10-21) is the server's reading with OCR OFF: the
 * crop cut at a FIXED place on the flat card ([fixedCrop], foil_marker.fixed_crop - the median of the set
 * line's box over 1,487 cards), asked only for a card the picture search has turned upright with confidence
 * and whose printings all print a marker (PictureReader). Measured on the server with this network and this
 * threshold (600 held-out cards where the marker is needed): 95.5% answered, 99.3% of those right; on the 15
 * Deadpool phone captures (confetti foils) 10 answered, 9 right.
 *
 * What the network must be fed (the server's notes in foil_marker.py): the crop is cut from the strip of a
 * flat card (the lowest 14% x the left 62%), with a quarter of the line's height added on every side, made
 * grey and scaled to 128 x 32 with INTER_AREA; each crop's contrast is normalised on its own - the sample
 * standard deviation (n - 1), as torch's - because cameras differ in brightness and a sleeve's glare shifts it:
 * what counts is the shape.
 */
class FoilNet private constructor(private val net: Net) {

    /**
     * How likely a crop shows a foil's star: 0..1. [crop] is a single-channel 8-bit Mat of [CROP_H] rows and
     * [CROP_W] columns.
     */
    fun probability(crop: Mat): Double {
        require(crop.type() == CvType.CV_8UC1 && crop.rows() == CROP_H && crop.cols() == CROP_W) { "a grey 32 x 128 crop expected" }
        val pixels = ByteArray(CROP_W * CROP_H)
        crop.get(0, 0, pixels)
        val blob = Mat(intArrayOf(1, 1, CROP_H, CROP_W), CvType.CV_32F)
        blob.put(intArrayOf(0, 0, 0, 0), normalise(pixels))
        val logits = FloatArray(2)
        synchronized(net) {  // a dnn network is not safe to run from two threads at once
            net.setInput(blob)
            val out = net.forward()
            try {
                out.reshape(1, 1).get(0, 0, logits)
            } finally {
                out.release()
            }
        }
        blob.release()
        return foilProbability(logits)
    }

    companion object {
        private const val TAG = "Scanner"
        private const val ASSET = "foil_net.onnx"
        const val CROP_W = 128
        const val CROP_H = 32

        /** foil_marker.STRIP (y from, y to, x from, x to - fractions of the card), STRIP_CARD_HEIGHT, MARGIN, FIXED_BOX, SURE */
        private val STRIP = doubleArrayOf(0.86, 1.0, 0.0, 0.62)
        private const val STRIP_CARD_HEIGHT = 1000
        private const val MARGIN = 0.25
        private val FIXED_BOX = doubleArrayOf(0.9460, 0.9698, 0.0627, 0.3970)
        /** The probability one way or the other that is believed (foil_marker.SURE; Decide.FINISH_SURE) */
        const val SURE = 0.9

        /**
         * The bottom-left strip of a flat card (foil_marker.strip_of), enlarged (INTER_CUBIC) when the card is under [STRIP_CARD_HEIGHT] high. The bounds are
         * Python's int(height * 0.86) etc.: a double product cut toward zero. [card]: RGB or RGBA.
         */
        fun stripOf(card: Mat): Mat {
            val height = card.rows()
            val width = card.cols()
            val y0 = (height * STRIP[0]).toInt()
            val y1 = (height * STRIP[1]).toInt()
            val x0 = (width * STRIP[2]).toInt()
            val x1 = (width * STRIP[3]).toInt()
            val strip = card.submat(y0, y1, x0, x1)
            val scale = maxOf(1.0, STRIP_CARD_HEIGHT.toDouble() / height)
            if (scale == 1.0) return strip.clone().also { strip.release() }
            val out = Mat()
            Imgproc.resize(strip, out, Size(0.0, 0.0), scale, scale, Imgproc.INTER_CUBIC)   // dsize from fx / fy, as cv2.resize(strip, None, fx=, fy=)
            strip.release()
            return out
        }

        /**
         * foil_marker.set_line_crop: the set line (a box [left, top, right, bottom] in the strip's pixels) with a quarter of its height added on every side,
         * grey, scaled to 128 x 32 with INTER_AREA - or null when nothing is left. Python slices clamp at the picture's edge; so do these bounds.
         */
        fun setLineCrop(strip: Mat, left: Double, top: Double, right: Double, bottom: Double): Mat? {
            val margin = MARGIN * (bottom - top)
            val y0 = maxOf((top - margin).toInt(), 0)
            val y1 = minOf((bottom + margin).toInt(), strip.rows())
            val x0 = maxOf((left - margin).toInt(), 0)
            val x1 = minOf((right + margin).toInt(), strip.cols())
            if (y1 <= y0 || x1 <= x0) return null
            val part = strip.submat(Rect(x0, y0, x1 - x0, y1 - y0))
            val grey = Mat()
            Imgproc.cvtColor(part, grey, if (part.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGB2GRAY)
            part.release()
            val crop = Mat()
            Imgproc.resize(grey, crop, Size(CROP_W.toDouble(), CROP_H.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            grey.release()
            return crop
        }

        /**
         * foil_marker.fixed_crop: the network's input cut at [FIXED_BOX] of a flat card (RGB / RGBA) - through the same strip as training (enlarged when small)
         * and the same margin - or null. The card must be UPRIGHT: on a card upside down this place is the art and the title.
         */
        fun fixedCrop(card: Mat): Mat? {
            if (card.empty() || minOf(card.rows(), card.cols()) < 2) return null
            val strip = stripOf(card)
            try {
                val stripHeight = strip.rows().toDouble()
                val stripWidth = strip.cols().toDouble()
                // the Python arithmetic, in its order: (y - STRIP[0]) / (STRIP[1] - STRIP[0]) * strip_height, x / STRIP[3] * strip_width
                val top = (FIXED_BOX[0] - STRIP[0]) / (STRIP[1] - STRIP[0]) * stripHeight
                val bottom = (FIXED_BOX[1] - STRIP[0]) / (STRIP[1] - STRIP[0]) * stripHeight
                val left = FIXED_BOX[2] / STRIP[3] * stripWidth
                val right = FIXED_BOX[3] / STRIP[3] * stripWidth
                return setLineCrop(strip, left, top, right, bottom)
            } finally {
                strip.release()
            }
        }

        /** The network from the app's assets, or null when it can't be read */
        fun load(context: Context): FoilNet? = try {
            val bytes = context.assets.open(ASSET).use { it.readBytes() }
            FoilNet(Dnn.readNetFromONNX(MatOfByte(*bytes)))
        } catch (e: Exception) {
            Log.w(TAG, "Foil network could not be loaded: ${e.message}")
            null
        }

        /**
         * A crop's bytes as the network's input: x = pixel / 255, then (x - mean) / (sample std + 0.001). The
         * server does this in float32 (numpy); here the sums are in doubles and the result is rounded to
         * float32, which differs from it by rounding only (the parity tests allow 1e-5).
         */
        fun normalise(pixels: ByteArray): FloatArray {
            val n = pixels.size
            val x = FloatArray(n) { (pixels[it].toInt() and 0xFF) / 255f }
            var sum = 0.0
            for (v in x) sum += v
            val mean = sum / n
            var squares = 0.0
            for (v in x) squares += (v - mean) * (v - mean)
            val std = sqrt(squares / (n - 1))                      // ddof = 1: torch's standard deviation
            return FloatArray(n) { ((x[it] - mean) / (std + 1e-3)).toFloat() }
        }

        /** The network's two outputs (non-foil, foil) as the probability of a foil: a softmax, index 1 is "foil" (training's label) */
        fun foilProbability(logits: FloatArray): Double {
            val biggest = maxOf(logits[0], logits[1])
            val e0 = exp((logits[0] - biggest).toDouble())
            val e1 = exp((logits[1] - biggest).toDouble())
            return e1 / (e0 + e1)
        }
    }
}
