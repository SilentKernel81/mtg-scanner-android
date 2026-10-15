package com.cardscanner.detection

import android.content.Context
import android.util.Log
import org.opencv.core.Mat

/**
 * Where the app looks for a card: the corner network first, the outline detector (findCardOutline)
 * when it finds no card, is unsure or gives a shape no card has - as the scanner server's stations
 * do (ObjectDetector.detect). Null still means "no card" to the tracker.
 */
object CardFinder {
    private const val TAG = "Scanner"

    /** Settings → Use the corner network; written from the analysis thread */
    @Volatile var enabled = true

    private var appContext: Context? = null
    private var net: CornerNet? = null
    private var tried = false

    /** Remember where to load the network from; it is loaded at the first frame (OpenCV is up by then) */
    fun install(context: Context) { appContext = context.applicationContext }

    @Synchronized private fun network(): CornerNet? {
        if (!tried) {
            tried = true
            net = appContext?.let { CornerNet.load(it) }
            Log.i(TAG, "Corner network: ${if (net != null) "loaded" else "not available - the outline detector alone"}")
        }
        return net
    }

    fun find(frame: Mat, previous: Corners? = null, allowLandscape: Boolean = false): Outline? {
        if (enabled) {
            network()?.let { network ->
                try {
                    val (corners, score) = network.find(frame, previous)
                    if (score >= CornerMath.MIN_SCORE && CornerMath.plausible(corners, frame.cols(), frame.rows(), allowLandscape)) {
                        return Outline(corners, score)
                    }
                } catch (e: Exception) {
                    // Never stop scanning over the second opinion
                    Log.w(TAG, "Corner network failed (${e.message}) - the outline detector alone from now on")
                    synchronized(this) { net = null }
                }
            }
        }
        return findCardOutline(frame, allowLandscape = allowLandscape, previous = previous)
    }
}
