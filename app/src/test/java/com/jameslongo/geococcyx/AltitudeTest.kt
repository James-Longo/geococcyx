package com.jameslongo.geococcyx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AltitudeTest {
    @Test
    fun missingAltitudeIsDropped() {
        // What a Pixel Watch 3 sent on every point of the 2026-09-30 run.
        assertNull(Recorder.altitude(Double.MAX_VALUE))
        assertNull(Recorder.altitude(Double.NaN))
        assertNull(Recorder.altitude(Double.POSITIVE_INFINITY))
    }

    @Test
    fun realAltitudeIsKept() {
        assertEquals(42.5, Recorder.altitude(42.5)!!, 0.0)
        assertEquals(-3.0, Recorder.altitude(-3.0)!!, 0.0)
    }
}
