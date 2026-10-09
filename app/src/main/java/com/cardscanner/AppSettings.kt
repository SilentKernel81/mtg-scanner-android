package com.cardscanner

import android.content.Context
import com.cardscanner.ai.Provider

/**
 * User settings (SharedPreferences, private to the app), saved on every change. The first group
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
    /** Sound effects: capture, added to the inventory, sent to the review queue */
    val sounds: Boolean = true,
    /**
     * Client mode: captured cards are sent to a scanner server (the Python project's app.py),
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

    companion object {
        private const val PREFS = "settings"

        fun load(context: Context): AppSettings {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stationId = prefs.getString("station_id", null) ?: ("phone-" + java.util.UUID.randomUUID().toString().take(8))
                .also { prefs.edit().putString("station_id", it).apply() }
            return AppSettings(
                serverMode = prefs.getBoolean("server_mode", false),
                serverUrl = prefs.getString("server_url", "").orEmpty(),
                stationName = prefs.getString("station_name", null) ?: android.os.Build.MODEL.orEmpty(),
                stationToken = prefs.getString("station_token", "").orEmpty(),
                stationId = stationId,
                provider = Provider.of(prefs.getString("provider", null)),
                models = Provider.entries.associateWith { prefs.getString("model_${it.id}", "").orEmpty() },
                apiKeys = Provider.entries.associateWith { prefs.getString("key_${it.id}", "").orEmpty() },
                localUrl = prefs.getString("local_url", "").orEmpty(),
                detectFoil = prefs.getBoolean("detect_foil", true),
                minSharpness = prefs.getInt("min_sharpness", 250),
                stableFrames = prefs.getInt("stable_frames", 5),
                rotation = prefs.getInt("rotation", 0),
                fixedAreaEnabled = prefs.getBoolean("fixed_area_enabled", false),
                autoAdd = prefs.getBoolean("auto_add", true),
                sounds = prefs.getBoolean("sounds", true),
                fixedArea = prefs.getString("fixed_area", null)?.split(",")?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 4 },
            )
        }
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putString("provider", provider.id)
            Provider.entries.forEach {
                putString("model_${it.id}", models[it].orEmpty())
                putString("key_${it.id}", apiKeys[it].orEmpty())
            }
            putString("local_url", localUrl)
            putBoolean("detect_foil", detectFoil)
            putInt("min_sharpness", minSharpness)
            putInt("stable_frames", stableFrames)
            putInt("rotation", rotation)
            putBoolean("fixed_area_enabled", fixedAreaEnabled)
            putBoolean("auto_add", autoAdd)
            putBoolean("sounds", sounds)
            putString("fixed_area", fixedArea?.joinToString(","))
            putBoolean("server_mode", serverMode)
            putString("server_url", serverUrl)
            putString("station_name", stationName)
            putString("station_token", stationToken)
            if (stationId.isNotEmpty()) putString("station_id", stationId)
        }.apply()
    }
}
