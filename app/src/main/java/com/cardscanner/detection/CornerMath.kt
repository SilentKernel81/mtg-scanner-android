package com.cardscanner.detection

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * The arithmetic around the corner network, with no OpenCV in it (so a JVM test can run it): the
 * windows it is shown, its maps read as corner positions, and whether an answer is a card's
 * shape. A port of station/corner_net.py and `_plausible_neural` in object_detector.py in the
 * scanner server's repository - keep them in step.
 */
object CornerMath {
    const val WINDOW_W = 192
    const val WINDOW_H = 320
    const val STRIDE = 4
    /** The second stage's window is this much larger than the card's box */
    const val ZOOM_OUT = 1.45
    val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /** The network's corner maps: one per corner, WINDOW_H / 4 rows of WINDOW_W / 4 */
    const val MAP_W = WINDOW_W / STRIDE
    const val MAP_H = WINDOW_H / STRIDE

    /** Where a window of this height (frame pixels) centred on (cx, cy) puts a point of the frame */
    fun toWindow(p: Pt, cx: Double, cy: Double, height: Double): Pt {
        val s = WINDOW_H / height
        return Pt((p.x - cx) * s + WINDOW_W / 2.0, (p.y - cy) * s + WINDOW_H / 2.0)
    }

    /** ... and back: a point of the window in the frame */
    fun toFrame(p: Pt, cx: Double, cy: Double, height: Double): Pt {
        val s = WINDOW_H / height
        return Pt((p.x - WINDOW_W / 2.0) / s + cx, (p.y - WINDOW_H / 2.0) / s + cy)
    }

    /**
     * Each corner's expected position under a softmax of its map (sub-pixel), in window pixels.
     * maps: 4 x MAP_H x MAP_W values, corner by corner, row by row.
     */
    fun decode(maps: FloatArray): Corners {
        require(maps.size == 4 * MAP_H * MAP_W) { "Expected ${4 * MAP_H * MAP_W} map values, got ${maps.size}" }
        return List(4) { k ->
            val base = k * MAP_H * MAP_W
            var peak = Float.NEGATIVE_INFINITY
            for (i in 0 until MAP_H * MAP_W) peak = max(peak, maps[base + i])
            var total = 0.0
            var x = 0.0
            var y = 0.0
            for (row in 0 until MAP_H) for (col in 0 until MAP_W) {
                val w = exp((maps[base + row * MAP_W + col] - peak).toDouble())
                total += w
                x += w * (col + 0.5) * STRIDE
                y += w * (row + 0.5) * STRIDE
            }
            Pt(x / total, y / total)
        }
    }

    fun sigmoid(v: Double) = 1.0 / (1.0 + exp(-v))

    /** The window (centre x, centre y, height) for the next stage around a quad: the card's box, zoomed out */
    fun nextWindow(quad: Corners): Triple<Double, Double, Double> {
        val xs = quad.map { it.x }
        val ys = quad.map { it.y }
        // the card's height; for a quad that came out too flat, a card's shape from its width
        val cardHeight = max(ys.max() - ys.min(), (xs.max() - xs.min()) * WINDOW_H / WINDOW_W * 0.6)
        return Triple((xs.min() + xs.max()) / 2, (ys.min() + ys.max()) / 2, cardHeight * ZOOM_OUT)
    }

    const val MIN_SCORE = 0.5
    private const val ASPECT_TOLERANCE = 0.2
    private const val MIN_AREA = 0.02

    /** Whether four corners from the network are a card seen from above: convex, a card's shape, in the frame */
    fun plausible(corners: Corners, width: Int, height: Int, allowLandscape: Boolean = false): Boolean {
        if (corners.size != 4 || corners.any { !it.x.isFinite() || !it.y.isFinite() }) return false
        val marginX = 0.02 * width
        val marginY = 0.02 * height
        if (corners.any { it.x < -marginX || it.x > width + marginX || it.y < -marginY || it.y > height + marginY }) return false
        // convex: every turn the same way (the shoelace area is then also the polygon's)
        var sign = 0
        var area = 0.0
        for (i in 0 until 4) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            val c = corners[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cross != 0.0) {
                val s = if (cross > 0) 1 else -1
                if (sign != 0 && s != sign) return false
                sign = s
            }
            area += a.x * b.y - b.x * a.y
        }
        if (abs(area) / 2 < MIN_AREA * width * height) return false
        val sideW = ((corners[1] - corners[0]).norm() + (corners[2] - corners[3]).norm()) / 2
        val sideH = ((corners[3] - corners[0]).norm() + (corners[2] - corners[1]).norm()) / 2
        if (min(sideW, sideH) == 0.0 || (sideW > sideH && !allowLandscape)) return false
        val ratio = max(sideW, sideH) / min(sideW, sideH)
        return abs(ratio - CARD_ASPECT_RATIO) / CARD_ASPECT_RATIO <= ASPECT_TOLERANCE
    }
}
