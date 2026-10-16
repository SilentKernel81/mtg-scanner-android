package com.cardscanner.server

import java.io.IOException

/**
 * When a capture that cannot be sent stops being tried (the station's `Station.park`,
 * scanner-server docs/PROGRAM_DOCUMENTATION.md, Stations). Each capture here has its own retry
 * loop, so one that fails never holds the others up - but it was retried every 30 s for ever, also
 * when the server had said it will never take it, or kept answering with something that is neither
 * "received" nor "refused" (an error page, a 5xx for this picture, a wrong token).
 *
 * - Refused for good: HTTP 400 / 413 (an unreadable or too large picture): parked at once.
 * - An odd answer: any other answer from the server that is not a success - counted; after
 *   [PARK_AFTER_ANSWERS] the capture is parked.
 * - No answer at all (the server is away, no network): never counted - retried for ever, as
 *   "at least once" needs.
 *
 * A parked capture stays in the outbox (its photo and note) and is tried again the next time the
 * app starts; the other captures go on.
 */
object SendPolicy {
    const val PARK_AFTER_ANSWERS = 20

    /** The server answered that it will never take this capture */
    fun refusedForGood(e: Exception): Boolean = e is ServerRefused && (e.code == 400 || e.code == 413)

    /**
     * The server answered, and not with a success, and not "never": counts toward parking.
     * A refusal decided on the phone (code 0: no server address, the original server is needed)
     * is not an answer - Settings fix that - and neither is a failure to reach the server.
     */
    fun oddAnswer(e: Exception): Boolean = when (e) {
        is ServerRefused -> e.code != 0 && !refusedForGood(e)
        // ScannerServer.send: "HTTP <code>: <text>" for a 5xx or an answer that is not JSON
        is IOException -> e.message?.startsWith("HTTP ") == true
        else -> false
    }
}
