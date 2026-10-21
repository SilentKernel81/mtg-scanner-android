package com.cardscanner

import com.cardscanner.reading.CardText
import com.cardscanner.reading.TextMath.Point
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The OCR parsers (reading/CardText.kt) give the server's answers (core/card_ocr.py parse_magic / parse_strip, core/foil_marker.py set_line / symbol_read)
 * on light-ocr's real lines of 30 pictures - each read whole and its strip - and on hand-written lines (a token, 123/281, look-alike digits, stray mana
 * symbols, a star, a dot, Unicode spaces, a set line above the number): ocr_parser_reference.json, made by ocr_reference.py from the server's own code.
 */
class CardTextTest {
    private val cases by lazy {
        JSONObject(javaClass.getResource("/ocr_parser_reference.json")!!.readText()).getJSONArray("cases").let { a -> List(a.length()) { a.getJSONObject(it) } }
    }

    private fun lines(case: JSONObject) = case.getJSONArray("lines").let { a ->
        List(a.length()) { i ->
            val l = a.getJSONObject(i)
            val box = l.getJSONArray("box").let { b -> List(b.length()) { b.getJSONArray(it).let { p -> Point(p.getDouble(0), p.getDouble(1)) } } }
            CardText.Line(l.getString("text"), l.getDouble("confidence"), box)
        }
    }

    @Test fun everyParserGivesThePythonsAnswer() {
        for (case in cases) {
            val name = case.getString("name")
            val lines = lines(case)
            val magic = CardText.parseMagic(lines, case.getInt("width"), case.getInt("height"))
            if (case.isNull("parse_magic")) assertNull("$name: parse_magic", magic)
            else {
                val want = case.getJSONObject("parse_magic")
                assertEquals("$name: parse_magic", CardText.MagicRead(want.getString("name"), want.getString("collector_number"), want.getString("set_code"),
                    want.getString("foil")), magic)
            }
            val strip = case.getJSONArray("parse_strip")
            assertEquals("$name: parse_strip", strip.getString(0) to strip.getString(1), CardText.parseStrip(lines))
            val line = CardText.setLine(lines)
            assertEquals("$name: set_line", if (case.isNull("set_line_index")) null else lines[case.getInt("set_line_index")], line)
            assertEquals("$name: symbol_read", if (case.isNull("symbol_read")) null else case.getString("symbol_read"), line?.let { CardText.symbolRead(it) })
        }
    }

    @Test fun theFileHasTheCasesThatMatter() {
        // a sanity check of the reference itself: real lines of both kinds, and the hand-written edge cases
        val names = cases.map { it.getString("name") }
        assertEquals(60, names.count { it.endsWith(".jpg") || it.endsWith(".jpg (strip)") })
        for (wanted in listOf("a token", "123/281 of an older card", "look-alike digits", "a Unicode space", "the set line before the number")) {
            assertEquals(wanted, true, wanted in names)
        }
    }
}
