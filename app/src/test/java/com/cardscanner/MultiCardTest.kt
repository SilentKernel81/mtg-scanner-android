package com.cardscanner

import com.cardscanner.detection.Corners
import com.cardscanner.detection.MultiCard
import com.cardscanner.detection.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiCardTest {
    private fun rect(x: Double, y: Double, w: Double, h: Double): Corners =
        listOf(Pt(x, y), Pt(x + w, y), Pt(x + w, y + h), Pt(x, y + h))

    /** 3x3 binder page of 63x88 cards with 10 px gaps, as the finder would see them */
    private val cards = (0 until 3).flatMap { r -> (0 until 3).map { c -> rect(20.0 + c * 73, 20.0 + r * 98, 63.0, 88.0) } }
    private val page = rect(10.0, 10.0, 3 * 73.0 + 10, 3 * 98.0 + 10)

    @Test fun thePageHoldingNineCardsIsNotACard() {
        val kept = MultiCard.cards(cards + listOf(page))
        assertEquals(9, kept.size)
        assertFalse(kept.any { it == page })
    }

    @Test fun aSleeveAndItsCardAreOneCard() {
        val sleeve = rect(17.0, 17.0, 69.0, 94.0)
        val kept = MultiCard.cards(cards + listOf(sleeve))
        assertEquals(9, kept.size)
        assertTrue(sleeve in kept)  // the larger of the two, as in the live detector
        assertFalse(cards[0] in kept)
    }

    @Test fun fragmentsAreDropped() {
        val kept = MultiCard.cards(cards + listOf(rect(500.0, 500.0, 20.0, 28.0)))
        assertEquals(9, kept.size)
    }

    @Test fun cardsComeRowByRowFromTheLeft() {
        val shuffled = cards.reversed()
        assertEquals(cards, MultiCard.inReadingOrder(shuffled))
    }

    @Test fun aSlightlyTiltedRowStaysARow() {
        val row = listOf(rect(10.0, 14.0, 63.0, 88.0), rect(90.0, 0.0, 63.0, 88.0), rect(170.0, 10.0, 63.0, 88.0))
        assertEquals(listOf(10.0, 90.0, 170.0), MultiCard.inReadingOrder(row.reversed()).map { it[0].x })
    }

    @Test fun gridCellsFillThePage() {
        val cells = MultiCard.gridCells(rect(0.0, 0.0, 300.0, 270.0), 3, 3, inset = 0.0)
        assertEquals(9, cells.size)
        assertEquals(listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 90.0), Pt(0.0, 90.0)), cells[0])
        assertEquals(listOf(Pt(200.0, 180.0), Pt(300.0, 180.0), Pt(300.0, 270.0), Pt(200.0, 270.0)), cells[8])
    }

    @Test fun gridCellsAreInsetAndFollowASkewedPage() {
        val skewed = listOf(Pt(0.0, 0.0), Pt(300.0, 30.0), Pt(300.0, 330.0), Pt(0.0, 300.0))
        val cells = MultiCard.gridCells(skewed, 3, 3, inset = 0.1)
        assertEquals(9, cells.size)
        // the right-hand column is lower than the left one, the cell smaller than its share
        assertTrue(cells[2][0].y > cells[0][0].y)
        assertTrue(cells[0][1].x - cells[0][0].x < 100.0)
    }

    @Test fun aPointInsideAnOutline() {
        val card = listOf(Pt(0.0, 0.0), Pt(10.0, 2.0), Pt(9.0, 14.0), Pt(-1.0, 12.0))
        assertTrue(MultiCard.contains(card, Pt(5.0, 7.0)))
        assertFalse(MultiCard.contains(card, Pt(15.0, 7.0)))
    }
}
