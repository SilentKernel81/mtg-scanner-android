package com.cardscanner

import com.cardscanner.reading.Decide
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision (Decide.kt) passes the test files the scanner server's Python code passes:
 *  - reading_decision_cases.json: HAND-written, the answer a person working rule A2 out by hand expects;
 *  - reading_regression_cases.json: CAPTURED from the server's lookup on real pictures (ids and similarities only);
 *  - reading_finish_cases.json: HAND-written, the finish as evidence;
 *  - reading_guard_cases.json: HAND-written, the guards around a decision (acceptPrinting, confirmByOcr).
 * They are COPIES made by reading_reference.py from the server's tests/data - never edited here.
 */
class ReadingDecideParityTest {
    private fun load(name: String) = JSONObject(javaClass.getResource("/$name")!!.readText())

    private fun candidates(picture: JSONObject): List<Decide.Candidate> {
        val list = picture.getJSONArray("candidates")
        return List(list.length()) {
            val c = list.getJSONObject(it)
            Decide.Candidate(
                id = c.getString("id"),
                similarity = c.getDouble("similarity"),
                set = if (c.isNull("set")) null else c.getString("set"),
                number = if (c.isNull("number")) null else c.getString("number"),
                oracleId = if (c.isNull("oracle_id")) null else c.getString("oracle_id"),
                finishes = c.optJSONArray("finishes").let { f -> if (f == null) emptyList() else List(f.length()) { i -> f.getString(i) } },
            )
        }
    }

    private fun strip(json: JSONObject) = Decide.Strip(
        number = json.optString("number", ""),
        set = json.optString("set", ""),
    )

