package com.cardscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Captures that are not settled yet, kept in the app's storage: a capture is saved here before
 * anything else happens to it and removed once it is identified (standalone) or the server has
 * answered (client mode). Without a network it waits here - also while the app is closed - and
 * is taken up again when the app starts (the init block of ScannerViewModel that reads `all()`).
 *
 * files/outbox/<capture id>.jpg (the photo), <capture id>_foil.jpg (the flat card for the ★/•
 * check, when that is another picture) and <capture id>.json, written last: a capture without
 * it was cut off while being saved and is dropped.
 */
class Outbox(context: Context) {
    private val dir = File(context.filesDir, "outbox").apply { mkdirs() }

    inner class Item(
        /** Also what the server knows the capture by: sent again, it is the same card */
        val captureId: String,
        /** The photo is the flat card itself (outline mode): no second picture */
        val foilIsCard: Boolean,
        private val hasFoil: Boolean,
    ) {
        internal val cardFile get() = File(dir, "$captureId.jpg")
        internal val foilFile get() = File(dir, "${captureId}_foil.jpg")
        internal val metaFile get() = File(dir, "$captureId.json")

        val deleteRequested: Boolean
            get() = synchronized(this@Outbox) { metaFile.isFile && JSONObject(metaFile.readText()).optBoolean("delete_requested") }

        /** Retries and queued removals belong to the server that first received this capture. */
        fun bindServer(url: String, station: String) = synchronized(this@Outbox) {
            val data = JSONObject(metaFile.readText())
            if (data.has("server_url")) {
                if (data.getString("server_url") != url || data.getString("station_id") != station)
                    throw com.cardscanner.server.ServerRefused("Restore the original server and station to finish this capture")
                return@synchronized
            }
            writeMeta(data.put("server_url", url).put("station_id", station))
        }

        fun requestDeletion() = synchronized(this@Outbox) {
            val data = JSONObject(metaFile.readText()).put("delete_requested", true)
            writeMeta(data)
        }

        private fun writeMeta(data: JSONObject) {
            val atomic = AtomicFile(metaFile)
            val stream = atomic.startWrite()
            try {
                stream.write(data.toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (e: Exception) { atomic.failWrite(stream); throw e }
        }

        /** The photo, or null if its file is gone or unreadable */
        fun card(): Bitmap? = BitmapFactory.decodeFile(cardFile.path)

        /** Decode only enough pixels for the scan list when resuming a large backlog. */
        fun thumbnail(): Bitmap? {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(cardFile.path, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            options.inSampleSize = 1
            while (options.outHeight / (options.inSampleSize * 2) >= 280) options.inSampleSize *= 2
            options.inJustDecodeBounds = false
            return BitmapFactory.decodeFile(cardFile.path, options)
        }

        /** The flat card for the ★/• check - `card` itself, the second picture, or null (no outline) */
        fun foil(card: Bitmap): Bitmap? = when {
            foilIsCard -> card
            hasFoil -> BitmapFactory.decodeFile(foilFile.path)
            else -> null
        }
    }

    /** Save a capture (card: the photo; foilCard: the flat card for the ★/• check, or null) */
    @Synchronized
    fun add(card: Bitmap, foilCard: Bitmap?): Item {
        val item = Item(UUID.randomUUID().toString(), foilIsCard = foilCard === card, hasFoil = foilCard != null && foilCard !== card)
        try {
            save(card, item.cardFile)
            if (foilCard != null && foilCard !== card) save(foilCard, item.foilFile)
            val meta = AtomicFile(item.metaFile)
            val stream = meta.startWrite()
            try {
                stream.write(JSONObject().put("foil_is_card", item.foilIsCard).toString().toByteArray(Charsets.UTF_8))
                meta.finishWrite(stream)
            } catch (e: Exception) {
                meta.failWrite(stream)
                throw e
            }
        } catch (e: Exception) {
            remove(item)
            throw e
        }
        return item
    }

    @Synchronized
    fun remove(item: Item) {
        item.metaFile.delete()
        item.cardFile.delete()
        item.foilFile.delete()
    }

    /** The waiting captures, oldest first; leftovers of a capture cut off while saving are deleted */
    @Synchronized
    fun all(): List<Item> {
        val files = dir.listFiles().orEmpty()
        val items = files.filter { it.extension == "json" }.sortedBy { it.lastModified() }.mapNotNull { meta ->
            val id = meta.nameWithoutExtension
            val foilIsCard = runCatching { JSONObject(meta.readText()).optBoolean("foil_is_card") }.getOrDefault(false)
            Item(id, foilIsCard, hasFoil = File(dir, "${id}_foil.jpg").isFile).takeIf { it.cardFile.isFile }
                ?: run { meta.delete(); null }
        }
        val kept = items.flatMap { listOf(it.cardFile, it.foilFile, it.metaFile) }.toSet()
        files.filter { it !in kept }.forEach { it.delete() }
        return items
    }

    @Synchronized
    fun count(): Int = dir.listFiles().orEmpty().count { it.extension == "json" }

    private fun save(image: Bitmap, file: File) {
        file.outputStream().use {
            check(image.compress(Bitmap.CompressFormat.JPEG, 95, it)) { "Could not save capture" }
            it.fd.sync()
        }
    }
}
