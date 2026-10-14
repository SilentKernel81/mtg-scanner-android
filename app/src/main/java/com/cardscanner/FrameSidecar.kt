package com.cardscanner

import com.cardscanner.detection.Corners
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.round

/**
 * The camera frame a capture was cut from, with the outline found in it: training data for a
 * better card detector. A capture is only the flat card - the frame and the outline are gone
 * once it is cut out - so, with Settings → "Send camera frames" on (client mode), each capture
 * is sent with both, as the station does (scanner-server: `core/frames.py`, the station's
 * `queue_upload`): the multipart fields `frame` (a JPEG) and `frame_meta` (JSON).
 *
 * The server keeps the pair as data/frames/<capture id>.jpg + .json; an older server ignores
 * the fields. Nothing here is read back by the app.
 */
class CapturedFrame(val jpeg: ByteArray, val meta: JSONObject)

/** Why the card now on the pile counted as new (CardTracker.lastNewCard), for the note */
class NewCard(val kind: String, val jump: Double? = null, val imageChange: Double? = null,
              val areaChange: Double? = null, val at: Long = System.currentTimeMillis())

object FrameSidecar {
    /**
     * Longest side of the stored frame: a 12 MP still is ~4 MB, and the detector works at
     * 640 px anyway. The outline is scaled with it, so it stays in the stored frame's pixels.
     */
    const val MAX_SIDE = 2048
    const val JPEG_QUALITY = 90

    /** Factor that brings a width x height picture to MAX_SIDE on its longer side (1.0: it already fits) */
    fun scale(width: Int, height: Int, maxSide: Int = MAX_SIDE): Double {
        val longest = max(width, height)
        return if (longest <= maxSide) 1.0 else maxSide.toDouble() / longest
    }

    /** The outline in the stored frame's pixels - [[x, y] x4: top left, top right, bottom right, bottom left] - or null */
    fun cornersJson(corners: Corners?, factor: Double): JSONArray? = corners?.let { points ->
        JSONArray().also { array ->
            points.forEach { array.put(JSONArray().put(round(it.x * factor * 10) / 10).put(round(it.y * factor * 10) / 10)) }
        }
    }

    /**
     * The note, with the same fields as the station's (docs/PROGRAM_DOCUMENTATION.md, *Camera
     * frames as training data*). captureId / previousCaptureId are added by the outbox.
     */
    fun meta(
        frameWidth: Int, frameHeight: Int, corners: JSONArray?, cardWidth: Int, cardHeight: Int,
        rotation: Int, fixedArea: Boolean, stableFrames: Int, requiredStableFrames: Int,
        newCard: NewCard?, secondsSincePreviousCapture: Double?, now: Long = System.currentTimeMillis(),
    ): JSONObject = JSONObject()
        .put("taken", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(now)))
        .put("frame_width", frameWidth)
        .put("frame_height", frameHeight)
        .put("corners", corners ?: JSONObject.NULL)  // null: no outline (the photo is the area as drawn)
        .put("card_size", JSONArray().put(cardWidth).put(cardHeight))
        .put("rotation", rotation)
        .put("fixed_area", fixedArea)
        .put("stable_frames", stableFrames)
        .put("required_stable_frames", requiredStableFrames)
        .put("new_card", newCard?.let { reason ->
            JSONObject().put("kind", reason.kind).apply {
                reason.jump?.let { put("jump", round(it * 10000) / 10000) }
                reason.imageChange?.let { put("image_change", round(it * 1000) / 1000) }
                reason.areaChange?.let { put("area_change", round(it * 100) / 100) }
                put("seconds_before", round((now - reason.at) / 10.0) / 100)
            }
        } ?: JSONObject.NULL)
        .put("seconds_since_previous_capture", secondsSincePreviousCapture ?: JSONObject.NULL)
        .put("source", "android")
}
