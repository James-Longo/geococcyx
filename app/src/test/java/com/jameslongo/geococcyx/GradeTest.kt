package com.jameslongo.geococcyx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.random.Random

class GradeTest {
    private val mile = 1609.344
    private fun gapPerMile(secPerMile: Int, percent: Double) = Grade.adjust(secPerMile / mile, percent) * mile

    @Test
    fun climbsCostMoreThanDescentsGiveBack() {
        assertEquals(1.0, Grade.factor(0.0), 0.0)
        for (g in listOf(1.0, 3.0, 6.0)) assertTrue(Grade.factor(g) - 1 > 1 - Grade.factor(-g))
        // Past -8% braking eats into the gain, so steeper isn't faster.
        assertTrue(Grade.factor(-12.0) > Grade.factor(-8.0))
    }

    /** Half-mile splits from James's summer races, against a 6:05-6:25/mi pacer. */
    @Test
    fun raceSplitsAgainstGoalRange() {
        val fast = 365; val slow = 385
        // Friend Memorial 5k, mile 2: 7:03 up 3.5%. Raw pace says too slow; the effort was on target.
        assertTrue(gapPerMile(423, 3.5).toInt() in fast..slow)
        // Friend Memorial 5k, mile 0.5: 5:44 down 3.9%. Raw pace says too fast; the effort was on target.
        assertTrue(gapPerMile(344, -3.9).toInt() in fast..slow)
        // Woodcock Waddle, first half mile: 5:31 up 1.2%. Too fast either way: the start that cost the race.
        assertTrue(gapPerMile(331, 1.2) < fast)
        // Flat ground: unchanged.
        assertEquals(375.0, gapPerMile(375, 0.0), 1e-9)
    }

    @Test
    fun slopeFromNoisyBarometer() {
        val g = Grade()
        assertNull(g.percent)
        val rnd = Random(1)
        var m = 0.0
        while (m < 300) {
            g.add(m, 10 + 0.03 * m + rnd.nextDouble(-0.15, 0.15))  // 3% climb, ±15 cm of jitter
            m += 3.0  // about 6:10/mi, once a second
        }
        assertEquals(3.0, g.percent!!, 0.5)
        // Standing at the top doesn't change it or pile up readings.
        repeat(600) { g.add(m, 10 + 0.03 * m) }
        assertEquals(3.0, g.percent!!, 0.5)
    }

    @Test
    fun noSlopeUntilEnoughDistance() {
        val g = Grade()
        g.add(0.0, 10.0); g.add(30.0, 12.0)
        assertNull(g.percent)
        assertEquals(17.0, Grade.adjust(17.0, null), 0.0)
    }

    @Test
    fun elevationIsJournaledAndExported() {
        val rec = Recorder(File.createTempFile("run", ".journal"))
        rec.addEvent("START", 0)
        for (i in 0..10) {
            rec.addLoc(Loc(i * 1000L, 44.0 + i * 0.0001, -68.0, null))  // no GPS altitude, as on a Pixel Watch
            rec.addElevation(i * 1000L, 20.0 + i)
        }
        rec.addElevation(11_000L, Double.MAX_VALUE)  // a missing reading is dropped
        assertEquals(11, rec.elevation.size)
        assertEquals(29.0, rec.recentElevation(2000)!!, 1e-9)  // mean of 28, 29, 30
        rec.addLap(Lap(0, 10_000, 10_000, 120.0, "MANUAL", null), 10_000, 120.0)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(rec.toTcx().byteInputStream())
        assertEquals(11, doc.getElementsByTagName("AltitudeMeters").length)
        assertEquals("25.0", doc.getElementsByTagName("AltitudeMeters").item(5).textContent)
        assertEquals(11, Recorder.replay(rec.journalFile).first.elevation.size)
    }
}
