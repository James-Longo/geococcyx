package com.jameslongo.geococcyx

/**
 * Slope under the runner, from barometric elevation against distance, for a grade-adjusted pacer.
 * Fed once a second with the run's total meters and the latest smoothed elevation.
 */
class Grade {
    // (meters, elevation) checkpoints, oldest first, covering a little over SPAN_M of distance.
    private val samples = ArrayDeque<Pair<Double, Double>>()

    fun add(meters: Double, elevation: Double) {
        if (samples.isNotEmpty() && meters < samples.last().first) samples.clear()  // distance went backwards: start over
        if (samples.isNotEmpty() && meters == samples.last().first) samples.removeLast()  // standing still: keep the newest reading
        samples.addLast(meters to elevation)
        while (samples.size > 2 && meters - samples[1].first >= SPAN_M) samples.removeFirst()
    }

    /** Percent slope over the last ~[SPAN_M] meters; null until [MIN_SPAN_M] of distance has elevation. */
    val percent: Double?
        get() {
            val (m1, e1) = samples.lastOrNull() ?: return null
            val (m0, e0) = samples.first()
            if (m1 - m0 < MIN_SPAN_M) return null
            return (100 * (e1 - e0) / (m1 - m0)).coerceIn(-MAX_PERCENT, MAX_PERCENT)
        }

    companion object {
        const val SPAN_M = 100.0
        const val MIN_SPAN_M = 60.0
        const val MAX_PERCENT = 15.0

        /**
         * Effort of running at [percent] slope relative to flat ground (above 1 is harder).
         * Uphill costs about 3.5% per 1% of grade, which is what James's own races showed
         * (10-15 s/mi per 1% near 6:15/mi). A descent gives back about half that, and past -8%
         * braking eats into the gain (Minetti et al. 2002 has the same asymmetric shape).
         */
        fun factor(percent: Double): Double = when {
            percent >= 0 -> 1 + 0.035 * percent
            percent >= -8 -> 1 + 0.018 * percent
            else -> 1 - 0.018 * 8 + 0.018 * (-8 - percent)
        }

        /** Flat-ground equivalent of [secPerMeter] run on [percent] slope; unchanged when the slope is unknown. */
        fun adjust(secPerMeter: Double, percent: Double?): Double =
            if (percent == null) secPerMeter else secPerMeter / factor(percent)
    }
}
