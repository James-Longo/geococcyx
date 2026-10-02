package com.jameslongo.geococcyx

import android.content.Context

/**
 * Track intervals: warm up, then [reps] on/rest pairs (no rest after the last rep), then cool down.
 * [onSec] / [restSec] of 0 means open: that interval lasts until the crown is turned up.
 * Warm up and cool down are always open. Each interval is one workout step, so one lap.
 */
data class Intervals(val reps: Int, val onSec: Int, val restSec: Int) {
    enum class Kind(val label: String) { WARM_UP("WARM UP"), ON("ON"), REST("REST"), COOL_DOWN("COOL DOWN") }

    fun workout() = Workout(NAME, buildList {
        add(step("Warm up", 0))
        for (k in 1..reps) {
            add(step("On $k", onSec))
            if (k < reps) add(step("Rest $k", restSec))
        }
        add(step("Cool down", 0))
    })

    /** Steps go warm up, on 1, rest 1, …, on N, cool down. */
    fun kind(stepIdx: Int) = when {
        stepIdx <= 0 -> Kind.WARM_UP
        stepIdx >= 2 * reps -> Kind.COOL_DOWN
        stepIdx % 2 == 1 -> Kind.ON
        else -> Kind.REST
    }

    /** The rep an ON step runs, or the rep just finished during a REST. */
    fun rep(stepIdx: Int) = (stepIdx + 1) / 2

    /** Reps still to run after the current REST. */
    fun repsLeft(stepIdx: Int) = reps - rep(stepIdx)

    fun isLast(stepIdx: Int) = stepIdx >= 2 * reps

    fun save(ctx: Context) = prefs(ctx).edit().putInt("reps", reps).putInt("on", onSec).putInt("rest", restSec).apply()

    companion object {
        const val NAME = "Intervals"

        /** The last setup used; a recovered run rebuilds its intervals from this too. */
        fun load(ctx: Context) = prefs(ctx).run { Intervals(getInt("reps", 6), getInt("on", 0), getInt("rest", 90)) }

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("intervals", 0)
        private fun step(name: String, sec: Int) = Step(name, null, sec.takeIf { it > 0 }?.toDouble(), null, null)
    }
}
