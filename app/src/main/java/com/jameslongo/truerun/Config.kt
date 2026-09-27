package com.jameslongo.truerun

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Settings, workouts, races and API credentials, read from files/config.json (pushed with tools/push-config.sh). */
data class Config(
    val metersPerUnit: Double,
    val unitLabel: String,
    val autoLapMeters: Double,
    val autoPause: Boolean,
    val screens: List<List<String>>,
    val workouts: List<Workout>,
    val races: List<Race>,
    val strava: Creds?,
    val google: Creds?,
) {
    companion object {
        private val DEFAULT_SCREENS = listOf(
            listOf("time", "distance", "pace", "hr"),
            listOf("lapTime", "lapPace", "lapDistance", "lastLap"),
            listOf("avgPace", "clock", "lap", "hr"),
        )

        fun load(ctx: Context): Config {
            val f = File(ctx.filesDir, "config.json")
            val j = if (f.exists()) JSONObject(f.readText()) else JSONObject()
            val km = j.optString("units", "mi") == "km"
            val perUnit = if (km) 1000.0 else 1609.344
            return Config(
                metersPerUnit = perUnit,
                unitLabel = if (km) "km" else "mi",
                autoLapMeters = j.optDouble("autoLapMeters", perUnit),
                autoPause = j.optBoolean("autoPause", false),
                screens = j.optJSONArray("screens")?.let { a ->
                    (0 until a.length()).map { i -> a.getJSONArray(i).strings() }
                } ?: DEFAULT_SCREENS,
                workouts = j.optJSONArray("workouts").objects().map { Workout.parse(it, perUnit) },
                races = j.optJSONArray("races").objects().map { Race.parse(it) },
                strava = j.optJSONObject("strava")?.let(Creds::parse),
                google = j.optJSONObject("google")?.let(Creds::parse),
            )
        }
    }
}

data class Creds(val clientId: String, val clientSecret: String, val refreshToken: String) {
    companion object {
        fun parse(j: JSONObject) = Creds(
            j.optString("clientId"), j.optString("clientSecret"), j.optString("refreshToken"),
        ).takeIf { it.clientId.isNotEmpty() && it.refreshToken.isNotEmpty() }
    }
}

data class Race(val name: String, val meters: Double, val targetSec: Double) {
    companion object {
        fun parse(j: JSONObject) = Race(
            j.getString("name"), j.getDouble("meters"), parseClock(j.getString("target")),
        )
    }
}

/** "27:30" or "1:23:45" -> seconds. */
fun parseClock(s: String): Double =
    s.split(":").fold(0.0) { acc, part -> acc * 60 + part.toDouble() }

fun JSONArray.strings() = (0 until length()).map { getString(it) }
fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
