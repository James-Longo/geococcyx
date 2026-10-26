package com.jameslongo.geococcyx

import java.io.File
import java.time.Instant

data class Loc(val t: Long, val lat: Double, val lon: Double, val alt: Double?)
data class Lap(
    val start: Long, val end: Long, val activeMs: Long, val meters: Double,
    val trigger: String, val avgHr: Int?,
)
data class Event(val t: Long, val type: String)

/** Where a run stood when its journal was last written. */
data class Replay(
    val mode: String, val workout: String?, val race: String?,
    val lapEndWall: Long = 0, val lapEndActive: Long = 0, val lapEndMeters: Double = 0.0,
    val stepIdx: Int = 0, val stepStartActive: Long = 0, val stepStartMeters: Double = 0.0,
)

/**
 * Everything one run records, keyed by each sample's own timestamp (epoch ms).
 * Every sample is also appended to [journalFile] so a crash doesn't lose the run.
 */
class Recorder(val journalFile: File, val start: Long = System.currentTimeMillis()) {
    val locs = ArrayList<Loc>()
    val hr = ArrayList<Pair<Long, Int>>()
    /** Steps per minute, both feet. */
    val cadence = ArrayList<Pair<Long, Double>>()
    /** Meters, as Health Services reports STRIDE_LENGTH. */
    val stride = ArrayList<Pair<Long, Double>>()
    /** Meters above sea level from the barometer (ABSOLUTE_ELEVATION); GPS altitude is missing on a Pixel Watch. */
    val elevation = ArrayList<Pair<Long, Double>>()
    val laps = ArrayList<Lap>()
    val events = ArrayList<Event>()

    private fun log(line: String) = journalFile.appendText(line + "\n")

    /** False for a sample already recorded. */
    fun addLoc(l: Loc): Boolean {
        // Health Services can redeliver a batch; keep samples strictly in time order.
        if (locs.isNotEmpty() && l.t <= locs.last().t) return false
        locs += l
        log("L,${l.t},${l.lat},${l.lon},${l.alt ?: ""}")
        return true
    }

    fun addHr(t: Long, bpm: Int) {
        if (bpm <= 0 || (hr.isNotEmpty() && t <= hr.last().first)) return
        hr += t to bpm
        log("H,$t,$bpm")
    }

    fun addCadence(t: Long, spm: Double) {
        if (spm <= 0 || (cadence.isNotEmpty() && t <= cadence.last().first)) return
        cadence += t to spm
        log("C,$t,$spm")
    }

    fun addStride(t: Long, m: Double) {
        if (m <= 0 || (stride.isNotEmpty() && t <= stride.last().first)) return
        stride += t to m
        log("R,$t,$m")
    }

    fun addElevation(t: Long, m: Double) {
        if (altitude(m) == null || (elevation.isNotEmpty() && t <= elevation.last().first)) return
        elevation += t to m
        log("B,$t,$m")
    }

    /** Mean barometric elevation over the [ms] up to the newest reading; null when there's none. */
    fun recentElevation(ms: Long = 5000): Double? {
        val end = elevation.lastOrNull()?.first ?: return null
        var sum = 0.0; var n = 0
        for (i in elevation.indices.reversed()) {
            if (elevation[i].first < end - ms) break
            sum += elevation[i].second; n++
        }
        return sum / n
    }

    fun addEvent(type: String, t: Long = System.currentTimeMillis()) {
        events += Event(t, type)
        log("E,$t,$type")
    }

    /** [activeAtEnd] and [metersAtEnd] let a restarted service carry on from this lap. */
    fun addLap(lap: Lap, activeAtEnd: Long, metersAtEnd: Double) {
        laps += lap
        log("P,${lap.start},${lap.end},${lap.activeMs},${lap.meters},${lap.trigger},$activeAtEnd,$metersAtEnd")
    }

    fun logMeta(mode: String, workout: String?, race: String?) =
        log("M,${start},${mode.replace(",", " ")},${workout.orEmpty().replace(",", " ")},${race.orEmpty().replace(",", " ")}")

