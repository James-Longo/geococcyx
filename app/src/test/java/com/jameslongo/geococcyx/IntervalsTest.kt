package com.jameslongo.geococcyx

import com.jameslongo.geococcyx.Intervals.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class IntervalsTest {
    @Test
    fun stepsRunWarmUpOnRestAndEndWithCoolDown() {
        val iv = Intervals(reps = 3, onSec = 0, restSec = 90)
        val steps = iv.workout().steps
        assertEquals(listOf("Warm up", "On 1", "Rest 1", "On 2", "Rest 2", "On 3", "Cool down"), steps.map { it.name })
        assertEquals(steps.indices.map { iv.kind(it) },
            listOf(Kind.WARM_UP, Kind.ON, Kind.REST, Kind.ON, Kind.REST, Kind.ON, Kind.COOL_DOWN))
        // Open intervals wait for the crown; timed ones carry their seconds.
        assertEquals(true, steps[1].untilLap)
        assertEquals(90.0, steps[2].seconds)
        assertEquals(true, steps.first().untilLap && steps.last().untilLap)
    }

    @Test
    fun restCountsRepsLeftAndCoolDownIsLast() {
        val iv = Intervals(reps = 6, onSec = 60, restSec = 0)
        assertEquals(1, iv.rep(2))
        assertEquals(5, iv.repsLeft(2))
        assertEquals(1, iv.repsLeft(10))
        assertEquals(6, iv.rep(12))
        assertEquals(false, iv.isLast(11))
        assertEquals(true, iv.isLast(12))
    }
}
