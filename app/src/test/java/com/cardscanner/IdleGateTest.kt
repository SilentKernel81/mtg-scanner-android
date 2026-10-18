package com.cardscanner

import com.cardscanner.detection.IdleGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleGateTest {
    private fun grey(level: Int) = ByteArray(1200) { level.toByte() }

    @Test
    fun busySceneIsAlwaysAnalysed() {
        val gate = IdleGate()
        assertTrue((0 until 20).all { gate.shouldProcess(grey(100), idle = false, now = it * 66L) })
    }

    @Test
    fun idleSceneIsAnalysedOnceEveryInterval() {
        val gate = IdleGate(idleIntervalMs = 500)
        gate.shouldProcess(grey(100), idle = true, now = 0)
        val analysed = (1..30).count { gate.shouldProcess(grey(100), idle = true, now = it * 66L) }
        assertEquals(3, analysed)  // ~2 s at 15 fps -> a frame every 500 ms
    }

    @Test
    fun aChangeWakesFullRateForAWhile() {
        val gate = IdleGate(idleIntervalMs = 250, wakeMs = 1500)
        gate.shouldProcess(grey(100), idle = true, now = 0)
        assertFalse(gate.shouldProcess(grey(100), idle = true, now = 66))
        assertTrue(gate.shouldProcess(grey(140), idle = true, now = 132))  // the card falls in
        assertTrue((1..20).all { gate.shouldProcess(grey(140), idle = true, now = 132 + it * 66L) })  // 1.3 s
        assertFalse(gate.shouldProcess(grey(140), idle = true, now = 132 + 1500 + 66))
    }

    @Test
    fun slowDriftAddsUp() {
        val gate = IdleGate()
        gate.shouldProcess(grey(100), idle = true, now = 0)
        // 2 levels per frame is below the threshold each time, but not against the analysed frame
        val woke = (1..10).any { gate.shouldProcess(grey(100 + 2 * it), idle = true, now = it * 10L) && it <= 4 }
        assertTrue(woke)
    }
}
