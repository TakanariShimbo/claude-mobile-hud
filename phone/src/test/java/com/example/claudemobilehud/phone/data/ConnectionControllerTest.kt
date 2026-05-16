package com.example.claudemobilehud.phone.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ConnectionControllerTest {

    @Test
    fun `backoff doubles up to cap with jitter bounded`() {
        // 固定 RNG (jitter = 0) で base 値だけ検証
        val zeroJitter = Random(0L).let { object : Random() {
            override fun nextBits(bitCount: Int): Int = 0
            override fun nextDouble(from: Double, until: Double): Double = 0.0
        } }
        assertEquals(1_000L, ConnectionController.computeBackoffMs(1, zeroJitter))
        assertEquals(2_000L, ConnectionController.computeBackoffMs(2, zeroJitter))
        assertEquals(4_000L, ConnectionController.computeBackoffMs(3, zeroJitter))
        assertEquals(8_000L, ConnectionController.computeBackoffMs(4, zeroJitter))
        assertEquals(16_000L, ConnectionController.computeBackoffMs(5, zeroJitter))
        assertEquals(30_000L, ConnectionController.computeBackoffMs(6, zeroJitter))
        assertEquals(30_000L, ConnectionController.computeBackoffMs(100, zeroJitter))
    }

    @Test
    fun `backoff jitter stays within +-25 percent`() {
        for (attempt in 1..7) {
            for (seed in 1..50) {
                val delay = ConnectionController.computeBackoffMs(attempt, Random(seed.toLong()))
                val shift = (attempt - 1).coerceIn(0, 5)
                val base = (1_000L shl shift).coerceAtMost(30_000L)
                val lo = (base * 0.75).toLong().coerceAtLeast(100L)
                val hi = (base * 1.25).toLong()
                assertTrue(delay in lo..hi, "attempt=$attempt seed=$seed delay=$delay base=$base")
            }
        }
    }
}
