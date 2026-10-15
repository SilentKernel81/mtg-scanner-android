package com.cardscanner.ui

/**
 * How strongly the card is painted over on the scanner screen - the same as the stations' live
 * view (station/scanner.py: `_mark_auto_card`, `_flash_capture`): a card found by its outline has
 * no border, it is tinted in its state's colour, and a capture washes it green, stronger, fading
 * down to the tint. The fixed area keeps its rectangle and gets the wash only. No Compose in
 * here, so a JVM test can check the numbers.
 */
internal object CaptureWash {
    /** A card found by itself: its state's colour, see-through */
    const val TINT = 0.22f
    /** At a capture: green, this strong at first */
    const val FLASH = 0.45f

    /**
     * What to paint over an auto-detected card. wash: 1 at a capture, fading to 0.
     * Returns (alpha, green): green while the wash is stronger than the tint, else the state's colour.
     */
    fun autoCard(wash: Float): Pair<Float, Boolean> {
        val flash = FLASH * wash.coerceIn(0f, 1f)
        return if (flash > TINT) flash to true else TINT to false
    }

    /** The fixed area: only the wash (its rectangle is drawn anyway); 0 = nothing */
    fun area(wash: Float): Float = FLASH * wash.coerceIn(0f, 1f)
}
