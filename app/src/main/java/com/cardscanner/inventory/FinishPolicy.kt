package com.cardscanner.inventory

import com.cardscanner.ai.Foil
import com.cardscanner.scryfall.Printing

/** Shared scan/review finish policy, matching the Python scanner's suggested_finish. */
internal object FinishPolicy {
    fun suggest(printing: Printing?, marker: Foil): Pair<Boolean?, String?> {
        val finishes = printing?.finishes.orEmpty()
        val hasFoil = "foil" in finishes || "etched" in finishes
        val hasNonFoil = "nonfoil" in finishes
        return when {
            hasFoil && !hasNonFoil -> true to "only printed in foil"
            hasNonFoil && !hasFoil -> false to "only printed non-foil"
            marker == Foil.FOIL -> true to "★ marker"
            marker == Foil.NON_FOIL -> false to "• marker"
            else -> null to null
        }
    }

    fun finish(printing: Printing?, foil: Boolean?): Finish = when {
        foil != true -> Finish.REGULAR
        printing?.surgeFoil == true -> Finish.SURGE
        else -> Finish.FOIL
    }
}
