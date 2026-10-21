package com.cardscanner.reading

/**
 * What the OCR's lines say about a Magic card: ports of the scanner server's parsers, exactly - core/card_ocr.py (parse_magic, parse_strip, MAGIC_NUMBER,
 * MAGIC_NUMBER_OF_TOTAL, MAGIC_SET, DIGIT_LOOKALIKES, prints_token_letter, token_set_code, STARS / DOTS) and core/foil_marker.py (set_line, set_line_box,
 * symbol_read). The JVM test CardTextTest gives them light-ocr's real lines of 30 pictures and hand-written ones, and they must answer as the Python does
 * (app/src/test/resources/ocr_reference.py -> ocr_parser_reference.json).
 *
 * Python's `re` and Kotlin's Regex differ in two places that matter here, both handled:
 *  - in Python 3, `\s`, `\d` and `\b` on a str are Unicode-aware (a no-break space is a space). On a phone (ICU) they are too, on a desktop JVM they are
 *    ASCII - and the flag that would change that, (?U), is REFUSED by Android's regex engine (a Codex review of 2026-10-21: the parsers would not even
 *    start on a phone, while the JVM tests passed). So no pattern here uses `\s`, `\d` or `\b`: they are spelled out as Python's classes ([WS],
 *    `\p{Nd}`, [WORD_END]), which both engines read alike - CardTextTest runs the cases on the JVM, CardTextParityTest the same cases on the phone;
 *  - `pattern.match(text)` anchors at the START of the text (not its end): Regex.matchAt(text, 0).
 * The parser is strict on purpose (the server's CLAUDE.md: a looser collector-number pattern once confirmed the wrong printing): never loosen it here alone.
 */
object CardText {
    /** One OCR line, as the server's reader answers it: its text, confidence and box (four corners, clockwise from the top-left, in the picture's pixels) */
    data class Line(val text: String, val confidence: Double, val box: List<TextMath.Point>)

    /** parse_magic's answer: name, collector number (as printed: "0172"), set code (upper case, as printed; a token's "T" + it), foil ("foil" / "non-foil" / "unknown") */
    data class MagicRead(val name: String, val collectorNumber: String, val setCode: String, val foil: String)

    /**
     * The characters Python's `\s` matches on a str (str.isspace()), inside a character class: the ASCII control spaces, \x1c-\x1f, \x85 and every
     * Unicode space separator (\p{Z}: the no-break space, U+1680, U+2000-200A, U+2028/2029, U+202F, U+205F, U+3000)
     */
    private const val SPACES = """\t\n\x0B\f\r\x1C-\x1F\x85\p{Z}"""

    /** Python's `\s` */
    private const val WS = "[$SPACES]"

    /** Python's `\b` after a word: not followed by a word character (Python's \w on a str: a letter, a number, or "_") */
    private const val WORD_END = """(?![\p{L}\p{Nd}\p{Nl}\p{No}_])"""

    /** card_ocr.LANGUAGES: the language codes printed after the set code ("HOB • EN") */
    private const val LANGUAGES = "EN|DE|FR|IT|ES|SP|PT|JP|JA|KO|KR|RU|CS|CT|ZHS|ZHT|PH"

    /** card_ocr.MAGIC_NUMBER: "U 0172", "M0128", "M.0010", "P 0003 Play Promo" - a rarity letter then 3-4 digits (or their look-alikes), or 4 digits alone */
    val MAGIC_NUMBER = Regex("""(?:[A-Z][. ]?$WS*([0-9OoDBIlSZ]{3,4}[a-z]?)|([0-9OoDBIlSZ]{4}[a-z]?))(?=$WS|$)""")

    /** card_ocr.MAGIC_NUMBER_OF_TOTAL: older cards' "123/281 U" (a set total of three digits; Python's \d: \p{Nd}) */
    val MAGIC_NUMBER_OF_TOTAL = Regex("""(\p{Nd}{1,3})$WS*/$WS*\p{Nd}{3}(?!\p{Nd})""")

