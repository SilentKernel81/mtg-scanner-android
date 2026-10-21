package com.cardscanner

import com.cardscanner.reading.TextMath
import com.cardscanner.reading.TextMath.Point
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The OCR reader's arithmetic (reading/TextMath.kt) against the server's Python reference of light-ocr's reading (scripts/experiments/ocr_port/
 * ppocr_reference.py): ocr_math_reference.json, made by ocr_reference.py from that code - the grown box (Clipper's round offset, point for point), the
 * clockwise order, the reading order, the recognizer's tensor width, CTC decoding.
 */
class TextMathTest {
    private val reference by lazy { JSONObject(javaClass.getResource("/ocr_math_reference.json")!!.readText()) }

    private fun points(a: JSONArray) = List(a.length()) { a.getJSONArray(it).let { p -> Point(p.getDouble(0), p.getDouble(1)) } }
    private fun cases(name: String) = reference.getJSONArray(name).let { a -> List(a.length()) { a.getJSONObject(it) } }

    @Test fun theGrownBoxIsClippersPointForPoint() {
        val ratio = reference.getDouble("unclip_ratio")
        for ((i, case) in cases("grow").withIndex()) {
            val box = points(case.getJSONArray("box"))
            assertEquals("case $i: area", case.getDouble("area"), TextMath.area(box), 1e-6 * maxOf(1.0, case.getDouble("area")))
            assertEquals("case $i: perimeter", case.getDouble("perimeter"), TextMath.perimeter(box), 1e-6 * maxOf(1.0, case.getDouble("perimeter")))
            val distance = TextMath.unclipDistance(box, ratio)
            // OpenCV's contourArea / arcLength (the Python's) work on the box's float32 points: the distance agrees to ~1e-8, not to the last bit. The phone
            // (TextReader) calls those same OpenCV functions; what must hold either way is the next line - the whole-number points Clipper gives
            assertEquals("case $i: distance", case.getDouble("distance"), distance, 1e-6 * maxOf(1.0, distance))
            // the Python's own distance into the offset: the points must be its points exactly (whole numbers)
            val want = case.getJSONArray("points").let { a -> List(a.length()) { a.getJSONArray(it).let { p -> p.getLong(0) to p.getLong(1) } } }
            assertEquals("case $i: Clipper's points", want, TextMath.clipperOffsetRound(box, case.getDouble("distance")))
            assertEquals("case $i: Clipper's points from our own distance", want, TextMath.clipperOffsetRound(box, distance))
        }
    }

    @Test fun clockwiseFromTheTopLeft() {
        for ((i, case) in cases("clockwise").withIndex()) {
            assertEquals("case $i", points(case.getJSONArray("ordered")), TextMath.orderClockwise(points(case.getJSONArray("points"))))
        }
    }

    @Test fun theReadingOrder() {
        for ((i, case) in cases("reading_order").withIndex()) {
            val tops = points(case.getJSONArray("top_lefts"))
            val quads = tops.map { p -> listOf(p, Point(p.x + 10, p.y), Point(p.x + 10, p.y + 5), Point(p.x, p.y + 5)) }
            val want = case.getJSONArray("order").let { a -> List(a.length()) { a.getInt(it) } }
            assertEquals("case $i", want, TextMath.readingOrder(quads, case.getDouble("band")))
        }
    }

    @Test fun theRecognizersTensorWidth() {
        for (case in cases("tensor_width")) {
            val want = case.getJSONArray("tensor_and_content")
            assertEquals("${case.getInt("height")} x ${case.getInt("width")}", want.getInt(0) to want.getInt(1),
                TextMath.tensorWidth(case.getInt("height"), case.getInt("width")))
        }
    }

    @Test fun ctcDecoding() {
        val ctc = reference.getJSONObject("ctc")
        val dictionary = ctc.getJSONArray("dictionary").let { a -> List(a.length()) { a.getString(it) } }
        for ((i, case) in ctc.getJSONArray("cases").let { a -> List(a.length()) { a.getJSONObject(it) } }.withIndex()) {
            val rows = case.getJSONArray("probabilities")
            val steps = rows.length()
            val classes = rows.getJSONArray(0).length()
            val probabilities = FloatArray(steps * classes) { rows.getJSONArray(it / classes).getDouble(it % classes).toFloat() }
            val (text, confidence, indices) = TextMath.ctcDecode(probabilities, steps, classes, dictionary)
            assertEquals("case $i: text", case.getString("text"), text)
            assertEquals("case $i: confidence", case.getDouble("confidence"), confidence, 1e-7)
            assertArrayEquals("case $i: argmax", case.getJSONArray("indices").let { a -> IntArray(a.length()) { a.getInt(it) } }, indices)
        }
    }
}
