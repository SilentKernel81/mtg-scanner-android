package com.cardscanner

import com.cardscanner.server.ScannerServer
import com.cardscanner.server.ServerCard
import com.cardscanner.server.ServerOutcome
import com.cardscanner.server.ServerRefused
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** The server's answers to a capture, as app.py's station_capture really sent them (2026-10-08) */
class ServerOutcomeTest {
    @Test
    fun added() {
        val outcome = ServerOutcome.parse(JSONObject("""{"capture":1,"card":{"finish":"Regular","name":"Nori, Teller of Tales","number":"161","quantity":1,"set":"The Hobbit"},"read":{"foil":"non-foil","name":"Nori, Teller of Tales","number":"0161","reader":"light-ocr","set":"HOB"},"seconds":0.93,"status":"added","success":true}"""))
        assertEquals(ServerOutcome.Status.ADDED, outcome.status)
        assertEquals(1, outcome.capture)
        assertEquals(ServerCard("Nori, Teller of Tales", "The Hobbit", "161", "Regular"), outcome.card)
        assertEquals("light-ocr", outcome.reader)
        assertEquals("0161", outcome.readNumber)
    }

    @Test
    fun theLineUnderACardSaysWhoReadItAndWhyNot() {
        fun line(json: String) = ServerOutcome.parse(JSONObject(json)).statusLine
        val card = """"card":{"finish":"Regular","name":"X","number":"1","set":"S"}"""
        // the full-card OCR, the picture and the collector line (evidence mode), a vision AI
        assertEquals("Added on the server · OCR", line("""{"status":"added",$card,"read":{"name":"X","number":"1","set":"s","reader":"light-ocr"}}"""))
        assertEquals("Added on the server · picture", line("""{"status":"added",$card,"read":{"name":"X","number":"1","set":"s","reader":"picture"}}"""))
        assertEquals("Added on the server · picture + collector line", line("""{"status":"added",$card,"read":{"name":"X","number":"1","set":"s","reader":"picture+strip"}}"""))
        assertEquals("Added on the server · AI (local/qwen3.5:9b-q8_0)", line("""{"status":"added",$card,"read":{"name":"X","number":"1","set":"s","reader":"local/qwen3.5:9b-q8_0"}}"""))
        // an answer without a reader (an older server, or none said) adds nothing
        assertEquals("Added on the server", line("""{"status":"added",$card}"""))
        // a review: the reason, and who read it when somebody did
        assertEquals("Sent to server review · printing not confirmed · read by AI (gemini-2.5)",
            line("""{"status":"review","reason":"printing not confirmed","read":{"name":"X","reader":"gemini-2.5"}}"""))
        assertEquals("Sent to server review · not read", line("""{"status":"review","reason":"not read","read":null}"""))
        // nothing to say for a card still being read or an error
        assertNull(line("""{"status":"pending","capture":2}"""))
    }

    @Test
    fun notRead() {
        val outcome = ServerOutcome.parse(JSONObject("""{"capture":3,"read":null,"reason":"not read","seconds":0.58,"status":"review","success":true}"""))
        assertEquals(ServerOutcome.Status.REVIEW, outcome.status)
        assertEquals("not read", outcome.reason)
        assertNull(outcome.card)
        assertNull(outcome.reader)
        assertEquals("", outcome.readName)
    }

    @Test
    fun pendingAndError() {
        assertEquals(ServerOutcome.Status.PENDING, ServerOutcome.parse(JSONObject("""{"capture":2,"status":"pending","success":true}""")).status)
        val error = ServerOutcome.parse(JSONObject("""{"capture":4,"status":"error","message":"disk full","success":false}"""))
        assertEquals(ServerOutcome.Status.ERROR, error.status)
        assertEquals("disk full", error.message)
    }

    @Test
    fun addresses() {
        assertEquals("http://192.168.1.20:5000", ScannerServer.normalize(" 192.168.1.20:5000/ "))
        assertEquals("https://cards.example", ScannerServer.normalize("https://cards.example/"))
        assertEquals("", ScannerServer.normalize("  "))
    }

    @Test
    fun invalidAddressesAreRecoverableConfigurationErrors() {
        for (url in listOf("", " ", "http://", "ftp://cards.example", "http://bad host:5000", "https://cards.example?token=x", "https://user:pass@cards.example")) {
            assertThrows("Address: $url", ServerRefused::class.java) { ScannerServer.validatedUrl(url) }
            // This previously constructed an invalid Request before send() could validate it.
            assertThrows(ServerRefused::class.java) {
                ScannerServer(url, "phone-test", "Test", "", OkHttpClient()).test()
            }
        }
    }

    @Test
    fun addressesSupportLocalPortsAndProxyPaths() {
        assertEquals("http://192.168.1.20:5000/", ScannerServer.validatedUrl("192.168.1.20:5000").toString())
        assertEquals("https://cards.example/scanner", ScannerServer.validatedUrl("https://cards.example/scanner/").toString())
    }
}