    /** card_ocr.MAGIC_SET: "HOB·EN", "HOB★EN", "SPM *EN", "HOBEN", "SPM·ENLADRAWS" - the set code, then a marker and a language, or a language alone */
    val MAGIC_SET = Regex("""([A-Z0-9]{3,4})$WS*(?:([^A-Za-z0-9$SPACES])$WS*(?:$LANGUAGES)|(?:$LANGUAGES)$WORD_END)""")

    /** card_ocr.DIGIT_LOOKALIKES: str.maketrans('OoDBIlSZ', '00081152') */
    private val LOOKALIKES = mapOf('O' to '0', 'o' to '0', 'D' to '0', 'B' to '8', 'I' to '1', 'l' to '1', 'S' to '5', 'Z' to '2')

    /** card_ocr.STARS, DOTS: the characters read for a foil's star and a regular card's dot */
    private const val STARS = "*★☆"
    private const val DOTS = "·•."

    /** Python's str.strip(): Unicode whitespace off both ends (Kotlin's trim() is the same rule: Char.isWhitespace) */
    private fun strip(text: String) = text.trim()

    private fun digits(text: String) = text.count { it.isDigit() }

    /** card_ocr.prints_token_letter: a MAGIC_NUMBER match of the LETTER alternative whose letter is "T" - a token ("T 0001") */
    private fun printsTokenLetter(match: MatchResult?) = match != null && match.groups[1] != null && match.value.trimStart().startsWith("T")

    /** card_ocr.token_set_code: "t" + the code, in the code's own case ("TFRA", the strip's "tfra") */
    private fun tokenSetCode(setCode: String) = (if (setCode.firstOrNull()?.isLowerCase() == true) "t" else "T") + setCode

    private fun left(line: Line, width: Int) = line.box[0].x / width
    private fun top(line: Line, height: Int) = line.box[0].y / height

    /** The collector number a line starts with (MAGIC_NUMBER with at least two real digits, the look-alikes translated), and whether it is a token's - or null */
    private fun numberOf(text: String): Pair<String, Boolean>? {
        val match = MAGIC_NUMBER.matchAt(text, 0)
        val digits = match?.let { it.groups[1]?.value ?: it.groups[2]?.value }
        if (digits != null && digits(digits) >= 2) return String(digits.map { LOOKALIKES[it] ?: it }.toCharArray()) to printsTokenLetter(match)
        return null
    }

    /**
     * card_ocr.parse_magic: the name, collector number, set code and foil marker of a card from its OCR lines ([width] x [height]: the picture's size), or
     * null when no name was found. Name: the first line in the title bar (top < 16%, left < 35%, three letters in a row), stray mana symbols cut off its end;
     * then, from the top down, the bottom-left lines (top > 84%, left < 60%): the first that reads as the collector number, then the first that reads as
     * "SET . LANGUAGE" - its marker counts only when a star or a dot was READ
     */
    fun parseMagic(lines: List<Line>, width: Int, height: Int): MagicRead? {
        val title = lines.filter { top(it, height) < 0.16 && left(it, width) < 0.35 && Regex("[A-Za-z]{3}").containsMatchIn(it.text) }
        if (title.isEmpty()) return null
        // min(title, key=top): the first of the topmost
        val first = title.minByOrNull { top(it, height) }!!
        val name = strip(first.text.replace(Regex("""[^A-Za-zÀ-ÿ0-9!?'’".)]+$"""), ""))
        if (name.isEmpty()) return null
        var number = ""
        var setCode = ""
        var foil = "unknown"
        var token = false
        for (line in lines.filter { top(it, height) > 0.84 && left(it, width) < 0.6 }.sortedBy { top(it, height) }) {
            val text = strip(line.text)
            if (number.isEmpty()) {
                val read = numberOf(text)
                if (read != null) { number = read.first; token = read.second; continue }
                val total = MAGIC_NUMBER_OF_TOTAL.matchAt(text, 0)
                if (total != null) { number = total.groupValues[1]; continue }
            }
            if (setCode.isEmpty()) {
                val match = MAGIC_SET.matchAt(text, 0)
                if (match != null) {
                    setCode = match.groupValues[1]
                    val marker = match.groups[2]?.value ?: ""
                    foil = when {
                        marker.isNotEmpty() && marker in STARS -> "foil"
                        marker.isNotEmpty() && marker in DOTS -> "non-foil"
                        else -> "unknown"
                    }
                }
            }
        }
        if (token && setCode.isNotEmpty()) setCode = tokenSetCode(setCode)
        return MagicRead(name, number, setCode, foil)
    }

