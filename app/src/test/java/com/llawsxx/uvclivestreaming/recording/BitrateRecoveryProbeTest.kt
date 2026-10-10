package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test

class BitrateRecoveryProbeTest {
    @Test fun unknownCapacityDoublesAndFreshMeasurementCanJumpDirectlyToSafeBudget() {
        val probe = BitrateRecoveryProbe()
        assertEquals(2_000_000, probe.increase(0, 1_000_000, 12_000_000)!!.bitrate)
        assertEquals(8_000_000, probe.increase(6_000_000_000L, 2_000_000, 12_000_000, 8_000_000)!!.bitrate)
        assertEquals(12_000_000, probe.increase(12_000_000_000L, 8_000_000, 12_000_000, 100_000_000)!!.bitrate)
    }

    @Test fun failedProbeReturnsLastGoodPointAndShrinksFutureAttempts() {
        val probe = BitrateRecoveryProbe()
        assertEquals(4_000_000, probe.increase(0, 2_000_000, 12_000_000)!!.bitrate)
        assertEquals(2_000_000, probe.reject(3_000_000_000L, 4_000_000))
        assertEquals(3_000_000, probe.increase(10_000_000_000L, 2_000_000, 12_000_000)!!.bitrate)
        assertEquals(2_000_000, probe.reject(13_000_000_000L, 3_000_000))
        assertEquals(2_500_000, probe.increase(20_000_000_000L, 2_000_000, 12_000_000)!!.bitrate)
    }

    @Test fun confirmedProbeCannotBeRolledBackHoursLaterAndAClockGapAbandonsIt() {
        val probe = BitrateRecoveryProbe()
        probe.increase(0, 3_000_000, 6_000_000)
        assertNull(probe.increase(6_000_000_000L, 6_000_000, 6_000_000))
        assertNull(probe.reject(600_000_000_000L, 6_000_000))
        probe.increase(0, 1_000_000, 6_000_000)
        probe.abandon()
        assertNull(probe.reject(100_000_000_000L, 2_000_000))
    }

    @Test fun fineProbingStopsNearBoundaryAndResumesLargeProbesAfterThirtySeconds() {
        val probe = BitrateRecoveryProbe()
        probe.increase(0, 1_000_000, 12_000_000)
        probe.reject(3_000_000_000L, 2_000_000)
        assertNull(probe.increase(10_000_000_000L, 1_950_000, 12_000_000))
        assertEquals(3_900_000, probe.increase(33_000_000_000L, 1_950_000, 12_000_000)!!.bitrate)
    }

    @Test fun lowAchievedMeasurementsPermitOnlyASmallReassessmentProbe() {
        val probe = BitrateRecoveryProbe()
        val raised = probe.increase(0, 1_000_000, 12_000_000, 500_000)!!
        assertEquals(1_100_000, raised.bitrate)
        assertTrue(raised.fine)
    }

    @Test fun genuinelyImprovedMeasurementsReleaseBoundaryButRepeatedOverestimateDoesNot() {
        val probe = BitrateRecoveryProbe()
        assertEquals(8_000_000, probe.increase(0, 2_000_000, 12_000_000, 8_000_000)!!.bitrate)
        assertEquals(2_000_000, probe.reject(3_000_000_000L, 8_000_000, 8_000_000))
        assertEquals(5_000_000, probe.increase(10_000_000_000L, 2_000_000, 12_000_000, 8_000_000)!!.bitrate)
        assertEquals(2_000_000, probe.reject(13_000_000_000L, 5_000_000, 4_000_000))
        assertEquals(7_000_000, probe.increase(20_000_000_000L, 2_000_000, 12_000_000, 7_000_000)!!.bitrate)
    }
}
