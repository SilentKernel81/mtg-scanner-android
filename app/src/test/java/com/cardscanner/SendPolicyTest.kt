package com.cardscanner

import com.cardscanner.server.SendPolicy
import com.cardscanner.server.ServerRefused
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** When a capture that cannot be sent stops being tried - the same rule as the scanner server's stations */
class SendPolicyTest {
    @Test
    fun a_400_or_413_is_refused_for_good() {
        assertTrue(SendPolicy.refusedForGood(ServerRefused("not a picture", 400)))
        assertTrue(SendPolicy.refusedForGood(ServerRefused("too large", 413)))
        assertFalse(SendPolicy.refusedForGood(ServerRefused("wrong token", 401)))
        assertFalse(SendPolicy.refusedForGood(ServerRefused("proxy page", 404)))
        assertFalse(SendPolicy.refusedForGood(IOException("HTTP 500: boom")))
    }

    @Test
    fun other_answers_count_as_odd() {
        for (code in listOf(401, 403, 404, 429)) assertTrue("HTTP $code", SendPolicy.oddAnswer(ServerRefused("x", code)))
        assertTrue(SendPolicy.oddAnswer(IOException("HTTP 502: <html>Bad gateway</html>")))   // a 5xx or an answer that is not JSON
        // a refusal for good is not "odd": it is parked at once, not counted
        assertFalse(SendPolicy.oddAnswer(ServerRefused("not a picture", 400)))
    }

    @Test
    fun no_answer_at_all_never_counts() {
        assertFalse(SendPolicy.oddAnswer(UnknownHostException("no such host")))
        assertFalse(SendPolicy.oddAnswer(SocketTimeoutException("timeout")))
        assertFalse(SendPolicy.oddAnswer(IOException("Connection reset")))
        // decided on the phone (no address, the original server is needed): Settings fix it
        assertFalse(SendPolicy.oddAnswer(ServerRefused("No server address - set it in Settings")))
        assertFalse(SendPolicy.oddAnswer(IllegalStateException("x")))
    }

    @Test
    fun the_limit_is_the_stations() {
        assertEquals(20, SendPolicy.PARK_AFTER_ANSWERS)
    }
}
