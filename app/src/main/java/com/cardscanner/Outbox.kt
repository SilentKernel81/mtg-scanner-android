package com.cardscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Captures that are not settled yet, kept in the app's storage: a capture is saved here before
 * anything else happens to it and removed once it is identified (standalone) or the server has
 * answered (client mode). Without a network it waits here - also while the app is closed - and
 * is taken up again when the app starts (ScannerViewModel.resumeOutbox).
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

        /** The photo, or null if its file is gone or unreadable */
        fun card(): Bitmap? = BitmapFactory.decodeFile(cardFile.path)

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
        save(card, item.cardFile)
        if (foilCard != null && foilCard !== card) save(foilCard, item.foilFile)
        item.metaFile.writeText(JSONObject().put("foil_is_card", item.foilIsCard).toString())
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
        file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    }
}
