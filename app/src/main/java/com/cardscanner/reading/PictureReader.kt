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
                       val net: Double? = null) {
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
    fun read(card: Mat, acceptImageAlone: Boolean, indexCovers: Boolean?, inData: (String) -> Boolean, foilNet: FoilNet? = null): Reading {
        val search = search(card)
        val decision = Decide.printing(search.candidates, Decide.Strip())            // no OCR: no strip
        val chosen = decision.printingId
        val facts = Decide.Facts(acceptImageAlone = acceptImageAlone, indexCovers = indexCovers,
            inData = chosen != null && index.facts.containsKey(chosen) && inData(chosen), stripNames = null, ocrOn = false)
        val acceptance = Decide.acceptPrinting(decision, facts)
        if (!acceptance.accepted || chosen == null) return Reading(search, decision, acceptance, null)
        // The finish. The foil network at the fixed place - the server's live_read with no OCR reader: only when the search is sure which way up the card
        // is (the place is the set line on an UPRIGHT card) and every printing the picture cannot tell from its best match prints a marker (an old-frame card
        // feeds the network whatever is there: it answered confidently on 53 of 80 such cards). Only asked where the printing's own finishes leave it open
        val finishes = index.facts[chosen]?.finishes
        val net = if (foilNet != null && Decide.finish(finishes).finish == "unknown" && orientationEstablished(search) && markerExpected(search)) {
            fixedNet(card, search.rotated, foilNet)
        } else null
        return Reading(search, decision, acceptance, Decide.finish(finishes, "unknown", net), net)
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
