package com.jameslongo.truerun

import org.json.JSONObject

/**
 * A structured workout, flattened to a list of steps. JSON form:
 * {"name":"6x800","steps":[{"name":"Warm up","seconds":600},
 *   {"repeat":6,"steps":[{"name":"800","meters":800,"pace":["3:05","3:12"]},
 *                        {"name":"Jog","seconds":90}]},
 *   {"name":"Cool down","lap":true}]}
 * A step ends after "meters", "seconds", or a lap-button press ("lap": true).
 * "pace" is a [fast, slow] range per configured unit.
 */
data class Workout(val name: String, val steps: List<Step>) {
    companion object {
        fun parse(j: JSONObject, metersPerUnit: Double) =
            Workout(j.getString("name"), flatten(j.getJSONArray("steps").objects(), metersPerUnit))

        private fun flatten(items: List<JSONObject>, perUnit: Double): List<Step> = items.flatMap { s ->
            if (s.has("repeat")) {
                val inner = flatten(s.getJSONArray("steps").objects(), perUnit)
                val n = s.getInt("repeat")
                (1..n).flatMap { k -> inner.map { it.copy(name = "${it.name} $k/$n") } }
            } else {
                listOf(Step.parse(s, perUnit))
            }
        }
    }
}

data class Step(
    val name: String,
    val meters: Double?,
    val seconds: Double?,
    /** Target pace range in seconds per meter, fast then slow. */
    val paceFast: Double?,
    val paceSlow: Double?,
) {
    val untilLap get() = meters == null && seconds == null

    companion object {
        fun parse(j: JSONObject, perUnit: Double): Step {
            val pace = j.optJSONArray("pace")?.strings()?.map { parseClock(it) / perUnit }
            return Step(
                name = j.optString("name", "Step"),
                meters = if (j.has("meters")) j.getDouble("meters") else null,
                seconds = if (j.has("seconds")) j.getDouble("seconds") else null,
                paceFast = pace?.minOrNull(),
                paceSlow = pace?.maxOrNull(),
            )
        }
    }
}
