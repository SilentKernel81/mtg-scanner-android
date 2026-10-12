package com.cardscanner

import android.graphics.Bitmap
import com.cardscanner.ai.CardReading
import com.cardscanner.ai.Usage
import com.cardscanner.inventory.Finish
import com.cardscanner.server.ServerOutcome
import com.cardscanner.scryfall.Printing

/** One captured card and what became of it */
data class ScanResult(
    val id: Long,
    val thumbnail: Bitmap,
    val status: Status = Status.IDENTIFYING,
    val reading: CardReading? = null,
    val printing: Printing? = null,
    val foil: Boolean? = null,
    /** Why the finish was chosen: from the printing data (certain) or from the ★/• marker */
    val foilReason: String? = null,
    val seconds: Double? = null,
    val error: String? = null,
    /** Tokens used by the AI requests for this card */
    val usage: Usage? = null,
    /** The inventory entry this card was added to (null: not added) */
    val inventoryId: Long? = null,
    /** The review queue item it went to (uncertain while adding automatically), or null */
    val reviewId: Long? = null,
    /** A manual Add is also backed by a durable review item. */
    val pendingAdd: Boolean = false,
    /** Client mode: what the server made of the card (null in standalone mode, and until it answers) */
    val server: ServerOutcome? = null,
    val serverUrl: String? = null,
    val stationId: String? = null,
    val deleting: Boolean = false,
    /**
     * Client mode: the server said it cannot follow this capture (ServerRefused.cannotFollow)
     * when asked to remove it. Remove then takes the row off the phone only - whatever the
     * capture became stays on the server, to be removed there.
     */
    val serverCannotFollow: Boolean = false,
    val serverMode: Boolean = false,
    /** Client mode: the server's add was taken back (Undo) */
    val undone: Boolean = false,
    /** What is going on while IDENTIFYING, when it isn't the AI ("Sending…", "Server not reachable…") */
    val note: String? = null,
) {
    /** The finish it is added as: surge foil printings' foils are surge foils */
    val finish get() = when {
        foil != true -> Finish.REGULAR
        printing?.surgeFoil == true -> Finish.SURGE
        else -> Finish.FOIL
    }

    enum class Status { IDENTIFYING, DONE, FAILED }
}
