package com.timer

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatterTest {

    @Test
    fun `formats minutes and seconds`() {
        assertEquals("00:00", formatRemainingTime(0))
        assertEquals("01:05", formatRemainingTime(65_000))
        assertEquals("59:59", formatRemainingTime(3_599_000))
    }

    @Test
    fun `rounds partial seconds up`() {
        assertEquals("00:01", formatRemainingTime(1))
        assertEquals("00:01", formatRemainingTime(1_000))
        assertEquals("00:02", formatRemainingTime(1_001))
    }

    @Test
    fun `adds hours from one hour on`() {
        assertEquals("1:00:00", formatRemainingTime(3_600_000))
        assertEquals("1:02:05", formatRemainingTime(3_725_000))
    }

    @Test
    fun `negative values are clamped to zero`() {
        assertEquals("00:00", formatRemainingTime(-5_000))
    }
}
