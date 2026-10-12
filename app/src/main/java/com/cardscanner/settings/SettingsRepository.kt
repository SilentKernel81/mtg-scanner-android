package com.cardscanner.settings

import android.content.Context
import com.cardscanner.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns live settings and serializes secure persistence away from typing/camera UI work. */
internal class SettingsRepository(
    context: Context,
    scope: CoroutineScope,
    private val storage: SettingsStorage = SettingsStorage(context),
) {
    private val loaded = storage.load()
    private val current = MutableStateFlow(loaded.settings)
    val settings = current.asStateFlow()
    private val failure = MutableStateFlow(loaded.credentialError)
    val error = failure.asStateFlow()
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private val writeLock = Mutex()

    init {
        // Start immediately so the final flush also runs if the owner is cleared right away.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                for (ignored in writes) flush()
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { flush() }
                writes.close()
            }
        }
    }

    fun update(settings: AppSettings) {
        current.value = settings
        writes.trySend(Unit)
    }

    internal suspend fun flush() = withContext(Dispatchers.IO) {
        writeLock.withLock {
            try {
                // Always read the latest snapshot under the lock, so queued writes and a
                // lifecycle flush cannot overwrite a newer edit with an older snapshot.
                failure.value = storage.save(current.value)
            } catch (e: SettingsStorage.SaveFailed) {
                failure.value = e.message
            }
        }
    }
}
