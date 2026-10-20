package com.cardscanner.reading

/**
 * The decision about a printing, as a pure function of plain data - a port of core/reading/decide.py
 * in the scanner server's repository (docs/plans/READING_REFACTOR_PLAN.md there).
 *
 * Nothing in here touches a database, a picture, the network or the clock: whoever GATHERS the
 * evidence (the printings the picture looks like, with their set / number / finishes, and what the
 * collector line says) does every look-up and hands over plain values. That is what lets this file
 * pass the very test files the Python code passes (reading_decision_cases.json,
 * reading_finish_cases.json, reading_regression_cases.json - copies made by reading_reference.py).
 *
 * NOT USED BY THE APP YET. Nothing calls it: the phone reads cards with the vision AI (standalone) or
 * sends them to the server (client mode). Wiring it in needs the image index on the phone, and the
 * guards the server puts around a decision by the picture alone are NOT in this file either - they
 * belong to the code that gathers the evidence, and must come along when it is written:
 *   - the best match must be at least 0.5 similar (PrintingCheck.IMAGE_MIN_SIMILARITY),
 *   - the collector line must not name a printing the card data has and the index lacks
 *     (PrintingCheck._names_unindexed),
 *   - a card only the picture decided is NOT added unless the person turned that on
 *     (reading.accept_image_alone, off by default).
 * Without them a printing newer than the index is added as another printing (36% of the pictures
 * whose printing was not indexed, in the server's simulation).
 */
object Decide {
    /** A printing counts as "looking like the picture as much as the best match" when it is less than this below it */
    const val AGREE_BELOW = 0.02

    /** The picture alone is believed when its best match leads the second by at least this */
    const val IMAGE_LEAD = 0.05

    /** How sure the foil network must be, one way or the other, to be believed */
    const val FINISH_SURE = 0.9

    /** One of the printings the picture looks like (nearest first in a list): what the card data says of it */
    data class Candidate(
        val id: String,
        /** -1..1, 1 = the same fingerprint (Fingerprint.similarity); at full precision, never rounded */
        val similarity: Double,
        val set: String?,
        val number: String?,
        val oracleId: String? = null,
        /** "nonfoil", "foil", "etched" - what the printing exists in */
        val finishes: List<String> = emptyList(),
    )

    /** What the bottom-left strip says. Empty strings: not read. [set] is expected in lower case, as the parse of the strip writes it. */
    data class Strip(
        val number: String = "",
        val set: String = "",
        /** The foil symbol the OCR read in the set line: "foil", "non-foil" or "unknown" */
        val symbol: String = "unknown",
        /** The foil network's probability of a foil, when it was asked (the OCR read no symbol); null otherwise */
        val net: Double? = null,
    )

    /** [path] is "strong", "image" or "review"; [printingId], [similarity] are null for a review; [lead] is null with fewer than two candidates */
    data class PrintingDecision(val path: String, val printingId: String?, val similarity: Double?, val lead: Double?)

    /** [finish] is "foil", "regular" or "unknown" (an unread marker is NOT regular); [source] is "printing", "symbol", "network" or "none" */
    data class FinishDecision(val finish: String, val source: String)

    /**
     * A collector number as the strip's parse writes it: no leading zeros ("0172" -> "172"), and "0"
     * stays "0" for a card numbered 0. (Python: str(number or '').strip().lstrip('0') or '0'.)
     */
    fun plainNumber(number: String?): String {
        val trimmed = (number ?: "").trim().trimStart('0')
        return trimmed.ifEmpty { "0" }
    }

    /**
     * Rule A2 of the server's experiment (docs/plans/IMAGE_MATCH_EXPERIMENT.md there), exactly as it was measured:
     *  - "strong": the strip names a printing (its number AND its set) that is one of the candidates - the first such
     *    one, nearest first - and that looks as much like the picture as the best match does (less than [agreeBelow]
     *    under it): a reprint with the same art has almost the same fingerprint, so the strip decides among those.
     *    If the first candidate the strip names looks DIFFERENT from the best match, the strip is not believed (a Secret
     *    Lair card prints another printing's set and number) and the search for it stops there;
     *  - "image": else the best match, when it leads the second by at least [imageLead];
     *  - "review": else - the evidence does not decide.
     * Equal to a threshold counts as the larger side for the lead (>=) and as not agreeing for the gap (< is the test).
     * Candidates must be nearest first, as the index gives them. Arithmetic is in doubles, in the order the Python
     * code does it, so the comparisons agree to the last bit.
     */
    fun printing(
        candidates: List<Candidate>,
        strip: Strip,
        agreeBelow: Double = AGREE_BELOW,
        imageLead: Double = IMAGE_LEAD,
    ): PrintingDecision {
        if (candidates.isEmpty()) return PrintingDecision("review", null, null, null)
        val best = candidates[0].similarity
        val lead = if (candidates.size > 1) best - candidates[1].similarity else null
        if (strip.number.isNotEmpty() && strip.set.isNotEmpty()) {
            for (candidate in candidates) {                      // nearest first: the first that names the strip's printing
                // lowercase(): the invariant locale, never the phone's own (a Turkish "I" must not become a dotless i)
                if (plainNumber(candidate.number) == strip.number && (candidate.set ?: "").lowercase() == strip.set) {
                    if (best - candidate.similarity < agreeBelow) {     // it looks as much like the picture as the best match does
                        return PrintingDecision("strong", candidate.id, candidate.similarity, lead)
                    }
                    break                                           // one that names it but looks different: the strip is not believed
                }
            }
        }
        if (lead != null && lead >= imageLead) return PrintingDecision("image", candidates[0].id, best, lead)
        return PrintingDecision("review", null, null, lead)
    }

    /**
     * The finish of a card whose printing is known, in this order:
     *  1. a printing that exists in one finish only is certain: "foil" (foil / etched only) or "regular" (non-foil only);
     *  2. the symbol the OCR read in the set line;
     *  3. the foil network, when it is sure either way (>= [sure] for a foil, <= 1 - [sure] for a regular card);
     *  4. otherwise "unknown", source "none" - NOT regular: an unread marker says nothing.
     * A surge foil is "foil" here: the kind of foil is not decided.
     */
    fun finish(
        finishes: List<String>?,
        symbol: String = "unknown",
        net: Double? = null,
        sure: Double = FINISH_SURE,
    ): FinishDecision {
        val known = finishes ?: emptyList()
        val hasFoil = "foil" in known || "etched" in known
        val hasNonfoil = "nonfoil" in known
        if (hasFoil && !hasNonfoil) return FinishDecision("foil", "printing")
        if (hasNonfoil && !hasFoil) return FinishDecision("regular", "printing")
        if (symbol == "foil") return FinishDecision("foil", "symbol")
        if (symbol == "non-foil") return FinishDecision("regular", "symbol")
        if (net != null) {
            if (net >= sure) return FinishDecision("foil", "network")
            if (net <= 1 - sure) return FinishDecision("regular", "network")     // 1 - 0.9 is 0.09999999999999998: as in Python
        }
        return FinishDecision("unknown", "none")
    }
}
