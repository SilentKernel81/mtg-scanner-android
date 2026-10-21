package com.cardscanner.reading

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The plain arithmetic of the OCR reader (light-ocr's PP-OCRv6 pipeline): ports of the server's scripts/experiments/ocr_port/ppocr_reference.py, which was
 * checked against light-ocr itself (its README: the texts and parses agree on 241 pictures). Nothing here touches a picture or OpenCV - so all of it is
 * tested on the JVM against cases the Python makes (app/src/test/resources/ocr_reference.py -> ocr_math_reference.json). The steps that need OpenCV
 * (contours, the min-area rectangle, the warp, the networks) are TextReader.kt's.
 *
 * The order of the arithmetic is the Python's, operation by operation: a different order can round a double differently, and the grown box below is
 * made of rounded integers - one unit off moves a line's crop by a pixel, which changes the first character of a line (measured, the README).
 */
object TextMath {
    /** A point in a picture's pixels */
    data class Point(val x: Double, val y: Double)

    /** ppocr_reference: the area of a polygon (cv2.contourArea's absolute value: the shoelace sum, in doubles) */
    fun area(points: List<Point>): Double {
        var sum = 0.0
        for (i in points.indices) {
            val a = points[(i + points.size - 1) % points.size]
            val b = points[i]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2.0
    }

    /** cv2.arcLength(closed): the sum of the edges' lengths */
    fun perimeter(points: List<Point>): Double {
        var sum = 0.0
        for (i in points.indices) {
            val a = points[(i + points.size - 1) % points.size]
            val b = points[i]
            sum += sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y))
        }
        return sum
    }

    /** db_postprocess.unclip's distance: area * ratio / perimeter (0 for a degenerate box) */
    fun unclipDistance(box: List<Point>, ratio: Double): Double {
        val perimeter = perimeter(box)
        return if (perimeter > 0) area(box) * ratio / perimeter else 0.0
    }

    /** ppocr_reference.ARC_TOLERANCE: ClipperOffset's default */
    private const val ARC_TOLERANCE = 0.25

    /** Clipper's Round: half away from zero, to a long */
    fun clipperRound(value: Double): Long = if (value < 0) (value - 0.5).toLong() else (value + 0.5).toLong()

    /**
     * ppocr_reference.clipper_offset_round: Clipper 6.4.2's ClipperOffset (MiterLimit 2, ArcTolerance 0.25) on ONE closed polygon with JT_ROUND joins and
     * delta > 0, as PaddleOCR calls pyclipper - the input points truncated to integers (pyclipper's IntPoint), the output Clipper's rounded points. Its
     * min-area rectangle is the grown text box (the Python: the same rectangle as pyclipper's for 3,000 of 3,000 random quads).
     */
    fun clipperOffsetRound(points: List<Point>, delta: Double): List<Pair<Long, Long>> {
        // AddPath: integers (truncated toward zero), consecutive duplicates removed, the closing duplicate too
        val path = ArrayList<Pair<Long, Long>>()
        for (p in points) {
            val pt = p.x.toLong() to p.y.toLong()
            if (path.isEmpty() || pt != path.last()) path.add(pt)
        }
        if (path.size > 1 && path.first() == path.last()) path.removeAt(path.size - 1)
        if (path.size < 3 || delta <= 0) return path
        // FixOrientations: Clipper's Area() = -0.5 * sum((x_prev + x) * (y_prev - y)); negative: Orientation false, the polygon is reversed
        var area = 0.0
        for (i in path.indices) {
            val (x1, y1) = path[(i + path.size - 1) % path.size]
            val (x2, y2) = path[i]
            area += (x1 + x2).toDouble() * (y1 - y2).toDouble()
        }
        if (area * -0.5 < 0) path.reverse()
        // DoOffset's constants
        var steps = PI / acos(1 - ARC_TOLERANCE / abs(delta))
        if (steps > abs(delta) * PI) steps = abs(delta) * PI
        val sinStep = sin(2 * PI / steps)
        val cosStep = cos(2 * PI / steps)
        val stepsPerRad = steps / (2 * PI)
        // the edges' unit normals (GetUnitNormal)
        val n = path.size
        val normals = Array(n) { j ->
            val (x1, y1) = path[j]
            val (x2, y2) = path[(j + 1) % n]
            val dx = (x2 - x1).toDouble()
            val dy = (y2 - y1).toDouble()
            if (dx == 0.0 && dy == 0.0) 0.0 to 0.0
            else {
                val f = 1.0 / sqrt(dx * dx + dy * dy)
                dy * f to -dx * f
            }
        }
        val out = ArrayList<Pair<Long, Long>>()
        var k = n - 1
        for (j in 0 until n) {
            val sx = path[j].first.toDouble()
            val sy = path[j].second.toDouble()
            val (nkx, nky) = normals[k]
            val (njx, njy) = normals[j]
            var sinA = nkx * njy - njx * nky
            if (abs(sinA * delta) < 1.0) {
                val cosA = nkx * njx + njy * nky
                if (cosA > 0) {                                           // nearly no angle: one point
                    out.add(clipperRound(sx + nkx * delta) to clipperRound(sy + nky * delta))
                    k = j
                    continue
                }
            } else if (sinA > 1.0) sinA = 1.0 else if (sinA < -1.0) sinA = -1.0
            if (sinA * delta < 0) {                                       // a concave corner
                out.add(clipperRound(sx + nkx * delta) to clipperRound(sy + nky * delta))
                out.add(path[j])
                out.add(clipperRound(sx + njx * delta) to clipperRound(sy + njy * delta))
            } else {                                                      // DoRound
                val a = atan2(sinA, nkx * njx + nky * njy)
                val count = max(clipperRound(stepsPerRad * abs(a)), 1L)
                var x = nkx
                var y = nky
                for (i in 0 until count) {
                    out.add(clipperRound(sx + x * delta) to clipperRound(sy + y * delta))
                    val x2 = x
                    x = x * cosStep - sinStep * y
                    y = x2 * sinStep + y * cosStep
                }
                out.add(clipperRound(sx + njx * delta) to clipperRound(sy + njy * delta))
            }
            k = j
        }
        return out
    }

    /**
     * ppocr_reference.order_clockwise (predict_det.order_points_clockwise): top-left, top-right, bottom-right, bottom-left - the two with the smallest x are
     * the left ones (a stable sort: equal x keep their order), of each pair the smaller y is the top
     */
    fun orderClockwise(points: List<Point>): List<Point> {
        val byX = points.sortedBy { it.x }                                // sortedBy is stable, as numpy's kind='stable'
        val left = byX.take(2).sortedBy { it.y }
        val right = byX.drop(2).sortedBy { it.y }
        return listOf(left[0], right[0], right[1], left[1])
    }

    /**
     * ppocr_reference.reading_order (predict_system.sorted_boxes): by the top-left corner's y, then x; then each line moved back past its predecessors while
     * their top-left y differ by less than [band] and its x is smaller. [quads]: each clockwise from its top-left. Returns the indices in reading order
     */
    fun readingOrder(quads: List<List<Point>>, band: Double): List<Int> {
        val order = quads.indices.sortedWith(compareBy<Int>({ quads[it][0].y }, { quads[it][0].x })).toMutableList()
        for (i in 0 until order.size - 1) {
            var j = i
            while (j >= 0) {
                val next = quads[order[j + 1]][0]
                val here = quads[order[j]][0]
                if (abs(next.y - here.y) < band && next.x < here.x) {
                    val t = order[j]; order[j] = order[j + 1]; order[j + 1] = t
                    j--
                } else break
            }
        }
        return order
    }

    /**
     * ppocr_reference.tensor_width (PaddleOCR's resize_norm_img): (the tensor's width, the content's width) of a crop [cropHeight] x [cropWidth] -
     * tensor = int(48 * max(320 / 48, w / h)) (truncated), at most [maxWidth]; content = min(tensor, ceil(48 * w / h))
     */
    fun tensorWidth(cropHeight: Int, cropWidth: Int, height: Int = 48, minWidth: Int = 320, maxWidth: Int = 3200): Pair<Int, Int> {
        val ratio = cropWidth / cropHeight.toDouble()
        var tensor = (height * max(minWidth / height.toDouble(), ratio)).toInt()
        tensor = min(tensor, maxWidth)
        val content = min(tensor, ceil(height * ratio).toInt())
        return tensor to content
    }

    /**
     * ppocr_reference.ctc_decode: greedy CTC on [probabilities] (steps x classes, row-major): per step the argmax (the first of equal ones, as numpy's argmax);
     * index 0 (the blank) dropped; a repeat of the previous step's index collapsed; character = dictionary[index - 1]. Confidence: the mean of the kept steps'
     * probabilities (in doubles, of the float32 values), 0 for none. Returns (text, confidence, each step's argmax)
     */
    fun ctcDecode(probabilities: FloatArray, steps: Int, classes: Int, dictionary: List<String>): Triple<String, Double, IntArray> {
        val indices = IntArray(steps)
        val text = StringBuilder()
        var sum = 0.0
        var kept = 0
        var previous = -1
        for (t in 0 until steps) {
            var best = 0
            val base = t * classes
            for (c in 1 until classes) if (probabilities[base + c] > probabilities[base + best]) best = c
            indices[t] = best
            if (best != 0 && best != previous) {
                text.append(dictionary[best - 1])
                sum += probabilities[base + best].toDouble()
                kept++
            }
            previous = best
        }
        return Triple(text.toString(), if (kept > 0) sum / kept else 0.0, indices)
    }
}
