package com.jameslongo.geococcyx

/**
 * The run's distance, measured along the GPS track. Health Services' DISTANCE_TOTAL ran 144 m short
 * in the first mile of the 2026-10-25 5k (4764 m for the whole race; the GPS track was 5007 m), so
 * laps and the distance field go by GPS. Its total only fills in while GPS is missing.
 * Fed only while running: [pause] drops the anchor, so walking around during a pause isn't counted.
 */
class Odometer(var meters: Double = 0.0, private var last: Loc? = null) {
    private var lastTotal: Double? = null

    fun gps(l: Loc) {
        last?.let { meters += Recorder.haversine(it, l) }
        last = l
    }

    /**
     * Health Services' running total at [now]. Only its gain while GPS has been silent for [GAP_MS]
     * counts; the next fix then starts a fresh anchor, so the gap isn't also bridged by a straight line.
     */
    fun total(total: Double, now: Long) {
        val prev = lastTotal
        lastTotal = total
        if (prev == null || total <= prev) return
        val l = last
        if (l == null || now - l.t > GAP_MS) { meters += total - prev; last = null }
    }

    /** Whatever happens until the next fix or total after resuming starts from scratch. */
    fun pause() { last = null; lastTotal = null }

    companion object {
        const val GAP_MS = 10_000L
    }
}
