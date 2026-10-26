package com.jameslongo.geococcyx

import org.junit.Assert.assertEquals
import org.junit.Test

class OdometerTest {
    // Due north from a made-up spot: one degree of latitude is ~111.2 km, so 4 m is this many degrees.
    private val step = 4.0 / 111_195.0
    private fun loc(sec: Int, north: Double) = Loc(sec * 1000L, 44.0 + north * step, -68.0, null)

    @Test
    fun followsTheGpsTrackNotTheTotal() {
        val o = Odometer()
        for (s in 0..100) {
            o.gps(loc(s, s.toDouble()))
            // Health Services counting 10% short, as it did in the first mile of the 2026-10-25 5k.
            o.total(s * 3.6, s * 1000L)
        }
        assertEquals(400.0, o.meters, 0.5)
    }

    @Test
    fun totalFillsInWhileGpsIsMissing() {
        val o = Odometer()
        for (s in 0..10) { o.gps(loc(s, s.toDouble())); o.total(s * 4.0, s * 1000L) }
        // GPS drops out for 30 s; the total keeps going, but only counts once the last fix is 10 s old.
        for (s in 11..40) o.total(s * 4.0, s * 1000L)
        assertEquals(40.0 + (40 - 20) * 4.0, o.meters, 0.5)
        // The fix comes back 120 m further on: it re-anchors instead of drawing a line across the gap.
        o.gps(loc(41, 70.0))
        o.gps(loc(42, 71.0))
        assertEquals(120.0 + 4.0, o.meters, 0.5)
    }

    @Test
    fun movingWhilePausedDoesNotCount() {
        val o = Odometer()
        for (s in 0..10) o.gps(loc(s, s.toDouble()))
        o.total(40.0, 10_000)
        o.pause()
        // Walked 50 m while paused. The first total and fix after resuming only set new baselines.
        o.total(90.0, 30_000)
        o.gps(loc(31, 22.5))
        o.gps(loc(32, 23.5))
        assertEquals(44.0, o.meters, 0.5)
    }

    @Test
    fun recoveredRunCarriesOnFromTheLastFix() {
        val o = Odometer(1609.344, loc(0, 0.0))
        o.gps(loc(1, 1.0))
        assertEquals(1613.344, o.meters, 0.5)
    }
}