    @Test
    fun theHandWrittenCasesGiveTheAnswerWrittenForThem() {
        val cases = load("reading_decision_cases.json").getJSONArray("cases")
        assertTrue("the case file is missing or empty", cases.length() >= 20)
        val paths = HashSet<String>()
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val thresholds = case.optJSONObject("thresholds")
            val decision = Decide.printing(
                candidates(case.getJSONObject("picture")), strip(case.getJSONObject("strip")),
                agreeBelow = thresholds?.getDouble("agree_below") ?: Decide.AGREE_BELOW,
                imageLead = thresholds?.getDouble("image_lead") ?: Decide.IMAGE_LEAD,
            )
            val expected = case.getJSONObject("expected")
            val message = "${case.getString("name")}: ${case.getString("why")}"
            assertEquals(message, expected.getString("path"), decision.path)
            assertEquals(message, if (expected.isNull("printing_id")) null else expected.getString("printing_id"), decision.printingId)
            if (expected.isNull("lead")) assertNull(message, decision.lead) else assertEquals(message, expected.getDouble("lead"), decision.lead!!, 1e-12)
            paths += decision.path
        }
        assertEquals(setOf("strong", "image", "review"), paths)
    }

    @Test
    fun theCasesCapturedFromRealPicturesGiveTheAnswerThePreviousImplementationGave() {
        val cases = load("reading_regression_cases.json").getJSONArray("cases")
        assertTrue("the case file is missing or too small", cases.length() >= 100)
        val paths = HashSet<String>()
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val decision = Decide.printing(candidates(case.getJSONObject("picture")), strip(case.getJSONObject("strip")))
            val expected = case.getJSONObject("expected")
            val message = case.getString("name")
            assertEquals(message, expected.getString("path"), decision.path)
            assertEquals(message, if (expected.isNull("printing_id")) null else expected.getString("printing_id"), decision.printingId)
            // The server logged these rounded to four places: compare within half of the last one, not by rounding again
            // (a tie rounds differently in Python and Kotlin)
            for (key in listOf("similarity" to decision.similarity, "lead" to decision.lead)) {
                if (expected.isNull(key.first)) assertNull("$message ${key.first}", key.second)
                else assertEquals("$message ${key.first}", expected.getDouble(key.first), key.second!!, 5.0001e-5)
            }
            paths += decision.path
        }
        assertEquals(setOf("strong", "image", "review"), paths)
    }

    @Test
    fun theFinishCasesGiveTheirAnswers() {
        val cases = load("reading_finish_cases.json").getJSONArray("cases")
        assertTrue("the case file is missing or too small", cases.length() >= 15)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val finishes = case.getJSONArray("finishes").let { f -> List(f.length()) { n -> f.getString(n) } }
            val net = if (case.isNull("net")) null else case.getDouble("net")
            val sure = case.optJSONObject("thresholds")?.getDouble("sure") ?: Decide.FINISH_SURE
            val got = Decide.finish(finishes, case.getString("symbol"), net, sure)
            val expected = case.getJSONObject("expected")
            assertEquals(case.getString("name"), Decide.FinishDecision(expected.getString("finish"), expected.getString("source")), got)
        }
    }

    @Test
    fun plainNumberDropsLeadingZerosAndKeepsZero() {
        val cases = listOf("0172" to "172", "172" to "172", "0" to "0", "00" to "0", "" to "0", null to "0", " 7 " to "7", "12a" to "12a",
            "IFIYW-2" to "IFIYW-2", "007b" to "7b")
        for ((given, want) in cases) assertEquals(given ?: "null", want, Decide.plainNumber(given))
    }

    @Test
    fun theThresholdsAreTheMeasuredOnes() {
        // the server's exit criteria were written against rule A2 with these numbers
        assertEquals(0.02, Decide.AGREE_BELOW, 0.0)
        assertEquals(0.05, Decide.IMAGE_LEAD, 0.0)
        assertEquals(0.9, Decide.FINISH_SURE, 0.0)
    }

    @Test
    fun aMissingStripOrNoCandidatesIsAReviewNotAnError() {
        assertEquals("review", Decide.printing(emptyList(), Decide.Strip("1", "tst")).path)
        val one = listOf(Decide.Candidate("a", 0.9, "tst", "1"))
        assertEquals("review", Decide.printing(one, Decide.Strip()).path)             // one candidate, nothing read: no lead can be measured
        assertEquals("strong", Decide.printing(one, Decide.Strip("1", "tst")).path)
    }

    @Test
    fun theSetIsComparedInTheInvariantLocale() {
        // a database set code in capitals, the strip's in lower case: the same printing - whatever the phone's language
        val upper = listOf(Decide.Candidate("a", 0.9, "ISK", "1"))
        assertEquals("strong", Decide.printing(upper, Decide.Strip("1", "isk")).path)
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"))             // Turkish: "I".lowercase() would give a dotless i
            assertEquals("strong", Decide.printing(upper, Decide.Strip("1", "isk")).path)
        } finally {
            java.util.Locale.setDefault(previous)
        }
        // and the strip's own set is not lower-cased: an upper-case "TST" names nothing
        val lower = listOf(Decide.Candidate("a", 0.875, "tst", "1"), Decide.Candidate("b", 0.375, "tst", "2"))
        assertEquals("image", Decide.printing(lower, Decide.Strip("1", "TST")).path)
    }

    private fun optString(json: JSONObject, key: String): String? = if (json.isNull(key)) null else json.getString(key)

    @Test
    fun theGuardCasesGiveTheirAnswers() {
        val cases = load("reading_guard_cases.json").getJSONArray("cases")
        assertTrue("the case file is missing or too small", cases.length() >= 20)
        val refused = HashSet<String>()
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val expected = case.getJSONObject("expected")
            val got = if (case.getString("kind") == "accept") {
                val d = case.getJSONObject("decision")
                val f = case.getJSONObject("facts")
                Decide.acceptPrinting(
                    Decide.PrintingDecision(d.getString("path"), optString(d, "printing_id"),
                        if (d.isNull("similarity")) null else d.getDouble("similarity"), if (d.isNull("lead")) null else d.getDouble("lead")),
                    Decide.Facts(f.getBoolean("accept_image_alone"), if (f.isNull("index_covers")) null else f.getBoolean("index_covers"),
                        f.getBoolean("in_data"), optString(f, "strip_names"), f.getBoolean("ocr_on")),
                )
            } else {
                val o = case.optJSONObject("ocr")
                Decide.confirmByOcr(o?.let { Decide.OcrFacts(it.getBoolean("name_matches"), optString(it, "line_names"), it.optBoolean("name_of_other_line", false), optString(it, "strip_names")) },
                    if (case.isNull("lead")) null else case.getDouble("lead"))
            }
            val want = Decide.Acceptance(expected.getBoolean("accepted"), optString(expected, "refused"), expected.optBoolean("needs_ocr", false))
            assertEquals(case.getString("name"), want, got)
            want.refused?.let { refused += it }
        }
        assertEquals(Decide.GUARDS.toSet(), refused)                  // every guard has a case
    }

    @Test
    fun theGuardFloorsAreTheServers() {
        assertEquals(0.5, Decide.IMAGE_MIN_SIMILARITY, 0.0)
        assertEquals(0.30, Decide.STRONG_MIN_SIMILARITY, 0.0)
        assertEquals(0.10, Decide.LEAD_WAIVES_NAME, 0.0)
    }
}
