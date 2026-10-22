package com.cardscanner.reading

import org.opencv.core.Core
import org.opencv.core.Mat

/**
 * The reading of a card by its picture alone - the server's `PrintingCheck.live_read` with NO OCR reader (its "OCR off" configuration: the phone has no OCR,
 * so there is no strip, never a "strong" decision, and the full-card OCR's confirmation `Decide.confirmByOcr` is never asked). The GATHERING half; the
 * deciding half is `Decide` (rule A2 and the guards), unchanged and pinned by the server's case files.
 *
 * Of the flat card (the picture the server fingerprints too): its fingerprint as given and turned 180 degrees, the distances to every printing of the
 * index both ways, the better orientation, its five nearest rows as candidates -> `Decide.printing` with an empty strip -> `Decide.acceptPrinting`. A card
 * it accepts is the printing of that id - the caller takes it from its card data BY ID, never looked up again by name / number / set (on the server 14% of
 * printings do not come back as themselves from such a lookup: promos, "CMR-77", "245p").
 *
 * The same answers as the server's code on the same pictures and index: PictureReaderParityTest (app/src/test/resources/picture_reference.py makes
 * its reference).
 */
class PictureReader(private val index: ImageIndex) {

    /**
     * What the search found: which way up the card is, the nearest distance (bits) of each orientation, the nearest rows of the better one - and that
     * orientation's distances to EVERY row (what [markerExpected] judges by, never by the five candidates alone)
     */
    class Search(val rotated: Boolean, val nearestAsGiven: Int, val nearestTurned: Int, val candidates: List<Decide.Candidate>, val distances: IntArray)

    /**
     * One card's reading: the search, rule A2's decision, the guards' answer and - when accepted - its finish (Decide.finish: the printing's own when it
     * exists in one finish only, else the foil network's at the fixed place when it was asked and sure, else "unknown"); [net]: the network's probability
     * of a foil when it was asked (null: not asked - see [read])
     */
    data class Reading(val search: Search, val decision: Decide.PrintingDecision, val acceptance: Decide.Acceptance, val finish: Decide.FinishDecision?,
                       val net: Double? = null, val strip: Decide.Strip? = null, val stripNames: String? = null, val full: Decide.OcrFacts? = null) {
        /** The printing to add, or null (not accepted: the caller goes on as it did before - the AI, or review) */
        val printingId get() = decision.printingId.takeIf { acceptance.accepted }
    }

    /** [card]: the flat card, RGB (or RGBA), filling the picture */
    fun search(card: Mat): Search {
        val turnedPicture = Mat()
        try {
            Core.rotate(card, turnedPicture, Core.ROTATE_180)
            val asGiven = index.distances(Fingerprint.hash(card))
            val turned = index.distances(Fingerprint.hash(turnedPicture))
            val nearestAsGiven = asGiven.min()
            val nearestTurned = turned.min()
            val rotated = nearestTurned < nearestAsGiven                 // the card was put in upside down (equal: as given - the server's `<`)
            val distances = if (rotated) turned else asGiven
            val candidates = nearest(distances, CANDIDATES).map { row ->
                val id = index.ids[row]
                val facts = index.facts[id]
                Decide.Candidate(id = id, similarity = Fingerprint.similarity(distances[row]), set = facts?.set, number = facts?.number,
                    oracleId = facts?.oracleId, finishes = facts?.finishes ?: emptyList())
            }
            return Search(rotated, nearestAsGiven, nearestTurned, candidates, distances)
        } finally {
            turnedPicture.release()
        }
    }

    /**
     * The card's reading. [acceptImageAlone]: the setting (Read by the picture first); [indexCovers]: the index has every printing of the phone's card data
     * (null: cannot be said - not a yes); [inData]: whether the phone's card data has a printing (by id).
     */
    /** What the card data has at the strip's set and number (the caller looks it up: the phone's CardDatabase): a printing's id, none, or a failed lookup */
    sealed interface StripPrinting {
        data class Found(val id: String) : StripPrinting
        data object NotFound : StripPrinting
        data object Failed : StripPrinting
    }

