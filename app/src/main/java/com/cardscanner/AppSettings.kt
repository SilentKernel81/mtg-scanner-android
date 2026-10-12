package com.cardscanner

import android.content.Context
import com.cardscanner.ai.Provider

/**
 * Immutable settings snapshot. Credentials are encrypted by SettingsStorage before persistence. The first group
 * is for standalone scanning (the AI, the foil check, automatic adds), the capture thresholds and
 * the fixed area apply to both modes, and the last group is client mode.
 */
data class AppSettings(
    val provider: Provider = Provider.GEMINI,
    val models: Map<Provider, String> = emptyMap(),
    val apiKeys: Map<Provider, String> = emptyMap(),
    val localUrl: String = "",
    val detectFoil: Boolean = true,
    /** Don't auto-capture below this sharpness (Laplacian variance, see CardTracker) */
    val minSharpness: Int = 250,
    /** Consecutive still, in-focus frames before an auto-capture */
    val stableFrames: Int = 5,
    /** Extra clockwise rotation of the camera image (0/90/180/270): cards must look upright */
    val rotation: Int = 0,
    /** Fixed area (sleeved / borderless cards): cards judged by the image inside it */
    val fixedAreaEnabled: Boolean = false,
    /** The area: x1, y1, x2, y2 as fractions of the (turned) camera frame */
    val fixedArea: List<Double>? = null,
    /** Add confirmed cards (set + number match) to the inventory without asking */
    val autoAdd: Boolean = true,
    /** Independent sound cues, used in both standalone and server mode. */
    val captureSound: Boolean = true,
    val addedSound: Boolean = true,
    val reviewSound: Boolean = true,
    /**
     * Client mode: captured cards are sent to a scanner server (the Python project's server/),
     * which reads them, finds the printing and keeps the collection. Off: standalone - this app
     * does all of that itself
     */
    val serverMode: Boolean = false,
    /** The server's address, e.g. http://192.168.1.20:5000 */
    val serverUrl: String = "",
    /** What this phone is called on the server (its station name) */
    val stationName: String = "",
    /** The server's station token, if it has one */
    val stationToken: String = "",
    /** This phone's station id on the server: made once, kept for good */
    val stationId: String = "",
) {
    /** The fixed area in use, or null (outline mode) */
    val activeArea get() = fixedArea?.takeIf { fixedAreaEnabled }

    fun model(provider: Provider = this.provider) = models[provider]?.takeIf { it.isNotBlank() } ?: provider.models.first()
    fun apiKey(provider: Provider = this.provider) = apiKeys[provider].orEmpty()

    // Compatibility entry points for tools/tests; live app state belongs to SettingsRepository.
    companion object {
        fun load(context: Context): AppSettings = com.cardscanner.settings.SettingsStorage(context).load().settings
    }

    fun save(context: Context) { com.cardscanner.settings.SettingsStorage(context).save(this) }

    override fun toString(): String = "AppSettings(provider=$provider, serverMode=$serverMode, credentials=<redacted>)"
}
