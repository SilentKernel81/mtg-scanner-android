package com.cardscanner.reading

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.RotatedRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The OCR reader on the phone: light-ocr's reading (PaddleOCR PP-OCRv6 small - the networks in assets/ocr/, copies of the server's bundle) ported from the
 * server's Python reference (scripts/experiments/ocr_port/ppocr_reference.py, checked against light-ocr itself on 241 pictures: README there). Each block
 * names the Python lines it is. TextReaderParityTest checks the lines it reads against that reference's on the same pictures, on the phone.
 *
 * In: an RGB(A) picture (a flat card, or its strip - CardText.parseStrip / symbolRead read the strip's lines). Out: its text lines, in reading order, each
 * with its text, confidence and box (four corners in the picture's pixels, clockwise from the top-left) - the format the server's reader answers.
 *
 * Both networks run on OpenCV's dnn module (5.0: 4.14 could not import the recognizer); a dnn Net is not safe from two threads, so [read] is synchronized.
 * The arithmetic is done in the Python's types: the picture's normalisation in float32 (numpy's float32 arrays), the boxes' rescaling in float32 then
 * rounded half to even (numpy's round) - a box one pixel off changes a line's first character (measured: the README).
 */
class TextReader private constructor(private val det: Net, private val rec: Net, private val dictionary: List<String>) {

    /** read with its trace: the detector's input size and each line's quad, crop and tensor width (for the parity test) */
    class Trace {
        var detectionSize: Pair<Int, Int>? = null
        val quads = ArrayList<List<TextMath.Point>>()
        val crops = ArrayList<Triple<Int, Int, Pair<Int, Int>>>()       // (crop height, crop width, (tensor, content))
    }

    /** The text lines of [picture] (RGB or RGBA, 8-bit), in reading order; lines whose text is empty are left out (the reference's defaultScoreThreshold 0.0) */
    @Synchronized
    fun read(picture: Mat, trace: Trace? = null): List<CardText.Line> {
        val bgr = Mat()
        Imgproc.cvtColor(picture, bgr, if (picture.channels() == 4) Imgproc.COLOR_RGBA2BGR else Imgproc.COLOR_RGB2BGR)
        try {
            val lines = ArrayList<CardText.Line>()
            for (quad in detect(bgr, trace)) {
                val (text, confidence) = recognize(bgr, quad, trace)
                if (text.isNotEmpty() && confidence >= SCORE_THRESHOLD) lines.add(CardText.Line(text, confidence, quad))
            }
            return lines
        } finally {
            bgr.release()
        }
    }

    // ------------------------------------------------------------------ 1. detection (ppocr_reference.Reader.detect)

    private fun detect(bgr: Mat, trace: Trace?): List<List<TextMath.Point>> {
        val height = bgr.rows()
        val width = bgr.cols()
        val (rows, cols) = detectionSize(height, width)
        trace?.detectionSize = rows to cols
        // resized = cv2.resize(bgr, (cols, rows), INTER_LINEAR); blob = (resized.astype(float32) * scale - mean) / std, BGR order, NCHW
        val resized = Mat()
        Imgproc.resize(bgr, resized, Size(cols.toDouble(), rows.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val blob = normalised(resized, DET_MEAN, DET_STD, rows, cols, cols)
        resized.release()
        val prob: Mat
        synchronized(det) {
            det.setInput(blob)
            val out = det.forward()
            prob = out.reshape(1, rows).clone()                       // 1 x 1 x rows x cols -> rows x cols, CV_32F
            out.release()
        }
        blob.release()
        // bitmap = prob > threshold; contours of it (RETR_LIST, CHAIN_APPROX_SIMPLE)
        val bitmap = Mat()
        Imgproc.threshold(prob, bitmap, THRESHOLD.toDouble(), 255.0, Imgproc.THRESH_BINARY)   // > threshold -> 255 (a float map)
        val bitmap8 = Mat()
        bitmap.convertTo(bitmap8, CvType.CV_8U)
        bitmap.release()
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(bitmap8, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        bitmap8.release(); hierarchy.release()
        // ratio_x, ratio_y = width / cols, height / rows (Python floats); the rescaling is float32 * that (numpy keeps float32), rounded half to even
        val ratioX = (width.toDouble() / cols).toFloat()
        val ratioY = (height.toDouble() / rows).toFloat()
        val quads = ArrayList<List<TextMath.Point>>()
        for (contour in contours.take(MAX_CANDIDATES)) {
            val points = MatOfPoint2f(*contour.toArray().map { Point(it.x, it.y) }.toTypedArray())
            contour.release()
            val (box, short) = miniBox(points)
            points.release()
            if (short < MIN_SIDE) continue
            if (boxScoreFast(prob, box) < BOX_THRESHOLD) continue
            val grownPoints = grow(box)
            val grownMat = MatOfPoint2f(*grownPoints.map { Point(it.first.toFloat().toDouble(), it.second.toFloat().toDouble()) }.toTypedArray())
            val (grown, grownShort) = miniBox(grownMat)
            grownMat.release()
            if (grownShort < MIN_SIDE + 2) continue
            val quad = grown.map { p ->
                TextMath.Point(Math.rint((p.x.toFloat() * ratioX).toDouble()).coerceIn(0.0, width.toDouble()),
                    Math.rint((p.y.toFloat() * ratioY).toDouble()).coerceIn(0.0, height.toDouble()))
            }
            quads.add(TextMath.orderClockwise(quad))
        }
        prob.release()
        val ordered = TextMath.readingOrder(quads, ROW_BAND).map { quads[it] }
        trace?.quads?.addAll(ordered)
        return ordered
    }

    /**
     * ppocr_reference.mini_box (db_postprocess.get_mini_boxes): the min-area rectangle's corners as float32 (cv2.boxPoints), sorted by x, then ordered
     * top-left, top-right, bottom-right, bottom-left; and its shorter side
     */
    private fun miniBox(points: MatOfPoint2f): Pair<List<TextMath.Point>, Double> {
        val rect: RotatedRect = Geometry.minAreaRect(points)
        val corners = Mat()
        Geometry.boxPoints(rect, corners)                               // 4 x 2, CV_32F
        val c = FloatArray(8).also { corners.get(0, 0, it) }
        corners.release()
        val sorted = (0 until 4).map { TextMath.Point(c[it * 2].toDouble(), c[it * 2 + 1].toDouble()) }.sortedBy { it.x }   // sorted(key=x): stable
        val (i1, i4) = if (sorted[1].y > sorted[0].y) 0 to 1 else 1 to 0
        val (i2, i3) = if (sorted[3].y > sorted[2].y) 2 to 3 else 3 to 2
        return listOf(sorted[i1], sorted[i2], sorted[i3], sorted[i4]) to min(rect.size.width, rect.size.height)
    }

    /** ppocr_reference.box_score_fast: the mean of the probability map inside the quad, on its bounding rectangle (a filled-polygon mask) */
    private fun boxScoreFast(prob: Mat, box: List<TextMath.Point>): Double {
        val h = prob.rows()
        val w = prob.cols()
        val xmin = floor(box.minOf { it.x }).toInt().coerceIn(0, w - 1)
        val xmax = ceil(box.maxOf { it.x }).toInt().coerceIn(0, w - 1)
        val ymin = floor(box.minOf { it.y }).toInt().coerceIn(0, h - 1)
        val ymax = ceil(box.maxOf { it.y }).toInt().coerceIn(0, h - 1)
        val mask = Mat.zeros(ymax - ymin + 1, xmax - xmin + 1, CvType.CV_8U)
        // shifted.astype(int32): numpy truncates toward zero
        val polygon = MatOfPoint(*box.map { Point((it.x - xmin).toInt().toDouble(), (it.y - ymin).toInt().toDouble()) }.toTypedArray())
        Imgproc.fillPoly(mask, listOf(polygon), Scalar(1.0))
        polygon.release()
        val region = prob.submat(ymin, ymax + 1, xmin, xmax + 1)
        val score = Core.mean(region, mask).`val`[0]
        region.release(); mask.release()
        return score
    }

    /** ppocr_reference.grow: Clipper's round offset of the quad by area * unclipRatio / perimeter (OpenCV's own area and length of the float32 quad) */
    private fun grow(box: List<TextMath.Point>): List<Pair<Long, Long>> {
        val m = MatOfPoint2f(*box.map { Point(it.x, it.y) }.toTypedArray())
        val area = kotlin.math.abs(Geometry.contourArea(m))
        val perimeter = Geometry.arcLength(m, true)
        m.release()
        val distance = if (perimeter > 0) area * UNCLIP_RATIO / perimeter else 0.0
        return TextMath.clipperOffsetRound(box, distance)
    }

    // ------------------------------------------------------------------ 2. recognition (ppocr_reference.Reader.recognize)

    private fun recognize(bgr: Mat, quad: List<TextMath.Point>, trace: Trace?): Pair<String, Double> {
        var crop = rotateCrop(bgr, quad)
        if (crop.rows() >= crop.cols() * TALL_RATIO) {                  // a tall line: np.rot90 - turned 90 degrees counter-clockwise
            val turned = Mat()
            Core.rotate(crop, turned, Core.ROTATE_90_COUNTERCLOCKWISE)
            crop.release()
            crop = turned
        }
        val h = crop.rows()
        val w = crop.cols()
        val (tensor, content) = TextMath.tensorWidth(h, w)
        trace?.crops?.add(Triple(h, w, tensor to content))
        val resized = Mat()
        Imgproc.resize(crop, resized, Size(content.toDouble(), REC_HEIGHT.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        crop.release()
        val blob = normalised(resized, REC_MEAN, REC_STD, REC_HEIGHT, content, tensor)
        resized.release()
        val probabilities: FloatArray
        val steps: Int
        val classes: Int
        synchronized(rec) {
            rec.setInput(blob)
            val out = rec.forward()                                     // 1 x steps x classes
            steps = out.size(1)
            classes = out.size(2)
            probabilities = FloatArray(steps * classes)
            out.reshape(1, steps * classes).get(0, 0, probabilities)
            out.release()
        }
        blob.release()
        val (text, confidence, _) = TextMath.ctcDecode(probabilities, steps, classes, dictionary)
        return text to confidence
    }

    /** ppocr_reference.rotate_crop: the quad warped upright - width the longer of the top / bottom edges, height of the left / right (truncated); cubic, replicate */
    private fun rotateCrop(bgr: Mat, quad: List<TextMath.Point>): Mat {
        // np.linalg.norm of float32 differences: in float32
        fun norm(a: TextMath.Point, b: TextMath.Point): Float {
            val dx = a.x.toFloat() - b.x.toFloat()
            val dy = a.y.toFloat() - b.y.toFloat()
            return sqrt(dx * dx + dy * dy)
        }
        val width = max(max(norm(quad[0], quad[1]), norm(quad[2], quad[3])).toInt(), 1)
        val height = max(max(norm(quad[0], quad[3]), norm(quad[1], quad[2])).toInt(), 1)
        val src = MatOfPoint2f(*quad.map { Point(it.x.toFloat().toDouble(), it.y.toFloat().toDouble()) }.toTypedArray())
        val dst = MatOfPoint2f(Point(0.0, 0.0), Point(width.toDouble(), 0.0), Point(width.toDouble(), height.toDouble()), Point(0.0, height.toDouble()))
        val matrix = Geometry.getPerspectiveTransform(src, dst)
        src.release(); dst.release()
        val crop = Mat()
        Imgproc.warpPerspective(bgr, crop, matrix, Size(width.toDouble(), height.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        matrix.release()
        return crop
    }

    companion object {
        private const val TAG = "Scanner"

        // normalized-config.json (bundle ppocrv6-small-native-20260719.1), by its keys - ppocr_reference reads the same file
        /** detection.normalize.mean / std, in BGR order as listed; scale 1/255 */
        private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        private const val SCALE = 0.00392156862745098f
        /** detection.postprocess: threshold, boxThreshold, unclipRatio, minimumBoxSide, maxCandidates */
        private const val THRESHOLD = 0.3f
        private const val BOX_THRESHOLD = 0.6
        private const val UNCLIP_RATIO = 1.5
        private const val MIN_SIDE = 3.0
        private const val MAX_CANDIDATES = 3000
        /** runtimeDefaults.detection: maxSide, minimumShortSide */
        private const val MAX_SIDE = 960
        private const val MIN_SHORT_SIDE = 64
        /** geometry: rowBandPixels, tallLineRatio */
        private const val ROW_BAND = 10.0
        private const val TALL_RATIO = 1.5
        /** recognition: input shape height, normalize mean / std (0.5), defaultScoreThreshold */
        private const val REC_HEIGHT = 48
        private val REC_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
        private val REC_STD = floatArrayOf(0.5f, 0.5f, 0.5f)
        private const val SCORE_THRESHOLD = 0.0

        /** ppocr_reference.detection_size: the longer side at most MAX_SIDE (never enlarged), each side rounded UP to a multiple of 32, at least MIN_SHORT_SIDE */
        fun detectionSize(height: Int, width: Int): Pair<Int, Int> {
            val ratio = min(1.0, MAX_SIDE.toDouble() / max(height, width))
            fun side(n: Int) = max(MIN_SHORT_SIDE, ceil(n * ratio / 32.0).toInt() * 32)
            return side(height) to side(width)
        }

        /**
         * A BGR 8-bit picture as the network's NCHW float32 input, (pixel * scale - mean[c]) / std[c] in float32 (numpy's), [tensorWidth] wide - the columns past
         * the picture's [contentWidth] padded with 0.0 (recognition.normalize.paddingValue, after normalising)
         */
        private fun normalised(bgr: Mat, mean: FloatArray, std: FloatArray, rows: Int, contentWidth: Int, tensorWidth: Int): Mat {
            val pixels = ByteArray(rows * contentWidth * 3)
            bgr.get(0, 0, pixels)
            val data = FloatArray(3 * rows * tensorWidth)               // zeros: the padding
            for (y in 0 until rows) for (x in 0 until contentWidth) {
                val p = (y * contentWidth + x) * 3
                for (c in 0 until 3) {
                    data[(c * rows + y) * tensorWidth + x] = ((pixels[p + c].toInt() and 0xFF).toFloat() * SCALE - mean[c]) / std[c]
                }
            }
            return Mat(intArrayOf(1, 3, rows, tensorWidth), CvType.CV_32F).also { it.put(intArrayOf(0, 0, 0, 0), data) }
        }

        /** The reader from the app's assets (ocr/det.onnx, rec.onnx, dictionary.json), or null when one cannot be read - the caller then has no OCR */
        fun load(context: Context): TextReader? = try {
            fun bytes(name: String) = context.assets.open("ocr/$name").use { it.readBytes() }
            val det = Dnn.readNetFromONNX(MatOfByte(*bytes("det.onnx")))
            val rec = Dnn.readNetFromONNX(MatOfByte(*bytes("rec.onnx")))
            val characters = JSONObject(String(bytes("dictionary.json"), Charsets.UTF_8)).getJSONArray("characters")
            TextReader(det, rec, List(characters.length()) { characters.getString(it) })
        } catch (e: Exception) {
            Log.w(TAG, "OCR reader could not be loaded: ${e.message}")
            null
        }
    }
}
