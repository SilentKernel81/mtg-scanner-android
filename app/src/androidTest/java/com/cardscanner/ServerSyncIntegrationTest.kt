package com.cardscanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cardscanner.server.ScannerServer
import com.cardscanner.server.ServerRefused
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in contract test against a disposable server seeded with synthetic cards only. */
@RunWith(AndroidJUnit4::class)
class ServerSyncIntegrationTest {
    @Test fun removesExactDuplicateAndResolvesCollectionTransferOverHttp() {
        val url = InstrumentationRegistry.getArguments().getString("serverValidationUrl")
        assumeNotNull(url)
        val http = OkHttpClient()
        fun fixture(path: String, post: Boolean = false): JSONObject {
            val request = Request.Builder().url("$url/__validation/$path").apply {
                if (post) post(ByteArray(0).toRequestBody())
            }.build()
            return http.newCall(request).execute().use {
                assertTrue(it.isSuccessful)
                JSONObject(it.body.string())
            }
        }
        val initial = fixture("state")
        val phone = ScannerServer(url!!, "phone-a", "Integration", "", http)
        val other = ScannerServer(url, "phone-b", "Other", "", http)
        val first = initial.getInt("first")
        val second = initial.getInt("second")
        val firstKey = initial.getString("first_key")
        val secondKey = initial.getString("second_key")
        assertEquals("scanned", phone.syncCapture(first, syncKey = firstKey))
        assertThrows(ServerRefused::class.java) { phone.syncCapture(second, true, "wrong-identity") }
        assertEquals(3, fixture("state").getInt("scanned"))
        assertEquals("removed", phone.syncCapture(second, true, secondKey))
        assertEquals("resolved", phone.syncCapture(second, true, secondKey))
        assertEquals(2, fixture("state").getInt("scanned"))
        assertEquals("scanned", phone.syncCapture(first, syncKey = firstKey))
        assertEquals("scanned", other.syncCapture(initial.getInt("other"), syncKey = initial.getString("other_key")))
        val moved = fixture("collect", post = true)
        assertEquals(1, moved.getInt("collected"))
        assertEquals("resolved", phone.syncCapture(first, syncKey = firstKey))
        assertEquals("resolved", phone.syncCapture(first, true, firstKey))
        val final = fixture("state")
        assertEquals(1, final.getInt("collected"))
        assertEquals(1, final.getInt("other_scanned"))
    }
}
