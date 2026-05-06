package com.bowltrack.data.format

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * JVM-only tests covering the relative / duration formatters that the
 * Home and History screens lean on. Pure functions, so no Robolectric
 * is required.
 */
class SessionFormattersTest {

    @Test
    fun `relative time under one minute renders as just now`() {
        val now = 1_000_000L
        val ts = now - 30_000L
        assertEquals("just now", formatRelative(ts, nowMillis = now))
    }

    @Test
    fun `relative time uses minutes hours and days as appropriate`() {
        val now = 1_700_000_000_000L
        assertEquals("5m ago", formatRelative(now - TimeUnit.MINUTES.toMillis(5), now))
        assertEquals("3h ago", formatRelative(now - TimeUnit.HOURS.toMillis(3), now))
        assertEquals("2d ago", formatRelative(now - TimeUnit.DAYS.toMillis(2), now))
    }

    @Test
    fun `duration formatter zero pads seconds`() {
        assertEquals("0:05", formatDuration(5f))
        assertEquals("1:00", formatDuration(60f))
        assertEquals("12:34", formatDuration(12 * 60 + 34f))
    }
}