    fun logStep(idx: Int, activeAtStart: Long, metersAtStart: Double) = log("S,$idx,$activeAtStart,$metersAtStart")

    /** GPS path length since [from], for a run picked up after a crash: where its [Odometer] carries on from. */
    fun gpsMeters(from: Long = Long.MIN_VALUE): Double =
        locs.filter { it.t >= from }.zipWithNext().sumOf { (a, b) -> haversine(a, b) }

    /** Active time from the event log, used only for crash recovery. */
    fun activeFromEvents(end: Long): Long {
        val first = events.firstOrNull { it.type == "START" }?.t ?: return 0
        return (end - first) - pauses().sumOf { (minOf(it.last, end) - it.first).coerceAtLeast(0) }
    }

    fun avgHr(from: Long, to: Long): Int? =
        hr.filter { it.first in from..to }.map { it.second }.takeIf { it.isNotEmpty() }?.average()?.toInt()

    private fun avgCadence(from: Long, to: Long): Double? =
        cadence.filter { it.first in from..to }.map { it.second }.takeIf { it.isNotEmpty() }?.average()

    /**
     * Nearest sample within 3 s of the GPS point (the earlier one on a tie). Series are in time order,
     * so this is a binary search: a linear scan per point made saving a 90-minute run take seconds.
     */
    private fun <T> nearest(series: List<Pair<Long, T>>, t: Long): T? {
        var lo = 0
        var hi = series.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (series[mid].first < t) lo = mid + 1 else hi = mid
        }
        val best = listOfNotNull(series.getOrNull(lo - 1), series.getOrNull(lo)).minByOrNull { kotlin.math.abs(it.first - t) }
        return best?.takeIf { kotlin.math.abs(it.first - t) <= 3000 }?.second
    }

    /** Wall-clock spans when the run was paused. */
    private fun pauses(): List<LongRange> {
        val out = ArrayList<LongRange>()
        var from: Long? = null
        for (e in events) when (e.type) {
            "PAUSE" -> if (from == null) from = e.t
            "RESUME", "STOP" -> from?.let { out += it..e.t; from = null }
        }
        return out
    }

    /** TCX with one Lap per lap and a new Track after every pause, so Strava (via Drive) sees real stops only. */
    fun toTcx(): String {
        val pauses = pauses()
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>
<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2" xmlns:ns3="http://www.garmin.com/xmlschemas/ActivityExtension/v2" xmlns:gx="$NS">
<Activities><Activity Sport="Running"><Id>${iso(start)}</Id>
""")
        for (lap in laps) {
            sb.append("<Lap StartTime=\"${iso(lap.start)}\"><TotalTimeSeconds>${lap.activeMs / 1000.0}</TotalTimeSeconds>")
            sb.append("<DistanceMeters>${"%.1f".format(lap.meters)}</DistanceMeters><Calories>0</Calories>")
            lap.avgHr?.let { sb.append("<AverageHeartRateBpm><Value>$it</Value></AverageHeartRateBpm>") }
            sb.append("<Intensity>Active</Intensity><TriggerMethod>${when (lap.trigger) { "MANUAL" -> "Manual"; "TIME" -> "Time"; else -> "Distance" }}</TriggerMethod>\n<Track>\n")
            var prev: Loc? = null
            for (l in locs) {
                if (l.t < lap.start || l.t > lap.end) continue
                if (prev != null && pauses.any { it.first in prev.t..l.t }) sb.append("</Track>\n<Track>\n")
                sb.append("<Trackpoint><Time>${iso(l.t)}</Time><Position><LatitudeDegrees>${l.lat}</LatitudeDegrees>")
                sb.append("<LongitudeDegrees>${l.lon}</LongitudeDegrees></Position>")
                (l.alt ?: nearest(elevation, l.t))?.let { sb.append("<AltitudeMeters>${"%.1f".format(it)}</AltitudeMeters>") }
                nearest(hr, l.t)?.let { sb.append("<HeartRateBpm><Value>$it</Value></HeartRateBpm>") }
                // Garmin's RunCadence counts one foot, so it's half of steps per minute.
                val spm = nearest(cadence, l.t)
                val len = nearest(stride, l.t)
                if (spm != null || len != null) {
                    sb.append("<Extensions>")
                    spm?.let { sb.append("<ns3:TPX><ns3:RunCadence>${Math.round(it / 2)}</ns3:RunCadence></ns3:TPX>") }
                    len?.let { sb.append("<gx:StrideLength>${"%.3f".format(it)}</gx:StrideLength>") }
                    sb.append("</Extensions>")
                }
                sb.append("</Trackpoint>\n")
                prev = l
            }
            sb.append("</Track>")
            avgCadence(lap.start, lap.end)?.let {
                sb.append("<Extensions><ns3:LX><ns3:AvgRunCadence>${Math.round(it / 2)}</ns3:AvgRunCadence></ns3:LX></Extensions>")
            }
            sb.append("</Lap>\n")
        }
        sb.append("<Creator xsi:type=\"Device_t\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><Name>Geococcyx (Pixel Watch)</Name></Creator>\n")
        sb.append("</Activity></Activities></TrainingCenterDatabase>\n")
        return sb.toString()
    }

    companion object {
        /** Namespace for TCX values Garmin's schema has no element for (stride length). */
        const val NS = "urn:geococcyx:tcx:v1"

        /** Rebuilds a run from its journal after the app was killed mid-run. */
        fun replay(journal: File): Pair<Recorder, Replay> {
            val lines = journal.readLines().map { it.split(",") }
            val meta = lines.firstOrNull { it[0] == "M" }
            val rec = Recorder(journal, meta?.get(1)?.toLong() ?: lines.firstOrNull()?.getOrNull(1)?.toLongOrNull() ?: 0L)
            var info = Replay(meta?.getOrNull(2) ?: "Run", meta?.getOrNull(3)?.ifEmpty { null }, meta?.getOrNull(4)?.ifEmpty { null })
            for (f in lines) runCatching {
                when (f[0]) {
                    "L" -> rec.locs += Loc(f[1].toLong(), f[2].toDouble(), f[3].toDouble(), f.getOrNull(4)?.toDoubleOrNull())
                    "H" -> rec.hr += f[1].toLong() to f[2].toInt()
                    "C" -> rec.cadence += f[1].toLong() to f[2].toDouble()
                    "R" -> rec.stride += f[1].toLong() to f[2].toDouble()
                    "B" -> rec.elevation += f[1].toLong() to f[2].toDouble()
                    "E" -> rec.events += Event(f[1].toLong(), f[2])
                    "P" -> {
                        rec.laps += Lap(f[1].toLong(), f[2].toLong(), f[3].toLong(), f[4].toDouble(), f[5], null)
                        info = info.copy(lapEndWall = f[2].toLong(), lapEndActive = f[6].toLong(), lapEndMeters = f[7].toDouble())
                    }
                    "S" -> info = info.copy(stepIdx = f[1].toInt(), stepStartActive = f[2].toLong(), stepStartMeters = f[3].toDouble())
                }
            }
            if (info.lapEndWall == 0L) info = info.copy(lapEndWall = rec.events.firstOrNull { it.type == "START" }?.t ?: rec.start)
            return rec to info
        }

        fun haversine(a: Loc, b: Loc): Double {
            val r = 6371000.0
            val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
            val dp = p2 - p1; val dl = Math.toRadians(b.lon - a.lon)
            val h = Math.sin(dp / 2).let { it * it } + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2).let { it * it }
            return 2 * r * Math.asin(Math.sqrt(h))
        }

        /**
         * Health Services reports a missing altitude as Double.MAX_VALUE (seen on a Pixel Watch 3), not NaN;
         * keep only readings that could be somewhere on Earth.
         */
        fun altitude(m: Double): Double? = m.takeIf { it.isFinite() && it in -500.0..9000.0 }

        fun iso(t: Long): String = Instant.ofEpochMilli(t).toString()
    }
}
