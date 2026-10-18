package com.cardscanner.detection

import kotlin.math.max
import kotlin.math.min

/**
 * The arithmetic of finding several cards in one photo (a binder page, cards laid out on a table):
 * which outlines to keep, where a grid's cells are, in what order to show them. Plain Kotlin like
 * [CornerMath], so it runs in JVM tests; the contours come from [MultiCardFinder].
 */
object MultiCard {
    /** Axis-aligned box of an outline: left, top, right, bottom */
    private fun box(c: Corners) = doubleArrayOf(c.minOf { it.x }, c.minOf { it.y }, c.maxOf { it.x }, c.maxOf { it.y })

    private fun area(b: DoubleArray) = max(0.0, b[2] - b[0]) * max(0.0, b[3] - b[1])

    private fun intersection(a: DoubleArray, b: DoubleArray) =
        area(doubleArrayOf(max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])))

    /** How much of the smaller of two outlines' boxes the other covers (1 = one lies inside the other) */
    fun overlap(a: Corners, b: Corners): Double {
        val boxA = box(a); val boxB = box(b)
        return intersection(boxA, boxB) / max(1e-9, min(area(boxA), area(boxB)))
    }

    /**
     * The outlines that are single cards. A 3x3 binder page has a card's proportions itself, so an
     * outline that holds two or more others is the page, not a card. Of outlines on top of each
     * other (a sleeve's edge and the card's) the larger is kept, as in the live detector. Fragments
     * far smaller than the typical card are dropped.
     */
    fun cards(outlines: List<Corners>, minShareOfMedian: Double = 0.4): List<Corners> {
        if (outlines.isEmpty()) return emptyList()
        val boxes = outlines.map { box(it) }
        val areas = boxes.map { area(it) }
        val containers = outlines.indices.filter { i ->
            outlines.indices.count { j -> j != i && areas[j] < areas[i] && overlap(outlines[i], outlines[j]) > 0.9 } >= 2
        }.toSet()
        val candidates = outlines.indices.filter { it !in containers }.sortedByDescending { areas[it] }
        val kept = ArrayList<Int>()
        for (i in candidates) if (kept.none { overlap(outlines[i], outlines[it]) > 0.5 }) kept += i
        val median = kept.map { areas[it] }.sorted().let { it[it.size / 2] }
        return inReadingOrder(kept.filter { areas[it] >= minShareOfMedian * median }.map { outlines[it] })
    }

    /** Rows from the top, each from left to right (rows: centres less than half a card's height apart) */
    fun inReadingOrder(cards: List<Corners>): List<Corners> {
        if (cards.size < 2) return cards
        fun centre(c: Corners) = Pt(c.sumOf { it.x } / 4, c.sumOf { it.y } / 4)
        val height = cards.map { c -> box(c).let { it[3] - it[1] } }.sorted()[cards.size / 2]
        val rows = ArrayList<MutableList<Corners>>()
        for (card in cards.sortedBy { centre(it).y }) {
            val row = rows.lastOrNull()
            if (row != null && centre(card).y - centre(row.first()).y < 0.5 * height) row += card else rows += mutableListOf(card)
        }
        return rows.flatMap { row -> row.sortedBy { centre(it).x } }
    }

    /**
     * The cells of a page of `columns` x `rows` cards: `page` is its outline (top-left, top-right,
     * bottom-right, bottom-left, in any perspective), each cell is that share of it, `inset` of the
     * cell's size smaller on every side (the pockets' gaps and the sleeves' edges).
     */
    fun gridCells(page: Corners, columns: Int, rows: Int, inset: Double = 0.02): List<Corners> {
        require(columns > 0 && rows > 0) { "A grid needs at least one column and row" }
        val (tl, tr, br, bl) = page
        // Bilinear: u across, v down (exact for a parallelogram, close for a page seen at an angle)
        fun at(u: Double, v: Double): Pt {
            val top = tl + (tr - tl) * u
            val bottom = bl + (br - bl) * u
            return top + (bottom - top) * v
        }
        return (0 until rows).flatMap { row ->
            (0 until columns).map { column ->
                val u0 = (column + inset) / columns; val u1 = (column + 1 - inset) / columns
                val v0 = (row + inset) / rows; val v1 = (row + 1 - inset) / rows
                listOf(at(u0, v0), at(u1, v0), at(u1, v1), at(u0, v1))
            }
        }
    }

    /** Is the point inside the outline? (a tap on a card in the preview) */
    fun contains(c: Corners, p: Pt): Boolean {
        var inside = false
        var j = c.size - 1
        for (i in c.indices) {
            val a = c[i]; val b = c[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }
}
