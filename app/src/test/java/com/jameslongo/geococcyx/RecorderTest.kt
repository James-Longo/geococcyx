package com.jameslongo.geococcyx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

class RecorderTest {
    /** Replays Friday 2026-09-25's watch track (with its 303 s pause) through the recorder. */
    @Test
    fun fridayRoundTrip() {
        val src = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(javaClass.classLoader!!.getResourceAsStream("friday.tcx"))
        val tps = src.getElementsByTagName("Trackpoint")
        val rec = Recorder(File.createTempFile("run", ".journal"))
        var prevT = 0L
        for (i in 0 until tps.length) {
            val e = tps.item(i) as org.w3c.dom.Element
            fun tx(tag: String) = e.getElementsByTagName(tag).item(0)?.textContent
            val t = Instant.parse(tx("Time")).toEpochMilli()
            if (i == 0) rec.addEvent("START", t)
            if (prevT != 0L && t - prevT > 60_000) { rec.addEvent("PAUSE", prevT + 1000); rec.addEvent("RESUME", t - 1000) }
            rec.addLoc(Loc(t, tx("LatitudeDegrees")!!.toDouble(), tx("LongitudeDegrees")!!.toDouble(), tx("AltitudeMeters")?.toDouble()))
            rec.addHr(t, 150 + i % 20)  // the Google export carries no heart rate
            rec.addCadence(t, 170.0 + i % 6)
            rec.addStride(t, 1.05)
            prevT = t
        }
        rec.addEvent("STOP", prevT)
        rec.addLap(Lap(rec.start.coerceAtMost(rec.locs.first().t), prevT, 2_423_000, 8800.0, "MANUAL", rec.avgHr(0, Long.MAX_VALUE)), 2_423_000, 8800.0)
        val out = rec.toTcx()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(out.byteInputStream())
        assertEquals(tps.length, doc.getElementsByTagName("Trackpoint").length)
        assertEquals(2, doc.getElementsByTagName("Track").length)  // split at the pause
        assertTrue(doc.getElementsByTagName("HeartRateBpm").length > 1500)
        assertEquals(tps.length, doc.getElementsByTagName("ns3:RunCadence").length)
        assertEquals("85", doc.getElementsByTagName("ns3:RunCadence").item(0).textContent)  // 170 spm, one foot
        assertEquals(tps.length, doc.getElementsByTagName("gx:StrideLength").length)
        assertEquals(1, doc.getElementsByTagName("ns3:AvgRunCadence").length)
        File("build/friday-roundtrip.tcx").writeText(out)

        // A journal replay must rebuild the same run.
        val (again, info) = Recorder.replay(rec.journalFile)
        assertEquals(rec.locs.size, again.locs.size)
        assertEquals(rec.hr.size, again.hr.size)
        assertEquals(rec.cadence.size, again.cadence.size)
        assertEquals(rec.stride.size, again.stride.size)
        assertEquals(2_423_000, info.lapEndActive)
        assertEquals(2_423_000.0, again.activeFromEvents(prevT).toDouble(), 10_000.0)
    }

    @Test
    fun gpsMetersSinceLap() {
        // A crash save adds GPS since the last lap to that lap's total, not the whole run's GPS.
        val rec = Recorder(File.createTempFile("run", ".journal"))
        for (i in 0..10) rec.addLoc(Loc(i * 1000L, 44.0 + i * 0.001, -68.0, null))
        assertEquals(rec.gpsMeters() / 2, rec.gpsMeters(5000), 0.5)
        assertEquals(0.0, rec.gpsMeters(10_000), 0.0)
    }
}
