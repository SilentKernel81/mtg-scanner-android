package com.cardscanner

import com.cardscanner.server.ScannerServer
import com.cardscanner.server.ServerCard
import com.cardscanner.server.ServerOutcome
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
