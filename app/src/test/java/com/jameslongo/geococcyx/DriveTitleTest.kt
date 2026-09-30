package com.jameslongo.geococcyx

import org.junit.Assert.assertEquals
import org.junit.Test

class DriveTitleTest {
    @Test
    fun localTimeFromMeta() {
        // 14:36:45 UTC on 2026-09-30 is 10:36 in New York (EDT).
        assertEquals("2026-09-30 10:36 Run.tcx", driveTitle("2026-09-30T143645.850Z.tcx", 1790779005850, "America/New_York", "Run"))
    }

    @Test
    fun zoneWhereTheRunHappened() {
        assertEquals("2026-09-30 07:36 Run.tcx", driveTitle("2026-09-30T143645.850Z.tcx", 1790779005850, "America/Los_Angeles", "Run"))
    }

    @Test
    fun olderRunFallsBackToFileName() {
        assertEquals("2026-09-30 14:36 Pacer.tcx", driveTitle("2026-09-30T143645.850Z.tcx", null, "UTC", "Pacer"))
    }
}
