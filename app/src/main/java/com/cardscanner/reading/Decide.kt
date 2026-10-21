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
 * sends them to the server (client mode). Wiring it in needs the image index on the phone.
 *
 * The guards the server puts around a decision ARE here (since the server's step 5 of
 * docs/plans/READING_REVIEW_2026-10-21_PLAN.md): [acceptPrinting] and [confirmByOcr], ports of
 * decide.accept_printing / confirm_by_ocr, passing reading_guard_cases.json. What they need - whether the
 * index covers the card data, what the strip's line names, what the full-card OCR read - must still be
 * GATHERED by the code that will call them (the server's PrintingCheck.live_read / ocr_facts): without
 * the guards a printing newer than the index is added as another printing (36% of the pictures whose
 * printing was not indexed, in the server's simulation). On the server the picture alone is allowed by
 * default since 2026-10-20 (reading.accept_image_alone), behind those guards.
 */
object Decide {
    /** A printing counts as "looking like the picture as much as the best match" when it is less than this below it */
    const val AGREE_BELOW = 0.02

    /** The picture alone is believed when its best match leads the second by at least this */
    const val IMAGE_LEAD = 0.05

    /** How sure the foil network must be, one way or the other, to be believed */
    const val FINISH_SURE = 0.9

    /** The guards' floors: a decision by the picture alone, and one the strip confirmed, must look at least this much like their printing */
    const val IMAGE_MIN_SIMILARITY = 0.5
    const val STRONG_MIN_SIMILARITY = 0.30

    /**
     * How far ahead of the second printing the picture's best match must be for the full-card OCR's NAME to be no longer needed (decide.LEAD_WAIVES_NAME on the
     * server, measured there: with every printing of a card missing from the index, the best match never led by more than 0.052 - 0 of 428 added as another card at 0.08)
     */
    const val LEAD_WAIVES_NAME = 0.10

    /** The guards' names, as the server's reading log writes them (decide.GUARDS), in the order they are met */
    const val IMAGE_ALONE_OFF = "image_alone_off"
    const val INDEX_INCOMPLETE = "index_incomplete"
    const val CARD_NOT_IN_DATA = "card_not_in_data"
    const val BELOW_IMAGE_FLOOR = "below_image_floor"
    const val BELOW_STRONG_FLOOR = "below_strong_floor"
    const val STRIP_NAMES_UNINDEXED = "strip_names_unindexed"
    const val STRIP_NAMES_UNKNOWN = "strip_names_unknown"
    const val OCR_DID_NOT_CONFIRM = "ocr_did_not_confirm"
    val GUARDS = listOf(IMAGE_ALONE_OFF, INDEX_INCOMPLETE, CARD_NOT_IN_DATA, BELOW_IMAGE_FLOOR, BELOW_STRONG_FLOOR, STRIP_NAMES_UNINDEXED,
        STRIP_NAMES_UNKNOWN, OCR_DID_NOT_CONFIRM)

    /**
     * What the caller gathered about a decision, for [acceptPrinting]. [indexCovers]: the index has every printing of the card data (null: cannot be said - not a yes).
     * [stripNames]: what the strip's whole line names - null (no whole line), "chosen", "indexed_other", "unindexed" (the card data has it, the index does not),
     * "unknown" (the card data lacks it), "lookup_failed". [ocrOn]: the full-card OCR can be asked.
     */
    data class Facts(
        val acceptImageAlone: Boolean,
        val indexCovers: Boolean?,
        val inData: Boolean,
        val stripNames: String? = null,
        val ocrOn: Boolean = false,
    )

    /** [needsOcr]: a decision by the picture alone that passed every guard with OCR on - [confirmByOcr] decides it */
    data class Acceptance(val accepted: Boolean, val refused: String?, val needsOcr: Boolean = false)

    /**
     * What the full-card OCR read about the printing the picture alone chose: [nameMatches] (false when it read nothing), [lineNames] - what its whole collector line
     * names: null, "chosen", "other_card" (another card's printing: no evidence), "same_card" (another printing of the same card), "unknown", "lookup_failed";
     * [nameOfOtherLine]: the name read is not the chosen card's but IS the card a whole collector line names (the OCR's own line or the strip's - gathered by the caller);
     * [stripNames]: what the STRIP's whole line names, the same values as [lineNames] (also gathered when the OCR read nothing)
     */
    data class OcrFacts(val nameMatches: Boolean, val lineNames: String? = null, val nameOfOtherLine: Boolean = false, val stripNames: String? = null)

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

    /**
     * May the decision ([printing]'s answer) be added on the evidence of the picture and the strip? decide.accept_printing, in the same order:
     *  1. "review" is no decision (not accepted, refused null);
     *  2. the picture alone needs [Facts.acceptImageAlone] (else image_alone_off) and an index that covers the card data (else index_incomplete);
     *  3. the printing must be in the card data (card_not_in_data);
     *  4. its similarity must reach its path's floor (below_image_floor / below_strong_floor; equality passes);
     *  5. the picture alone: a strip naming a printing the index lacks, or one that could not be looked up -> strip_names_unindexed; one the card data lacks -> strip_names_unknown.
     * A decision by the picture alone that passes, with OCR on, is not accepted yet: needsOcr, and [confirmByOcr] decides.
     */
    fun acceptPrinting(
        decision: PrintingDecision,
        facts: Facts,
        imageMinSimilarity: Double = IMAGE_MIN_SIMILARITY,
        strongMinSimilarity: Double = STRONG_MIN_SIMILARITY,
    ): Acceptance {
        if (decision.path != "strong" && decision.path != "image") return Acceptance(false, null)
        val alone = decision.path == "image"
        if (alone && !facts.acceptImageAlone) return Acceptance(false, IMAGE_ALONE_OFF)
        if (alone && facts.indexCovers != true) return Acceptance(false, INDEX_INCOMPLETE)
        if (!facts.inData) return Acceptance(false, CARD_NOT_IN_DATA)
        val similarity = decision.similarity ?: return Acceptance(false, if (alone) BELOW_IMAGE_FLOOR else BELOW_STRONG_FLOOR)
        if (similarity < (if (alone) imageMinSimilarity else strongMinSimilarity)) {
            return Acceptance(false, if (alone) BELOW_IMAGE_FLOOR else BELOW_STRONG_FLOOR)
        }
        if (alone) {
            when (facts.stripNames) {
                "unindexed", "lookup_failed" -> return Acceptance(false, STRIP_NAMES_UNINDEXED)
                "unknown" -> return Acceptance(false, STRIP_NAMES_UNKNOWN)
            }
        }
        val needsOcr = alone && facts.ocrOn
        return Acceptance(!needsOcr, null, needsOcr)
    }

    /**
     * decide.confirm_by_ocr. The name must match - unless the picture leads the second printing by at least [leadWaivesName] ([lead]: the decision's lead, null when
     * not given), or the whole collector line read names EXACTLY the chosen printing ("chosen": the picture and the line agree on it). Either way a line naming another
     * printing of the same card, an unknown one or one that cannot be looked up refuses: the lead says which card, never which printing. And a name that is ANOTHER
     * card's which a whole line names too ([OcrFacts.nameOfOtherLine]) refuses whatever the lead: two readings agree on a different card; and so does a strip naming
     * another printing of the same card ([OcrFacts.stripNames] "same_card"), whatever the name and the lead. [ocr] null: nothing read.
     */
    fun confirmByOcr(ocr: OcrFacts?, lead: Double? = null, leadWaivesName: Double = LEAD_WAIVES_NAME): Acceptance {
        val line = ocr?.lineNames
        if (ocr?.stripNames == "same_card") return Acceptance(false, OCR_DID_NOT_CONFIRM)
        if (ocr != null && !ocr.nameMatches && ocr.nameOfOtherLine) return Acceptance(false, OCR_DID_NOT_CONFIRM)
        val nameOk = (ocr?.nameMatches == true) || (lead != null && lead >= leadWaivesName) || line == "chosen"
        if (nameOk && line in listOf(null, "chosen", "other_card")) return Acceptance(true, null)
        return Acceptance(false, OCR_DID_NOT_CONFIRM)
    }
}