    /**
     * [textReader]: the OCR reader - given, the strip of the card turned upright is READ (the server's live_read with its OCR reader): its collector line
     * goes to rule A2 (a "strong" decision: the line names the printing the picture agrees with), the star / dot the OCR read gives the finish, the foil
     * network only where it read none - cut at the OCR's own box of the set line (read_strip_evidence). [stripPrinting]: what the card data has at the
     * strip's set and number (strip_names - the guards: a line naming a printing the index lacks or the card data lacks refuses the picture alone).
     * With OCR, a decision by the picture alone needs the full-card OCR's confirmation (the server's confirm_by_ocr): the card is read whole and must
     * agree ([ocrFacts]); [describe] gives a printing's name and flavor name (the phone's card data) for the name checks. Null: no OCR, as before.
     */
    fun read(card: Mat, acceptImageAlone: Boolean, indexCovers: Boolean?, inData: (String) -> Boolean, foilNet: FoilNet? = null,
             textReader: TextReader? = null, stripPrinting: ((set: String, number: String) -> StripPrinting)? = null,
             describe: ((id: String) -> Described?)? = null): Reading {
        val search = search(card)
        val strip = textReader?.let { readStrip(card, search.rotated, it, foilNet) }
        val decision = Decide.printing(search.candidates, strip ?: Decide.Strip())
        val chosen = decision.printingId
        val inDataNow = chosen != null && index.facts.containsKey(chosen) && inData(chosen)
        // live_read: what the strip's whole line names is looked up only where a guard asks it - the picture alone, allowed, a printing in the card data
        val stripNames = if (strip != null && decision.path == "image" && acceptImageAlone && inDataNow) stripNames(strip, chosen!!, stripPrinting) else null
        val facts = Decide.Facts(acceptImageAlone = acceptImageAlone, indexCovers = indexCovers, inData = inDataNow, stripNames = stripNames, ocrOn = textReader != null)
        var acceptance = Decide.acceptPrinting(decision, facts)
        // With OCR, a decision by the picture alone is accepted only once the FULL-CARD OCR confirms it (needs_ocr -> Identification._ocr_names on the
        // server): the card read whole as given (CardText.parseMagic), the facts gathered here (ocrFacts: printing_check.ocr_facts) and Decide.confirmByOcr
        // with the decision's lead - the name must match (or the lead reach LEAD_WAIVES_NAME), a line - the OCR's own or the strip's - naming another
        // printing of the same card refuses, and a name that is another card's which a line names too refuses (two Codex reviews of 2026-10-21)
        var full: Decide.OcrFacts? = null
        if (acceptance.needsOcr && textReader != null && chosen != null) {
            val read = CardText.parseMagic(textReader.read(card), card.cols(), card.rows())
            full = ocrFacts(read, chosen, strip, stripPrinting, describe)
            acceptance = Decide.confirmByOcr(full, decision.lead)
        }
        if (!acceptance.accepted || chosen == null) return Reading(search, decision, acceptance, null, strip = strip, stripNames = stripNames, full = full)
        if (strip != null) {
            // with OCR: decide_finish on the strip's evidence (the printing's own finishes first, then the star / dot read, then the network at the OCR's box)
            return Reading(search, decision, acceptance, Decide.finish(index.facts[chosen]?.finishes, strip.symbol, strip.net), strip.net, strip, stripNames, full)
        }
        // The finish. The foil network at the fixed place - the server's live_read with no OCR reader: only when the search is sure which way up the card
        // is (the place is the set line on an UPRIGHT card) and every printing the picture cannot tell from its best match prints a marker (an old-frame card
        // feeds the network whatever is there: it answered confidently on 53 of 80 such cards). Only asked where the printing's own finishes leave it open
        val finishes = index.facts[chosen]?.finishes
        val net = if (foilNet != null && Decide.finish(finishes).finish == "unknown" && orientationEstablished(search) && markerExpected(search)) {
            fixedNet(card, search.rotated, foilNet)
        } else null
        return Reading(search, decision, acceptance, Decide.finish(finishes, "unknown", net), net)
    }

    /**
     * printing_check.read_strip_evidence with an OCR reader: ONE read of the bottom-left strip of the card turned upright - its number and set
     * (CardText.parseStrip), the symbol read in the set line (symbolRead), and the foil network's probability where no symbol was read, cut at the OCR's
     * own box of the set line (FoilNet.setLineCrop); nothing read: an empty strip
     */
    private fun readStrip(card: Mat, rotated: Boolean, textReader: TextReader, foilNet: FoilNet?): Decide.Strip {
        val upright = if (rotated) Mat().also { Core.rotate(card, it, Core.ROTATE_180) } else card
        val strip = FoilNet.stripOf(upright)
        try {
            val lines = textReader.read(strip)
            if (lines.isEmpty()) return Decide.Strip()
            val (number, set) = CardText.parseStrip(lines)
            val line = CardText.setLine(lines)
            val symbol = line?.let { CardText.symbolRead(it) } ?: "unknown"
            var net: Double? = null
            if (symbol == "unknown" && foilNet != null && line != null) {
                val crop = FoilNet.setLineCrop(strip, line.box.minOf { it.x }, line.box.minOf { it.y }, line.box.maxOf { it.x }, line.box.maxOf { it.y })
                if (crop != null) try { net = foilNet.probability(crop) } finally { crop.release() }
            }
            return Decide.Strip(number, set, symbol, net)
        } finally {
            strip.release()
            if (upright !== card) upright.release()
        }
    }

