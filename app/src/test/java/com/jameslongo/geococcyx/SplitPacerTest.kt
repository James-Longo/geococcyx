package com.jameslongo.geococcyx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitPacerTest {
    private val mile = 1609.344
    private fun pacer() = SplitPacer(365 / mile, 385 / mile, mile / 4)  // 6:05-6:25/mi, quarter-mile splits

    /** Runs [secPerMile] for [miles] on [percent] slope (null: no elevation), once a second; returns the splits. */
    private fun SplitPacer.run(secPerMile: Double, miles: Double, percent: Double?, from: Pair<Long, Double> = 0L to 0.0): List<SplitPacer.Split> {
        val out = ArrayList<SplitPacer.Split>()
        val v = mile / secPerMile
        var t = from.first; var m = from.second
        val end = m + miles * mile
        while (m < end) { tick(t, m, percent)?.let { out += it }; t += 1000; m += v }
        return out
    }

    @Test
    fun onPaceIsSilent() {
        val splits = pacer().run(375.0, 2.0, 0.0)
        assertTrue(splits.size in 7..8)  // the 8th lands on the last tick or the next
        assertTrue(splits.all { it.zone == 0 })
    }

    @Test
    fun climbAtEvenEffortIsSilent() {
        // 7:03/mi up 3.5%: what raw pace calls too slow is 6:17 of effort.
        val splits = pacer().run(423.0, 1.0, 3.5)
        assertTrue(splits.isNotEmpty() && splits.all { it.zone == 0 && it.graded })
        assertEquals(377.0, splits.last().pace * mile, 2.0)
    }

    @Test
    fun outOfRangeSplitsAlert() {
        assertTrue(pacer().run(345.0, 1.0, 0.0).all { it.zone == -1 })  // 5:45 on the flat
        assertTrue(pacer().run(400.0, 1.0, 0.0).all { it.zone == 1 })   // 6:40 on the flat
        // 6:15 raw down a 4% hill is easy running: 6:43 of effort.
        assertTrue(pacer().run(375.0, 1.0, -4.0).all { it.zone == 1 })
    }

    @Test
    fun withoutElevationItJudgesRawPace() {
        val splits = pacer().run(375.0, 1.0, null)
        assertTrue(splits.all { it.zone == 0 && !it.graded })
        assertEquals(375.0, splits.first().pace * mile, 2.0)
    }

    @Test
    fun quartersEndWithTheMileOverAHalfMarathon() {
        // The service's loop: once a second, auto-lap first (from wherever the last lap closed), then the pacer.
        val p = SplitPacer(365 / mile, 385 / mile, mile / 4, mile)
        var t = 0L; var m = 0.0; var lapStart = 0.0
        val perLap = ArrayList<Int>(); var inLap = 0; var lapEnders = 0
        while (m < 13.1 * mile) {
            if (m - lapStart >= mile) {
                p.lap(t, m)?.let { inLap++; lapEnders++ }
                perLap += inLap; inLap = 0; lapStart = m
            }
            p.tick(t, m, 0.0)?.let { inLap++ }
            t += 1000; m += mile / 375 + (t / 1000 % 7) * 0.3  // uneven steps, as Health Services delivers
        }
        assertEquals(13, perLap.size)
        assertTrue("every mile has four quarters: $perLap", perLap.all { it == 4 })
        assertEquals(13, lapEnders)  // and the fourth always closes with the lap, never on its own
    }

    @Test
    fun pausedTimeDoesNotCount() {
        // Active time and distance both stand still while paused, so a split spans the pause unharmed.
        val p = pacer()
        var t = 0L; var m = 0.0
        repeat(50) { p.tick(t, m, 0.0); t += 1000; m += mile / 375 }
        repeat(120) { assertFalse(p.tick(t, m, 0.0) != null) }  // paused: neither moves
        var split: SplitPacer.Split? = null
        while (split == null) { split = p.tick(t, m, 0.0); t += 1000; m += mile / 375 }
        assertEquals(375.0, split.pace * mile, 3.0)
    }
}
