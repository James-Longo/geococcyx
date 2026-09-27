package com.jameslongo.truerun

import java.time.LocalTime
import java.time.format.DateTimeFormatter

fun fmtDur(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun fmtPace(secPerMeter: Double?, perUnit: Double): String {
    if (secPerMeter == null || secPerMeter <= 0 || secPerMeter * perUnit > 3600) return "--:--"
    val s = (secPerMeter * perUnit).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

fun fmtDist(m: Double, perUnit: Double) = "%.2f".format(m / perUnit)

/** Data-screen fields, by the keys used in config.json "screens": label to value. */
fun field(key: String, s: RunState, c: Config): Pair<String, String> {
    val u = c.metersPerUnit
    val avg = if (s.meters > 10) s.activeMs / 1000.0 / s.meters else null
    return when (key) {
        "time" -> "TIME" to fmtDur(s.activeMs)
        "distance" -> c.unitLabel.uppercase() to fmtDist(s.meters, u)
        "pace" -> "PACE" to fmtPace(s.pace, u)
        "avgPace" -> "AVG PACE" to fmtPace(avg, u)
        "hr" -> "HR" to (s.hr?.toString() ?: "--")
        "lap" -> "LAP" to s.lapNo.toString()
        "lapTime" -> "LAP ${s.lapNo}" to fmtDur(s.lapMs)
        "lapPace" -> "LAP PACE" to fmtPace(if (s.lapMeters > 10) s.lapMs / 1000.0 / s.lapMeters else null, u)
        "lapDistance" -> "LAP ${c.unitLabel.uppercase()}" to fmtDist(s.lapMeters, u)
        "lastLap" -> "LAST LAP" to (s.lastLapMs?.let { fmtDur(it) } ?: "--")
        "lastLapPace" -> "LAST PACE" to fmtPace(
            s.lastLapMeters?.takeIf { it > 10 }?.let { (s.lastLapMs ?: 0) / 1000.0 / it }, u,
        )
        "clock" -> "CLOCK" to LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm"))
        else -> key.uppercase() to "?"
    }
}
