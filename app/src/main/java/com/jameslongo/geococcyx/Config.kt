package com.jameslongo.geococcyx

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Settings, workouts, races and API credentials, read from files/config.json (pushed by tools/install.sh). */
data class Config(
    val metersPerUnit: Double,
    val unitLabel: String,
    val autoLapMeters: Double,
    /** The run screen's fields: top, middle left, middle right, bottom (keys in Fields.kt). */
    val fields: List<String>,
    val workouts: List<Workout>,
    val races: List<Race>,
    val google: Creds?,
    /** With [google] signed in: copy each saved run's TCX to Drive, and/or post its summary to Google Health. */
    val drive: Boolean,
    val googleHealth: Boolean,
) {
    companion object {
        private val DEFAULT_FIELDS = listOf("distance", "lapPace", "avgPace", "time")

        fun load(ctx: Context): Config {
            // A pushed files/config.json overrides the one built into the app.
            val f = File(ctx.filesDir, "config.json")
            val text = if (f.exists()) f.readText()
                else runCatching { ctx.assets.open("config.json").bufferedReader().readText() }.getOrNull()
            val j = text?.let(::JSONObject) ?: JSONObject()
            val km = j.optString("units", "mi") == "km"
            val perUnit = if (km) 1000.0 else 1609.344
            return Config(
                metersPerUnit = perUnit,
                unitLabel = if (km) "km" else "mi",
                autoLapMeters = j.optDouble("autoLapMeters", perUnit),
                fields = j.optJSONArray("fields")?.strings() ?: DEFAULT_FIELDS,
                workouts = j.optJSONArray("workouts").objects().map { Workout.parse(it, perUnit) },
                races = j.optJSONArray("races").objects().map { Race.parse(it) },
                google = j.optJSONObject("google")?.let(Creds::parse),
                drive = j.optBoolean("drive", true),
                googleHealth = j.optBoolean("googleHealth", false),
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
