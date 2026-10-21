package com.cardscanner.settings

import android.content.Context
import android.content.SharedPreferences
import com.cardscanner.AppSettings
import com.cardscanner.ai.Provider
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** Preferences contain ordinary settings plus one authenticated, encrypted credential payload. */
internal class SettingsStorage(
    context: Context,
    private val cipher: CredentialCipher = KeystoreCredentialCipher("${context.packageName}.credentials.v1"),
) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    data class Loaded(val settings: AppSettings, val credentialError: String? = null)

    /** Deliberately omit underlying exceptions: a parser/provider error can contain secret data. */
    class SaveFailed : IOException("Could not save settings securely. Your changes remain in memory; try again.")

    private class Credentials(val keys: Map<Provider, String>, val token: String) {
        val empty get() = keys.values.all { it.isEmpty() } && token.isEmpty()
        fun sameAs(other: Credentials) = keys == other.keys && token == other.token
        fun json() = JSONObject().put("api_keys", JSONObject().apply {
            Provider.entries.forEach { put(it.id, keys[it].orEmpty()) }
        }).put("station_token", token).toString()
    }

    private fun legacy() = Credentials(Provider.entries.associateWith { prefs.getString("key_${it.id}", "").orEmpty() },
        prefs.getString("station_token", "").orEmpty())

    private fun decode(payload: String): Credentials {
        val json = JSONObject(cipher.decrypt(payload))
        val keys = json.getJSONObject("api_keys")
        return Credentials(Provider.entries.associateWith { keys.getString(it.id) }, json.getString("station_token"))
    }

    private fun SharedPreferences.Editor.removeLegacy(): SharedPreferences.Editor = apply {
        Provider.entries.forEach { remove("key_${it.id}") }
        remove("station_token")
    }

    fun load(): Loaded = synchronized(storageLock) {
        var error: String? = null
        val payload = prefs.getString(CREDENTIALS, null)
        val credentials = if (payload != null) {
            try {
                decode(payload).also {
                    if (hasLegacy() && !prefs.edit().removeLegacy().commit()) error = MIGRATION_ERROR
                }
            } catch (_: Exception) {
                error = READ_ERROR
                Credentials(Provider.entries.associateWith { "" }, "")
            }
        } else {
            legacy().also {
                try {
                    val editor = prefs.edit().removeLegacy()
                    if (!it.empty) editor.putString(CREDENTIALS, cipher.encrypt(it.json()))
                    if (!editor.commit()) error = MIGRATION_ERROR
                } catch (_: Exception) {
                    // Leave existing plaintext untouched if encryption fails; never write new
                    // plaintext or remove the only recoverable copy of the user's credentials.
                    error = MIGRATION_ERROR
                }
            }
        }
        val stationId = prefs.getString("station_id", null) ?: "phone-${UUID.randomUUID().toString().take(8)}"
        Loaded(AppSettings(
            provider = Provider.of(prefs.getString("provider", null)),
            models = Provider.entries.associateWith { prefs.getString("model_${it.id}", "").orEmpty() },
            apiKeys = credentials.keys,
            stationToken = credentials.token,
            stationId = stationId,
            stationName = prefs.getString("station_name", null) ?: android.os.Build.MODEL.orEmpty(),
            serverMode = prefs.getBoolean("server_mode", false),
            sendFrames = prefs.getBoolean("send_frames", false),
            serverUrl = prefs.getString("server_url", "").orEmpty(),
            localUrl = prefs.getString("local_url", "").orEmpty(),
            detectFoil = prefs.getBoolean("detect_foil", true),
            pictureFirst = prefs.getBoolean("picture_first", true),
            readCollectorLine = prefs.getBoolean("read_collector_line", true),
            aiEnabled = prefs.getBoolean("ai_enabled", true),
            minSharpness = prefs.getInt("min_sharpness", 250),
            stableFrames = prefs.getInt("stable_frames", 5),
            rotation = prefs.getInt("rotation", 0),
            cornerNet = prefs.getBoolean("corner_net", true),
            fixedAreaEnabled = prefs.getBoolean("fixed_area_enabled", false),
            autoAdd = prefs.getBoolean("auto_add", true),
            captureSound = prefs.getBoolean("sound_capture", prefs.getBoolean("sounds", true)),
            addedSound = prefs.getBoolean("sound_added", prefs.getBoolean("sounds", true)),
            reviewSound = prefs.getBoolean("sound_review", prefs.getBoolean("sounds", true)),
            fixedArea = prefs.getString("fixed_area", null)?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 4 },
        ), error).also {
            if (!prefs.contains("station_id")) prefs.edit().putString("station_id", stationId).commit()
        }
    }

    private fun hasLegacy() = prefs.contains("station_token") || Provider.entries.any { prefs.contains("key_${it.id}") }

    /** Ciphertext replacement and plaintext removal share a single synchronous disk commit. */
    fun save(settings: AppSettings): String? = synchronized(storageLock) {
        try {
            val desired = Credentials(Provider.entries.associateWith { settings.apiKey(it) }, settings.stationToken)
            val existing = prefs.getString(CREDENTIALS, null)
            val previous = if (existing == null) legacy() else try { decode(existing) } catch (_: Exception) { null }
            val editor = prefs.edit()
            var error: String? = null
            if (existing != null && previous == null && desired.empty) {
                // An unrelated settings edit must not silently erase inaccessible credentials.
                error = READ_ERROR
            } else {
                if (desired.empty) editor.remove(CREDENTIALS)
                else if (previous == null || !desired.sameAs(previous) || existing == null) {
                    editor.putString(CREDENTIALS, cipher.encrypt(desired.json()))
                }
                editor.removeLegacy()
            }
            editor.putString("provider", settings.provider.id)
            Provider.entries.forEach { editor.putString("model_${it.id}", settings.models[it].orEmpty()) }
            editor.putString("local_url", settings.localUrl)
                .putBoolean("detect_foil", settings.detectFoil)
                .putBoolean("picture_first", settings.pictureFirst)
                .putBoolean("read_collector_line", settings.readCollectorLine)
                .putBoolean("ai_enabled", settings.aiEnabled)
                .putInt("min_sharpness", settings.minSharpness)
                .putInt("stable_frames", settings.stableFrames)
                .putInt("rotation", settings.rotation)
                .putBoolean("corner_net", settings.cornerNet)
                .putBoolean("fixed_area_enabled", settings.fixedAreaEnabled)
                .putString("fixed_area", settings.fixedArea?.joinToString(","))
                .putBoolean("auto_add", settings.autoAdd)
                .putBoolean("sound_capture", settings.captureSound)
                .putBoolean("sound_added", settings.addedSound)
                .putBoolean("sound_review", settings.reviewSound)
                .remove("sounds")
                .putBoolean("server_mode", settings.serverMode)
                .putBoolean("send_frames", settings.sendFrames)
                .putString("server_url", settings.serverUrl)
                .putString("station_name", settings.stationName)
            if (settings.stationId.isNotEmpty()) editor.putString("station_id", settings.stationId)
            if (!editor.commit()) throw SaveFailed()
            error
        } catch (_: Exception) {
            throw SaveFailed()
        }
    }

    companion object {
        internal const val CREDENTIALS = "credentials_v1"
        internal const val READ_ERROR = "Saved credentials could not be read. Re-enter your API keys and station token."
        internal const val MIGRATION_ERROR = "Could not protect saved credentials. Existing data was kept; try again."
        private val storageLock = Any()
    }
}
