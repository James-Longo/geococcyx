package com.jameslongo.geococcyx

/**
 * The virtual pacer: splits the run into quarters of a mile (or km) and judges each finished split's
 * grade-adjusted pace against the range. Fed once a second while running, and told about every lap
 * so the quarters line up with the laps instead of drifting a few meters each.
 */
class SplitPacer(
    /** Seconds per meter, fastest and slowest allowed. */
    val fast: Double,
    val slow: Double,
    val splitMeters: Double,
    /** Auto-lap distance; null when laps are only by hand. */
    val lapMeters: Double? = null,
) {
    /** A finished split: grade-adjusted pace (s/m), -1 too fast / 0 in range / 1 too slow, and whether elevation was known. */
    data class Split(val no: Int, val pace: Double, val zone: Int, val graded: Boolean)

    private var startActive = -1L
    private var startMeters = 0.0
    private var lastMeters = 0.0
    private var lapStartMeters = 0.0
    // Distance weighted by how hard each stretch's slope made it: the split's flat-ground equivalent.
    private var effortMeters = 0.0
    private var graded = false
    private var lastPercent: Double? = null
    private var count = 0

    /** [percent] is the current slope, null without elevation (that stretch counts as flat). Returns a split when one finishes. */
    fun tick(activeMs: Long, meters: Double, percent: Double?): Split? {
        if (startActive < 0) { restart(activeMs, meters); lapStartMeters = meters; return null }
        lastPercent = percent
        advance(meters)
        if (meters - startMeters < splitMeters) return null
        // Within half a quarter of the auto-lap, this split runs on to end with the lap.
        if (lapMeters != null && lapMeters - (meters - lapStartMeters) < splitMeters / 2) return null
        return finish(activeMs, meters)
    }

    /** A lap closed at [meters]: it ends the split in progress (unless that's under half a quarter) and starts fresh quarters. */
    fun lap(activeMs: Long, meters: Double): Split? {
        if (startActive < 0) { restart(activeMs, meters); lapStartMeters = meters; return null }
        advance(meters)  // the lap can land between ticks: count the distance since the last one
        val split = if (meters - startMeters >= splitMeters / 2) finish(activeMs, meters) else null
        restart(activeMs, meters)
        lapStartMeters = meters
        return split
    }

    private fun advance(meters: Double) {
        effortMeters += (meters - lastMeters).coerceAtLeast(0.0) * Grade.factor(lastPercent ?: 0.0)
        if (lastPercent != null) graded = true
        lastMeters = meters
    }

    private fun finish(activeMs: Long, meters: Double): Split? {
        if (effortMeters <= 0) return null
        val pace = (activeMs - startActive) / 1000.0 / effortMeters
        val split = Split(++count, pace, when { pace < fast -> -1; pace > slow -> 1; else -> 0 }, graded)
        restart(activeMs, meters)
        return split
    }

    private fun restart(activeMs: Long, meters: Double) {
        startActive = activeMs; startMeters = meters; lastMeters = meters; effortMeters = 0.0; graded = false
    }
}
