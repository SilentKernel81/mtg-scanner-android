package com.cardscanner.detection

import android.content.Context
import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import com.cardscanner.detection.CornerMath.WINDOW_H
import com.cardscanner.detection.CornerMath.WINDOW_W

/**
 * Our own card corner network, run with OpenCV's dnn module - a port of station/corner_net.py in
 * the scanner server's repository (the file is the same: assets/corner_net.onnx, trained there
 * from labelled camera frames only). A frame is read in a window of 192 x 320 pixels: the whole
 * frame in one window to find the card, then one window around it for the corners; with the
 * card's previous outline known the first stage is skipped. Everything numeric is in CornerMath.
 */
class CornerNet private constructor(private val net: Net) {

    /**
     * The card in a frame (RGB or RGBA, any size): its corners in the frame's pixels - top left,
     * top right, bottom right, bottom left - and a score 0..1 for "a card is here".
     */
    fun find(frame: Mat, previous: Corners? = null, refine: Int = 1): Pair<Corners, Double> {
        val rgb = if (frame.channels() == 4) Mat().also { Imgproc.cvtColor(frame, it, Imgproc.COLOR_RGBA2RGB) } else frame
        try {
            var quad: Corners
            var score: Double
            if (previous == null) {
                // The whole frame in one window (taller than any frame: letterboxed)
                val height = max(frame.rows().toDouble(), frame.cols().toDouble() * WINDOW_H / WINDOW_W)
                val first = read(rgb, frame.cols() / 2.0, frame.rows() / 2.0, height)
                quad = first.first
                score = first.second
            } else {
                quad = previous
                score = 0.0
            }
            repeat(refine) {
                val (cx, cy, height) = CornerMath.nextWindow(quad)
                val again = read(rgb, cx, cy, height)
                quad = again.first
                score = max(score, again.second)
            }
            return quad to score
        } finally {
            if (rgb !== frame) rgb.release()
        }
    }

    private fun max(a: Double, b: Double) = if (a > b) a else b

    /** One window: the corners in frame pixels, and the score */
    private fun read(rgb: Mat, cx: Double, cy: Double, height: Double): Pair<Corners, Double> {
        val scale = WINDOW_H / height
        val matrix = Mat(2, 3, CvType.CV_64F)
        matrix.put(0, 0, scale, 0.0, WINDOW_W / 2.0 - scale * cx, 0.0, scale, WINDOW_H / 2.0 - scale * cy)
        val window = Mat()
        // The flags the network was trained with (INTER_AREA is treated as linear by warpAffine)
        Imgproc.warpAffine(rgb, window, matrix, Size(WINDOW_W.toDouble(), WINDOW_H.toDouble()),
            if (scale < 1) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
        matrix.release()

        // (pixel / 255 - mean) / std, planar RGB: the blob [1, 3, H, W]
        val bytes = ByteArray(WINDOW_W * WINDOW_H * 3)
        window.get(0, 0, bytes)
        window.release()
        val plane = WINDOW_W * WINDOW_H
        val values = FloatArray(3 * plane)
        for (i in 0 until plane) for (c in 0 until 3) {
            values[c * plane + i] = ((bytes[i * 3 + c].toInt() and 0xFF) / 255f - CornerMath.MEAN[c]) / CornerMath.STD[c]
        }
        val blob = Mat(intArrayOf(1, 3, WINDOW_H, WINDOW_W), CvType.CV_32F)
        blob.put(intArrayOf(0, 0, 0, 0), values)

        val outputs = ArrayList<Mat>()
        synchronized(net) {  // a dnn network is not safe to run from two threads at once
            net.setInput(blob)
            net.forward(outputs, net.unconnectedOutLayersNames)
        }
        blob.release()
        try {
            val maps = outputs.first { it.dims() > 2 }
            val flat = FloatArray(maps.total().toInt())
            // reshape() is a new Mat sharing the maps' memory: released here, or that memory waits for the garbage collector after the outputs are released
            // (per camera frame - a Codex review of 2026-10-21 found the pattern in the OCR reader)
            val view = maps.reshape(1, CornerMath.MAP_H * 4)
            try { view.get(0, 0, flat) } finally { view.release() }
            val presence = outputs.first { it.dims() <= 2 }.get(0, 0)[0]
            val corners = CornerMath.decode(flat).map { CornerMath.toFrame(it, cx, cy, height) }
            return corners to CornerMath.sigmoid(presence)
        } finally {
            outputs.forEach { it.release() }
        }
    }

    companion object {
        private const val TAG = "Scanner"
        private const val ASSET = "corner_net.onnx"

        /** The network from the app's assets, or null when it can't be read (the outline detector then works alone) */
        fun load(context: Context): CornerNet? = try {
            val bytes = context.assets.open(ASSET).use { it.readBytes() }
            CornerNet(Dnn.readNetFromONNX(MatOfByte(*bytes)))
        } catch (e: Exception) {
            Log.w(TAG, "Corner network could not be loaded: ${e.message}")
            null
        }
    }
}
