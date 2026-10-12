package com.cardscanner.inventory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.cardscanner.ai.Foil
import java.io.File

/** A captured card waiting to be checked: what the AI read and the best match found, if any */
data class ReviewItem(
    val id: Long,
    val created: String,
    val imageFile: File,
    val name: String,
    val number: String,
    val setCode: String,
    val foil: Foil,
    /** Scryfall id of the suggested printing (not confirmed), or null */
    val suggestedId: String?,
    /** Why it is here: "Check: matched by name", "Not found on Scryfall", "The AI could not read the card", ... */
    val reason: String,
) {
    fun image(): Bitmap? = BitmapFactory.decodeFile(imageFile.path)
}

/**
 * Cards to review in standalone mode (review.py on the server is its counterpart, and has its
 * own queue for the cards a phone sends in client mode): while adding automatically, anything
 * uncertain - printing not
 * confirmed, not found, nothing read - is queued with a copy of its photo, and scanning goes on.
 * Oldest first; kept across restarts.
 */
class ReviewQueue(context: Context) : SQLiteOpenHelper(context, "review.db", null, 2) {
    private val imageDir = File(context.filesDir, "review").apply { mkdirs() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE review_queue (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created TEXT NOT NULL,
                image TEXT NOT NULL,
                name TEXT NOT NULL DEFAULT '',
                number TEXT NOT NULL DEFAULT '',
                set_code TEXT NOT NULL DEFAULT '',
                foil TEXT NOT NULL DEFAULT 'UNKNOWN',
                suggested_id TEXT,
                reason TEXT NOT NULL DEFAULT ''
            )""")
        createReceipts(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createReceipts(db)
    }

    private fun createReceipts(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE review_receipts (capture_id TEXT PRIMARY KEY, item_id INTEGER NOT NULL)")
    }

    /** Receipts survive resolving/skipping an item, so a delayed replay cannot recreate it. */
    @Synchronized
    fun receipt(captureId: String): Long? =
        readableDatabase.rawQuery("SELECT item_id FROM review_receipts WHERE capture_id = ?", arrayOf(captureId)).use {
            if (it.moveToFirst()) it.getLong(0) else null
        }

    @Synchronized
    fun contains(id: Long): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM review_queue WHERE id = ?", arrayOf(id.toString())).use { it.moveToFirst() }

    @Synchronized
    fun add(photo: Bitmap, name: String, number: String, setCode: String, foil: Foil, suggestedId: String?, reason: String,
            captureId: String? = null): Long {
        captureId?.let { receipt(it) }?.let { return it }
        val file = File(imageDir, "${java.util.UUID.randomUUID()}.jpg")
        val db = writableDatabase
        try {
            file.outputStream().use {
                check(photo.compress(Bitmap.CompressFormat.JPEG, 90, it)) { "Could not save review photo" }
                it.fd.sync()
            }
            db.beginTransaction()
            try {
                val id = db.insertOrThrow("review_queue", null, ContentValues().apply {
                    put("created", Inventory.now())
                    put("image", file.name)
                    put("name", name)
                    put("number", number)
                    put("set_code", setCode)
                    put("foil", foil.name)
                    put("suggested_id", suggestedId)
                    put("reason", reason)
                })
                if (captureId != null) db.execSQL("INSERT INTO review_receipts VALUES (?, ?)", arrayOf<Any?>(captureId, id))
                db.setTransactionSuccessful()
                return id
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    /** Resolve an item (added or skipped): it and its photo go */
    @Synchronized
    fun remove(id: Long) {
        val db = writableDatabase
        val image = db.rawQuery("SELECT image FROM review_queue WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) File(imageDir, c.getString(0)) else null
        }
        db.delete("review_queue", "id = ?", arrayOf(id.toString()))
        image?.delete()
    }

    /** All items, oldest first */
    @Synchronized
    fun all(): List<ReviewItem> =
        readableDatabase.rawQuery("SELECT * FROM review_queue ORDER BY id", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    fun text(column: String) = c.getString(c.getColumnIndexOrThrow(column)).orEmpty()
                    add(ReviewItem(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        created = text("created"),
                        imageFile = File(imageDir, text("image")),
                        name = text("name"),
                        number = text("number"),
                        setCode = text("set_code"),
                        foil = runCatching { Foil.valueOf(text("foil")) }.getOrDefault(Foil.UNKNOWN),
                        suggestedId = c.getString(c.getColumnIndexOrThrow("suggested_id")),
                        reason = text("reason"),
                    ))
                }
            }
        }
}
