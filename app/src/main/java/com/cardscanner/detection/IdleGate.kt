package com.cardscanner.detection

/**
 * Saves battery while the box sits unchanged: when nothing is going on (no card, or the card just
 * captured still lying there) a frame is only analysed every [idleIntervalMs] - unless a coarse
 * sample of the picture differs from the last analysed frame, which wakes the analysis to every
 * frame for [wakeMs] (a card falling in, a hand). Skipping a frame costs one cheap comparison
 * instead of the RGBA copy and the card finder.
 *
 * Only an idle scene is ever throttled: a card being tracked or settling is analysed on every frame,
 * as the thresholds in [CardTracker] were measured.
 */
class IdleGate(
    private val idleIntervalMs: Long = 500,
    private val wakeMs: Long = 1500,
    private val threshold: Double = 6.0,
) {
    private var reference: ByteArray? = null
    private var lastProcessed = 0L
    private var activeUntil = 0L

    /** The last measured difference to the reference (mean absolute grey level), for logging */
    var lastDifference = 0.0
        private set

    /** Whether to analyse this frame. [sample]: a coarse grid of grey levels of the frame. */
    fun shouldProcess(sample: ByteArray, idle: Boolean, now: Long): Boolean {
        val ref = reference
        if (ref == null || ref.size != sample.size) return take(sample, now)
        lastDifference = difference(sample, ref)
        if (lastDifference > threshold) activeUntil = now + wakeMs
        val process = !idle || now < activeUntil || now - lastProcessed >= idleIntervalMs
        return if (process) take(sample, now) else false
    }

    /** Forget the scene (the next frame is analysed) */
    fun reset() { reference = null }

    private fun take(sample: ByteArray, now: Long): Boolean {
        reference = sample
        lastProcessed = now
        return true
    }

    companion object {
        fun difference(a: ByteArray, b: ByteArray): Double {
            var sum = 0L
            for (i in a.indices) sum += kotlin.math.abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
            return sum.toDouble() / a.size
        }
    }
}
