package com.cardscanner.scryfall

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Offline card data: what is downloaded, or the download's progress / error */
data class CardDataState(val printings: Int? = null, val updatedAt: String? = null, val busy: Boolean = false,
                         val message: String? = null, val updateAvailable: Boolean? = null)

internal class CardDataRepository(private val cardData: CardDatabase, private val scope: CoroutineScope) {
    private val _cardDataState = MutableStateFlow(CardDataState())
    val state: StateFlow<CardDataState> = _cardDataState.asStateFlow()

    fun refresh(message: String? = null) {
        val info = cardData.info()
        _cardDataState.value = CardDataState(info?.first, info?.second, message = message)
    }

    /** Check Scryfall for newer card data (Settings) */
    fun checkCardDataUpdate() {
        scope.launch(Dispatchers.IO) {
            _cardDataState.update { it.copy(updateAvailable = cardData.updateAvailable()) }
        }
    }

    /** Download (or update) the offline card data - a few minutes, ~80 MB */
    fun downloadCardData() {
        if (_cardDataState.value.busy) return
        _cardDataState.update { it.copy(busy = true, message = "Downloading card data from Scryfall…") }
        scope.launch(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            try {
                cardData.download { progress -> _cardDataState.update { it.copy(message = progress) } }
                refresh("Done in ${(System.currentTimeMillis() - started) / 1000} s")
            } catch (e: Exception) {
                Log.e(TAG, "Card data download failed", e)
                refresh("Download failed: ${e.message}")
            }
        }
    }

    fun deleteCardData() {
        scope.launch(Dispatchers.IO) { cardData.delete(); refresh() }
    }
    fun start() { scope.launch(Dispatchers.IO) { refresh() } }
    companion object { private const val TAG = "Scanner" }
}
