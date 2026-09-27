package com.jameslongo.truerun

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.TimeZone

data class Loc(val t: Long, val lat: Double, val lon: Double, val alt: Double?)
data class Lap(
    val start: Long, val end: Long, val activeMs: Long, val meters: Double,
    val trigger: String, val avgHr: Int?,
)
data class Event(val t: Long, val type: String)

/**
 * Everything one run records, keyed by each sample's own timestamp (epoch ms).
 * Every sample is also appended to [journal] so a crash doesn't lose the run.
 */
class Recorder(private val journal: File) {
    val start = System.currentTimeMillis()
    val locs = ArrayList<Loc>()
    val hr = ArrayList<Pair<Long, Int>>()
    val laps = ArrayList<Lap>()
    val events = ArrayList<Event>()

    private fun log(line: String) = journal.appendText(line + "\n")

    fun addLoc(l: Loc) {
        // Health Services can redeliver a batch; keep samples strictly in time order.
        if (locs.isNotEmpty() && l.t <= locs.last().t) return
        locs += l
        log("L,${l.t},${l.lat},${l.lon},${l.alt ?: ""}")
    }

    fun addHr(t: Long, bpm: Int) {
        if (bpm <= 0 || (hr.isNotEmpty() && t <= hr.last().first)) return
        hr += t to bpm
        log("H,$t,$bpm")
    }

    fun addEvent(type: String, t: Long = System.currentTimeMillis()) {
        events += Event(t, type)
        log("E,$t,$type")
    }

    fun addLap(lap: Lap) {
        laps += lap
        log("P,${lap.start},${lap.end},${lap.activeMs},${lap.meters},${lap.trigger}")
    }

    fun avgHr(from: Long, to: Long): Int? =
        hr.filter { it.first in from..to }.map { it.second }.takeIf { it.isNotEmpty() }?.average()?.toInt()

    private fun hrAt(t: Long): Int? {
        // Nearest heart-rate sample within 3 s of the GPS point.
        var best: Pair<Long, Int>? = null
        for (h in hr) {
            if (kotlin.math.abs(h.first - t) <= 3000 &&
                (best == null || kotlin.math.abs(h.first - t) < kotlin.math.abs(best.first - t))
            ) best = h
            if (h.first > t + 3000) break
        }
        return best?.second
    }

    /** Wall-clock spans when the run was paused (manual or auto). */
    private fun pauses(): List<LongRange> {
        val out = ArrayList<LongRange>()
        var from: Long? = null
        for (e in events) when (e.type) {
            "PAUSE", "AUTO_PAUSE" -> if (from == null) from = e.t
            "RESUME", "AUTO_RESUME", "STOP" -> from?.let { out += it..e.t; from = null }
        }
        return out
    }

    /** TCX with one Lap per lap and a new Track after every pause, so Strava sees real stops only. */
    fun toTcx(): String {
        val pauses = pauses()
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>
<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">
<Activities><Activity Sport="Running"><Id>${iso(start)}</Id>
""")
        for (lap in laps) {
            sb.append("<Lap StartTime=\"${iso(lap.start)}\"><TotalTimeSeconds>${lap.activeMs / 1000.0}</TotalTimeSeconds>")
            sb.append("<DistanceMeters>${"%.1f".format(lap.meters)}</DistanceMeters><Calories>0</Calories>")
            lap.avgHr?.let { sb.append("<AverageHeartRateBpm><Value>$it</Value></AverageHeartRateBpm>") }
            sb.append("<Intensity>Active</Intensity><TriggerMethod>${if (lap.trigger == "MANUAL") "Manual" else "Distance"}</TriggerMethod>\n<Track>\n")
            var prev: Loc? = null
            for (l in locs) {
                if (l.t < lap.start || l.t > lap.end) continue
                if (prev != null && pauses.any { it.first in prev.t..l.t }) sb.append("</Track>\n<Track>\n")
                sb.append("<Trackpoint><Time>${iso(l.t)}</Time><Position><LatitudeDegrees>${l.lat}</LatitudeDegrees>")
                sb.append("<LongitudeDegrees>${l.lon}</LongitudeDegrees></Position>")
                l.alt?.let { sb.append("<AltitudeMeters>${"%.1f".format(it)}</AltitudeMeters>") }
                hrAt(l.t)?.let { sb.append("<HeartRateBpm><Value>$it</Value></HeartRateBpm>") }
                sb.append("</Trackpoint>\n")
                prev = l
            }
            sb.append("</Track></Lap>\n")
        }
        sb.append("<Creator xsi:type=\"Device_t\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><Name>TrueRun (Pixel Watch)</Name></Creator>\n")
        sb.append("</Activity></Activities></TrainingCenterDatabase>\n")
        return sb.toString()
    }

    /** Body for POST /v4/users/me/dataTypes/exercise/dataPoints (Google Health API). */
    fun toGoogleHealth(name: String, activeMs: Long, meters: Double): JSONObject {
        val end = events.lastOrNull { it.type == "STOP" }?.t ?: System.currentTimeMillis()
        val avg = avgHr(start, end)
        val summary = JSONObject().put("distanceMillimeters", meters * 1000)
        if (meters > 0) summary.put("averagePaceSecondsPerMeter", activeMs / 1000.0 / meters)
        avg?.let { summary.put("averageHeartRateBeatsPerMinute", it.toString()) }
        val ex = JSONObject()
            .put("interval", interval(start, end))
            .put("exerciseType", "RUNNING")
            .put("displayName", name)
            .put("activeDuration", "${activeMs / 1000.0}s")
            .put("metricsSummary", summary)
            .put("exerciseMetadata", JSONObject().put("hasGps", locs.isNotEmpty()))
            .put("exerciseEvents", JSONArray(events.map {
                JSONObject().put("eventTime", iso(it.t)).put("eventUtcOffset", offset(it.t))
                    .put("exerciseEventType", it.type)
            }))
            .put("splitSummaries", JSONArray(laps.map { lap ->
                interval(lap.start, lap.end)
                    .put("splitType", if (lap.trigger == "MANUAL") "MANUAL" else "DISTANCE")
                    .put("metricsSummary", JSONObject().put("distanceMillimeters", lap.meters * 1000).apply {
                        lap.avgHr?.let { put("averageHeartRateBeatsPerMinute", it.toString()) }
                    })
            }))
        return JSONObject().put("exercise", ex)
    }

    private fun interval(a: Long, b: Long) = JSONObject()
        .put("startTime", iso(a)).put("startUtcOffset", offset(a))
        .put("endTime", iso(b)).put("endUtcOffset", offset(b))

    companion object {
        fun iso(t: Long): String = Instant.ofEpochMilli(t).toString()
        fun offset(t: Long) = "${TimeZone.getDefault().getOffset(t) / 1000}s"
    }
}
