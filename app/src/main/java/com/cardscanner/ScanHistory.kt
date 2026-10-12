package com.cardscanner

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** Bounded presentation history; durable capture state lives in the outbox and collection. */
internal class ScanHistory {
    private val nextId = AtomicLong()
    private val current = MutableStateFlow<List<ScanResult>>(emptyList())
    val scans = current.asStateFlow()

    fun show(card: Bitmap, serverMode: Boolean): Long {
        val thumbHeight = 280
        val thumbnail = Bitmap.createScaledBitmap(card, (thumbHeight * card.width / card.height).coerceAtLeast(1), thumbHeight, true)
        val id = nextId.incrementAndGet()
        val note = if (serverMode) "Sending to the server…" else null
        update { (listOf(ScanResult(id, thumbnail, note = note)) + it).take(100) }
        return id
    }

    fun update(transform: (List<ScanResult>) -> List<ScanResult>) = current.update(transform)
    fun clear() { current.value = emptyList() }
    fun fail(id: Long, error: String) = update { list ->
        list.map { if (it.id == id) it.copy(status = ScanResult.Status.FAILED, error = error) else it }
    }
    fun note(id: Long, text: String) = update { list ->
        list.map { if (it.id == id && it.status == ScanResult.Status.IDENTIFYING) it.copy(note = text) else it }
    }
}