    /** What the card data says of a printing for the name checks: its name and flavor name (namesMatch checks both, as database.names_match) */
    data class Described(val name: String, val flavorName: String? = null)

    /**
     * printing_check._line_names: what a whole collector line (a set AND a number) names next to the chosen printing - (null | "chosen" | "same_card" |
     * "other_card" | "unknown" | "lookup_failed", the id it names). "chosen": the chosen printing's own set and number (Decide.plainNumber, case ignored);
     * else the card data's printing there ([stripPrinting], the suffix rule included), the same card by oracle id - or, when either lacks one, by the
     * same name exactly (never namesMatch's forgiveness, which takes Lightning Strike for Lightning Bolt)
     */
    private fun lineNames(set: String?, number: String?, chosen: String, lookup: ((String, String) -> StripPrinting)?, describe: ((String) -> Described?)?): Pair<String?, String?> {
        val n = (number ?: "").trim()
        val s = (set ?: "").trim().lowercase()
        if (n.isEmpty() || s.isEmpty()) return null to null
        val card = index.facts[chosen]
        if (card != null && s == card.set.lowercase() && Decide.plainNumber(n).lowercase() == Decide.plainNumber(card.number).lowercase()) return "chosen" to chosen
        return when (val named = lookup?.invoke(s, n) ?: StripPrinting.Failed) {
            StripPrinting.Failed -> "lookup_failed" to null
            StripPrinting.NotFound -> "unknown" to null
            is StripPrinting.Found -> {
                val a = index.facts[named.id]?.oracleId
                val b = card?.oracleId
                val same = if (a != null && b != null) a == b
                    else (com.cardscanner.scryfall.Scryfall.searchKey(describe?.invoke(named.id)?.name ?: index.facts[named.id]?.name) ?: "") ==
                        (com.cardscanner.scryfall.Scryfall.searchKey(describe?.invoke(chosen)?.name ?: card?.name) ?: "-")
                (if (same) "same_card" else "other_card") to named.id
            }
        }
    }

    /**
     * printing_check.ocr_facts: what the full-card OCR's read says about the printing the picture alone chose, as Decide.confirmByOcr takes it - the name
     * matches the chosen card (namesMatch: a face, the flavor name, a misread forgiven), what the read's own line names, what the strip's line names (looked
     * up whatever was read), and whether a name that is not the chosen card's IS the card a line names (the OCR's or the strip's)
     */
    fun ocrFacts(read: CardText.MagicRead?, chosen: String, strip: Decide.Strip?, lookup: ((String, String) -> StripPrinting)?,
                 describe: ((String) -> Described?)?): Decide.OcrFacts {
        val (stripNames, stripNamed) = if (strip != null) lineNames(strip.set, strip.number, chosen, lookup, describe) else null to null
        if (read == null) return Decide.OcrFacts(nameMatches = false, lineNames = null, nameOfOtherLine = false, stripNames = stripNames)
        val card = describe?.invoke(chosen)
        val names = listOfNotNull(card?.name ?: index.facts[chosen]?.name, card?.flavorName)
        val nameMatches = read.name.isNotEmpty() && com.cardscanner.scryfall.Scryfall.namesMatch(read.name, names)
        val (lineNames, named) = lineNames(read.setCode, read.collectorNumber, chosen, lookup, describe)
        var nameOfOtherLine = false
        if (read.name.isNotEmpty() && !nameMatches) {
            val others = listOfNotNull(named.takeIf { lineNames == "other_card" }, stripNamed.takeIf { stripNames == "other_card" })
            nameOfOtherLine = others.any { id ->
                val d = describe?.invoke(id)
                com.cardscanner.scryfall.Scryfall.namesMatch(read.name, listOfNotNull(d?.name ?: index.facts[id]?.name, d?.flavorName))
            }
        }
        return Decide.OcrFacts(nameMatches = nameMatches, lineNames = lineNames, nameOfOtherLine = nameOfOtherLine, stripNames = stripNames)
    }

    /**
     * Whether two printings of the index are the same CARD: the same oracle id (printing_check.ocr_facts' rule - never by name similarity, which takes Lightning
     * Strike for Lightning Bolt); when either lacks one, the same name exactly. False when the table does not describe one of them
     */
    fun sameCard(a: String, b: String): Boolean {
        val fa = index.facts[a] ?: return false
        val fb = index.facts[b] ?: return false
        if (fa.oracleId != null && fb.oracleId != null) return fa.oracleId == fb.oracleId
        return fa.name == fb.name
    }

