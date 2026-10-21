package com.cardscanner

import com.cardscanner.reading.CardText
import com.cardscanner.reading.TextMath.Point
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

/**
 * CardTextTest's cases ON THE PHONE (a copy of ocr_parser_reference.json in assets/parity/, made by ocr_reference.py): Android's regex engine is ICU, not
 * the desktop JVM's - an inline flag the JVM took ((?U)) made every parser fail to start on a phone while the JVM tests passed (a Codex review of
 * 2026-10-21). The parsers (reading/CardText.kt) must give the server's answers here too, the Unicode-space case among them.
 *   adb shell am instrument -w -e class com.cardscanner.CardTextParityTest com.cardscanner.debug.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class CardTextParityTest {
    private val cases by lazy {
        JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("parity/ocr_parser_reference.json").use { String(it.readBytes()) }).getJSONArray("cases").let { a -> List(a.length()) { a.getJSONObject(it) } }
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
