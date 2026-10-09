package com.cardscanner.server

import android.graphics.Bitmap
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

/** A card as the server added it */
data class ServerCard(val name: String, val set: String, val number: String, val finish: String)

/**
 * What became of a capture on the server (its answer to POST /api/stations/<id>/captures - see
 * the server's PROGRAM_DOCUMENTATION.md, Stations)
 */
data class ServerOutcome(
    /** The capture's number at this station (to ask again while it is PENDING) */
    val capture: Int? = null,
    val status: Status,
    /** ADDED: the card */
    val card: ServerCard? = null,
    /** REVIEW: why it was not added ("printing not confirmed", "not found", "not read", ...) */
    val reason: String? = null,
    /** What was read from the card, and what read it ("light-ocr", or the AI model) */
    val readName: String = "",
    val readNumber: String = "",
    val readSet: String = "",
    val reader: String? = null,
    /** ERROR: the server's message */
    val message: String? = null,
) {
    /** ADDED to the scanned cards; REVIEW: in the server's review queue; PENDING: still being read */
    enum class Status { ADDED, REVIEW, PENDING, ERROR }

    companion object {
        fun parse(json: JSONObject): ServerOutcome {
            val status = when (json.optString("status")) {
                "added" -> Status.ADDED
                "review" -> Status.REVIEW
                "pending" -> Status.PENDING
                else -> Status.ERROR
            }
            val card = json.optJSONObject("card")?.let {
                ServerCard(it.optString("name"), it.optString("set"), it.optString("number"), it.optString("finish"))
            }
            val read = json.optJSONObject("read")
            fun text(o: JSONObject?, key: String) = if (o == null || o.isNull(key)) null else o.optString(key)
            return ServerOutcome(
                capture = if (json.has("capture")) json.optInt("capture") else null,
                status = status,
                card = card,
                reason = text(json, "reason"),
                readName = text(read, "name").orEmpty(),
                readNumber = text(read, "number").orEmpty(),
                readSet = text(read, "set").orEmpty(),
                reader = text(read, "reader"),
                message = text(json, "message"),
            )
        }
    }
}

/** The server refused the request (a wrong token, a bad picture): trying again won't help */
class ServerRefused(message: String) : Exception(message)

/**
 * The scanner server, as this phone's station sees it. Calls block (run them off the main
 * thread); IOException means the server could not be reached - the same capture can be sent
 * again with the same captureId and is one card.
 */
class ScannerServer(serverUrl: String, private val stationId: String, private val stationName: String,
                    private val token: String, private val http: OkHttpClient) {
    val baseUrl = normalize(serverUrl)

    /** This station's page on the server: its cards and its review queue */
    val pageUrl get() = "$baseUrl/scan/$stationId"

    private fun request(path: String) = Request.Builder().url("$baseUrl$path").apply {
        if (token.isNotBlank()) header("X-Station-Token", token)
    }

    private fun send(request: Request): Pair<Int, JSONObject> {
        if (baseUrl.isEmpty()) throw ServerRefused("No server address - set it in Settings")
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (response.code in 400..499) {
                throw ServerRefused(json?.optString("message")?.takeIf { it.isNotBlank() } ?: "HTTP ${response.code}")
            }
            if (!response.isSuccessful || json == null) throw IOException("HTTP ${response.code}: ${text.take(200)}")
            return response.code to json
        }
    }

    /**
     * Send a captured card. card: the photo; foilCard: the flat, tightly cropped card for the
     * ★/• check (the same bitmap when the photo is that card; null without an outline).
     * Waits up to `waitSeconds` for the outcome, which is PENDING after that (ask with outcome()).
     */
    fun sendCapture(card: Bitmap, foilCard: Bitmap?, captureId: String, waitSeconds: Int = 30): ServerOutcome {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("name", stationName)
            .addFormDataPart("capture_id", captureId)
            .addFormDataPart("wait", waitSeconds.toString())
            .addFormDataPart("image", "card.jpg", jpeg(card).toRequestBody(JPEG))
        if (foilCard === card) body.addFormDataPart("foil_is_image", "1")
        else if (foilCard != null) body.addFormDataPart("foil_image", "foil.jpg", jpeg(foilCard).toRequestBody(JPEG))
        return ServerOutcome.parse(send(request("/api/stations/$stationId/captures").post(body.build()).build()).second)
    }

    /** What became of a capture that was still PENDING */
    fun outcome(capture: Int): ServerOutcome =
        ServerOutcome.parse(send(request("/api/stations/$stationId/captures/$capture").get().build()).second)

    /** Take back the last card this station added: its name, or null when there is nothing to undo */
    fun undo(): String? {
        val json = send(request("/api/stations/$stationId/undo").post(ByteArray(0).toRequestBody()).build()).second
        return if (json.optBoolean("success")) json.optString("name") else null
    }

    /** Check the address (and that it is a scanner server): how many stations it knows */
    fun test(): String {
        val json = send(request("/api/stations").get().build()).second
        val stations = json.optJSONArray("stations") ?: throw IOException("Not a scanner server")
        return "Connected: ${stations.length()} station${if (stations.length() == 1) "" else "s"}" +
            if (json.optBoolean("token_required") && token.isBlank()) " - it needs the station token" else ""
    }

    companion object {
        private val JPEG = "image/jpeg".toMediaType()

        /** "192.168.1.20:5000/" -> "http://192.168.1.20:5000" */
        fun normalize(url: String): String {
            val trimmed = url.trim().trimEnd('/')
            return if (trimmed.isEmpty() || "://" in trimmed) trimmed else "http://$trimmed"
        }

        private fun jpeg(image: Bitmap): ByteArray =
            ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
    }
}