    /**
     * PrintingCheck._strip_names: what the strip's whole line names - null (no whole line: a number AND a set), "lookup_failed", "unknown" (the card data
     * lacks it), "unindexed" (the card data has it, the index lacks it), "chosen" (the printing decided on), "indexed_other"
     */
    private fun stripNames(strip: Decide.Strip, chosen: String, stripPrinting: ((String, String) -> StripPrinting)?): String? {
        if (strip.number.isEmpty() || strip.set.isEmpty()) return null
        return when (val named = stripPrinting?.invoke(strip.set, strip.number) ?: StripPrinting.Failed) {
            StripPrinting.Failed -> "lookup_failed"
            StripPrinting.NotFound -> "unknown"
            is StripPrinting.Found -> when {
                index.row(named.id) == null -> "unindexed"
                named.id == chosen -> "chosen"
                else -> "indexed_other"
            }
        }
    }

    /** The network's probability of a foil, read at the fixed place of the card turned upright (the search says which way up it lies) - null when nothing to cut */
    private fun fixedNet(card: Mat, rotated: Boolean, foilNet: FoilNet): Double? {
        val upright = if (rotated) Mat().also { Core.rotate(card, it, Core.ROTATE_180) } else card
        try {
            val crop = FoilNet.fixedCrop(upright) ?: return null
            try { return foilNet.probability(crop) } finally { crop.release() }
        } finally {
            if (upright !== card) upright.release()
        }
    }

    /**
     * printing_check.marker_expected: whether the card the picture shows prints a foil marker, judged by EVERY printing of the index the picture cannot tell
     * apart from its best match (less than Decide.AGREE_BELOW under it - the best match among them), all of which must print one. From the distances to the
     * whole index, not the five candidates (five same-art reprints tied would hide an older sixth). More than [MARKER_MAX_TIED] tied, or a printing the table
     * does not describe: false (the finish is left as before).
     */
    fun markerExpected(search: Search): Boolean {
        val distances = search.distances
        if (distances.isEmpty()) return false
        val best = Fingerprint.similarity(distances.min())
        var tied = 0
        for (row in distances.indices) {
            if (best - Fingerprint.similarity(distances[row]) < Decide.AGREE_BELOW) {
                if (++tied > MARKER_MAX_TIED) return false
                if (index.facts[index.ids[row]]?.printsAMarker != true) return false
            }
        }
        return tied > 0
    }

    companion object {
        /** How many nearest rows are candidates (the server's `_search_now`: k = 5) */
        const val CANDIDATES = 5

        /** printing_check.MARKER_MAX_TIED: more printings tied than this is too ambiguous to say the card prints a marker */
        const val MARKER_MAX_TIED = 200

        /** The server's PrintingCheck ORIENTATION_MIN_SIMILARITY / ORIENTATION_MIN_MARGIN */
        const val ORIENTATION_MIN_SIMILARITY = 0.30
        const val ORIENTATION_MIN_MARGIN = 0.20

        /**
         * The rows of the [k] smallest distances, nearest first; equal distances by row. (The server takes them with numpy's argpartition and a stable sort:
         * which of several rows tied exactly at the fifth place it keeps is numpy's choice - the parity test shows whether that ever matters.)
         */
        fun nearest(distances: IntArray, k: Int): List<Int> {
            if (distances.isEmpty()) return emptyList()
            // One pass keeping the k best (k is 5: an insertion into a small sorted array beats sorting 116,000 rows)
            val rows = IntArray(minOf(k, distances.size)) { -1 }
            var size = 0
            for (row in distances.indices) {
                val d = distances[row]
                if (size == rows.size && d >= distances[rows[size - 1]]) continue      // not better than the worst kept (a later equal row loses: by row)
                var at = if (size < rows.size) size++ else size - 1
                while (at > 0 && distances[rows[at - 1]] > d) { rows[at] = rows[at - 1]; at-- }
                rows[at] = row
            }
            return rows.take(size)
        }

        /**
         * Whether the search is sure which way up the card is (printing_check.orientation_established): the better orientation looks like a printing and is
         * clearly ahead of the other. What reads a place that is only where it should be on an upright card (the fixed-place foil crop) must ask this first.
         */
        fun orientationEstablished(search: Search): Boolean {
            val best = if (search.rotated) search.nearestTurned else search.nearestAsGiven
            val other = if (search.rotated) search.nearestAsGiven else search.nearestTurned
            val similarity = Fingerprint.similarity(best)
            return similarity >= ORIENTATION_MIN_SIMILARITY && similarity - Fingerprint.similarity(other) >= ORIENTATION_MIN_MARGIN
        }
    }
}