    /**
     * card_ocr.parse_strip: (number, set code) out of the lines of the bottom-left STRIP of a flat card (no position filter: only the two lines are in it) -
     * the number without leading zeros, the set code lower case (a token's "t" + it); "" for what was not read
     */
    fun parseStrip(lines: List<Line>): Pair<String, String> {
        var number = ""
        var setCode = ""
        var token = false
        for (line in lines.sortedBy { it.box[0].y }) {
            val text = strip(line.text)
            if (number.isEmpty()) {
                val read = numberOf(text)
                if (read != null) { number = read.first; token = read.second; continue }
                val total = MAGIC_NUMBER_OF_TOTAL.matchAt(text, 0)
                if (total != null) { number = total.groupValues[1]; continue }
            }
            if (setCode.isEmpty()) {
                val match = MAGIC_SET.matchAt(text, 0)
                if (match != null) setCode = match.groupValues[1]
            }
        }
        setCode = setCode.lowercase()       // Python's lower(): the code is ASCII letters and digits, the locale cannot matter (lowercase() is the invariant one)
        if (token && setCode.isNotEmpty()) setCode = tokenSetCode(setCode)
        return number.trimStart('0') to setCode
    }

    /**
     * foil_marker.set_line: the line the set code is on, or null - from the top down, the first line that reads as the collector number is skipped, and the
     * first line after it (or before it, when none matched yet) that reads as "SET . LANGUAGE" is it
     */
    fun setLine(lines: List<Line>): Line? {
        var numberSeen = false
        for (line in lines.sortedBy { it.box[0].y }) {
            val text = strip(line.text)
            if (!numberSeen && (numberOf(text) != null || MAGIC_NUMBER_OF_TOTAL.matchAt(text, 0) != null)) {
                numberSeen = true
                continue
            }
            if (MAGIC_SET.matchAt(text, 0) != null) return line
        }
        return null
    }

    /**
     * The collector numbers to look a collector line's number up by, group after group (printing_check.printing_named_by_line): a number with a LETTER
     * SUFFIX ("233a", "0245p") first as written - leading zeros off, then as read - and only then by its digits (database.collector_number_variants: "0330"
     * may be "330", "0330", "330s", "0330s"), for a suffix the OCR misread. The digits alone took "233a" for "233" - ZEN has both Plains (a Codex review of
     * 2026-10-21). No digit at all: nothing to look up
     */
    fun lineNumbers(number: String): List<List<String>> {
        val read = strip(number)
        val digits = Regex("[0-9]+").find(read)?.value ?: return emptyList()
        val base = digits.trimStart('0').ifEmpty { "0" }
        val variants = listOf(base, digits, base + "s", digits + "s").distinct()
        if (read.all { it in '0'..'9' }) return listOf(variants)
        val plain = read.replace(Regex("^0+(?=[0-9])"), "")
        return listOf(listOf(plain, read).distinct(), variants)
    }

    /** foil_marker.set_line_box: the set line's box, or null */
    fun setLineBox(lines: List<Line>): List<TextMath.Point>? = setLine(lines)?.box

    /** foil_marker.symbol_read: the marker the OCR itself read on a set line - "foil" (a star), "non-foil" (a dot) or "unknown" (none read: says nothing) */
    fun symbolRead(line: Line): String {
        val symbol = MAGIC_SET.matchAt(strip(line.text), 0)?.groups?.get(2)?.value ?: ""
        return when {
            symbol.isNotEmpty() && symbol in STARS -> "foil"
            symbol.isNotEmpty() && symbol in DOTS -> "non-foil"
            else -> "unknown"
        }
    }
}
