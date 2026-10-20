package com.cardscanner.reading

import android.content.Context
import android.util.Log
import org.opencv.core.CvType
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
 * probability of a foil out. Finding the set line on a card is NOT here: the server does it with its OCR
 * reader (foil_marker.set_line, which reads the strip's lines and picks the one that reads "SET . LANGUAGE"),
 * and the phone has no such reader. So this is not usable on the phone until the set line can be found;
 * NOT USED BY THE APP YET.
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
