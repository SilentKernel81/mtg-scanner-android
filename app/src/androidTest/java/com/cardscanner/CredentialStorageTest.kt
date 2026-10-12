package com.cardscanner

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.cardscanner.ai.Provider
import com.cardscanner.settings.CredentialCipher
import com.cardscanner.settings.KeystoreCredentialCipher
import com.cardscanner.settings.SettingsRepository
import com.cardscanner.settings.SettingsStorage
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Real Android Keystore, with unique aliases/preferences and synthetic credentials only. */
class CredentialStorageTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "credential-test-${UUID.randomUUID()}"
    private val alias = "$prefix.key"
    private val context = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$prefix-$name", mode)
    }
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val cipher = KeystoreCredentialCipher(alias)
    private val storage = SettingsStorage(context, cipher)
    private fun secrets() = AppSettings(apiKeys = Provider.entries.associateWith { "synthetic-${it.id}-secret" },
        stationToken = "synthetic-station-secret", stationId = "station-test", minSharpness = 310)

    @After fun cleanup() {
        base.deleteSharedPreferences("$prefix-settings")
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }

    @Test fun roundTripEncryptsAllCredentialsAndPreservesOrdinarySettings() {
        val original = secrets()
        assertNull(storage.save(original))
        val loaded = storage.load()
        assertNull(loaded.credentialError)
        assertEquals(original.apiKeys, loaded.settings.apiKeys)
        assertEquals(original.stationToken, loaded.settings.stationToken)
        assertEquals(original.stationId, loaded.settings.stationId)
        assertEquals(310, loaded.settings.minSharpness)
        assertTrue(prefs.contains(SettingsStorage.CREDENTIALS))
        val disk = prefs.all.toString()
        original.apiKeys.values.forEach { assertFalse(disk.contains(it)) }
        assertFalse(disk.contains(original.stationToken))
        assertFalse(original.toString().contains(original.stationToken))
        assertFalse(prefs.contains("station_token"))
        Provider.entries.forEach { assertFalse(prefs.contains("key_${it.id}")) }
        val unchanged = prefs.getString(SettingsStorage.CREDENTIALS, null)
        storage.save(loaded.settings.copy(captureSound = false, addedSound = false, reviewSound = false))
        assertEquals(unchanged, prefs.getString(SettingsStorage.CREDENTIALS, null))
    }

    @Test fun legacyMigrationRemovesPlaintextOnlyAfterEncryption() {
        val original = secrets()
        prefs.edit().apply {
            original.apiKeys.forEach { (provider, key) -> putString("key_${provider.id}", key) }
            putString("station_token", original.stationToken)
            putString("station_id", "existing-station")
            putInt("rotation", 90)
        }.commit()
        val loaded = storage.load()
        assertNull(loaded.credentialError)
        assertEquals(original.apiKeys, loaded.settings.apiKeys)
        assertEquals(original.stationToken, loaded.settings.stationToken)
        assertEquals("existing-station", loaded.settings.stationId)
        assertEquals(90, loaded.settings.rotation)
        assertFalse(prefs.contains("station_token"))
        Provider.entries.forEach { assertFalse(prefs.contains("key_${it.id}")) }
        assertEquals(original.apiKeys, storage.load().settings.apiKeys)
    }

    @Test fun failedMigrationKeepsRecoverableLegacyCopy() {
        prefs.edit().putString("key_gemini", "synthetic-legacy-secret").commit()
        val failing = SettingsStorage(context, object : CredentialCipher {
            override fun encrypt(plaintext: String): String = error("synthetic-sensitive-error")
            override fun decrypt(encoded: String): String = error("unused")
        })
        val loaded = failing.load()
        assertEquals(SettingsStorage.MIGRATION_ERROR, loaded.credentialError)
        assertEquals("synthetic-legacy-secret", loaded.settings.apiKey(Provider.GEMINI))
        assertEquals("synthetic-legacy-secret", prefs.getString("key_gemini", null))
        assertFalse(prefs.contains(SettingsStorage.CREDENTIALS))
        try { failing.save(secrets()); fail("Expected secure save failure") }
        catch (e: SettingsStorage.SaveFailed) {
            assertNull(e.cause)
            assertFalse(e.message.orEmpty().contains("synthetic"))
        }
        assertFalse(prefs.contains(SettingsStorage.CREDENTIALS))
    }

    @Test fun tamperedPayloadIsPreservedUntilExplicitCredentialReplacement() {
        storage.save(secrets())
        val original = prefs.getString(SettingsStorage.CREDENTIALS, null)!!
        val parts = original.split(':')
        val tampered = parts.take(2).joinToString(":") + ":" +
            (if (parts[2][0] == 'A') "B" else "A") + parts[2].drop(1)
        prefs.edit().putString(SettingsStorage.CREDENTIALS, tampered).commit()
        val loaded = storage.load()
        assertEquals(SettingsStorage.READ_ERROR, loaded.credentialError)
        assertTrue(loaded.settings.apiKeys.values.all { it.isEmpty() })
        assertEquals(SettingsStorage.READ_ERROR, storage.save(loaded.settings.copy(rotation = 180)))
        assertEquals(tampered, prefs.getString(SettingsStorage.CREDENTIALS, null))
        assertNull(storage.save(secrets()))
        assertEquals(secrets().stationToken, storage.load().settings.stationToken)
    }

    @Test fun lostKeyDoesNotGenerateReplacementDuringRead() {
        storage.save(secrets())
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        val payload = prefs.getString(SettingsStorage.CREDENTIALS, null)
        val loaded = storage.load()
        assertEquals(SettingsStorage.READ_ERROR, loaded.credentialError)
        storage.save(loaded.settings.copy(captureSound = false, addedSound = false, reviewSound = false))
        assertEquals(payload, prefs.getString(SettingsStorage.CREDENTIALS, null))
        assertFalse(store.containsAlias(alias))
        storage.save(secrets())
        assertEquals(secrets().apiKeys, storage.load().settings.apiKeys)
    }

    @Test fun encryptionUsesFreshIvAndCredentialsCanBeCleared() {
        val first = cipher.encrypt("synthetic-value")
        val second = cipher.encrypt("synthetic-value")
        assertNotEquals(first, second)
        assertEquals("synthetic-value", cipher.decrypt(first))
        storage.save(secrets())
        storage.save(secrets().copy(apiKeys = emptyMap(), stationToken = ""))
        assertFalse(prefs.contains(SettingsStorage.CREDENTIALS))
        assertTrue(storage.load().settings.apiKeys.values.all { it.isEmpty() })
    }

    @Test fun legacySoundPreferenceMigratesWithoutUnmuting() {
        for (enabled in listOf(false, true)) {
            prefs.edit().clear().putBoolean("sounds", enabled).commit()
            val loaded = storage.load().settings
            assertEquals(enabled, loaded.captureSound)
            assertEquals(enabled, loaded.addedSound)
            assertEquals(enabled, loaded.reviewSound)
            storage.save(loaded)
            assertFalse(prefs.contains("sounds"))
            val reloaded = storage.load().settings
            assertEquals(enabled, reloaded.captureSound)
            assertEquals(enabled, reloaded.addedSound)
            assertEquals(enabled, reloaded.reviewSound)
        }
    }

    @Test fun soundFlagsPersistIndependentlyAndOverrideLegacyDefault() {
        prefs.edit().putBoolean("sounds", false).putBoolean("sound_added", true).commit()
        val migrated = storage.load().settings
        assertFalse(migrated.captureSound)
        assertTrue(migrated.addedSound)
        assertFalse(migrated.reviewSound)
        for (mask in 0..7) {
            storage.save(migrated.copy(captureSound = mask and 1 != 0,
                addedSound = mask and 2 != 0, reviewSound = mask and 4 != 0))
            val loaded = storage.load().settings
            assertEquals(mask and 1 != 0, loaded.captureSound)
            assertEquals(mask and 2 != 0, loaded.addedSound)
            assertEquals(mask and 4 != 0, loaded.reviewSound)
        }
    }

    @Test fun rapidEditsAndImmediateCancellationPersistLatestSnapshot() = runBlocking {
        val job = SupervisorJob()
        val repository = SettingsRepository(context, CoroutineScope(job + Dispatchers.Default), storage)
        repeat(50) { repository.update(secrets().copy(stationToken = "synthetic-token-$it", rotation = it)) }
        job.cancelAndJoin()
        val loaded = storage.load()
        assertEquals("synthetic-token-49", loaded.settings.stationToken)
        assertEquals(49, loaded.settings.rotation)
        assertNull(repository.error.value)
    }
}
