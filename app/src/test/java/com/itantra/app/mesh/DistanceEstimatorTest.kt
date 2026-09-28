package com.itantra.app.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DistanceEstimatorTest {

    @Test
    fun strongerRssiYieldsSmallerDistance() {
        val near = estimateMeters(rssi = -40)
        val far = estimateMeters(rssi = -80)
        assertTrue("stronger RSSI must estimate a smaller distance (near=$near, far=$far)", near < far)
    }

    @Test
    fun distanceIsOneMeterAtReferencePower() {
        // At rssi == txPowerDbm the path-loss exponent is 0 and 10^0 == 1.0.
        assertEquals(1.0, estimateMeters(rssi = DEFAULT_TX_POWER_DBM), 1e-9)
    }

    @Test
    fun distanceIsMonotonicInRssi() {
        var previous = Double.MAX_VALUE
        for (rssi in -90..-30) {
            val d = estimateMeters(rssi)
            assertTrue("distance must shrink as RSSI strengthens", d < previous)
            previous = d
        }
    }

    @Test
    fun kalmanFilterConvergesTowardConstantMeasurement() {
        val filter = KalmanRssiFilter(initialRssi = -90.0)
        // 60 samples of a constant -70 dBm reading.
        repeat(60) { filter.update(-70.0) }
        assertEquals(-70.0, filter.currentEstimate, 1.0)
    }

    @Test
    fun kalmanFilterSmoothsStepChanges() {
        val filter = KalmanRssiFilter(initialRssi = -70.0)
        repeat(20) { filter.update(-70.0) }
        val before = filter.currentEstimate
        // One +25 dBm outlier should move the estimate by well under half the
        // jump — the filter trades response speed for stability.
        val after = filter.update(-45.0)
        assertTrue(
            "single outlier must not fully jump the estimate (before=$before, after=$after)",
            kotlin.math.abs(after - before) < 25.0 * 0.5
        )
    }

    @Test
    fun kalmanResetReturnsToInitialState() {
        val filter = KalmanRssiFilter(initialRssi = -80.0)
        repeat(30) { filter.update(-50.0) }
        filter.reset()
        assertEquals(-80.0, filter.currentEstimate, 1e-9)
    }

    @Test
    fun rangedTrackerConvergesToConstantDistance() {
        val tracker = RangedNodeDistanceTracker(initialRssi = -90.0)
        val constantRssi = -60
        val expected = tracker.rssiToDistance(constantRssi)
        repeat(60) { tracker.updateRssi(constantRssi) }
        assertEquals(expected, tracker.currentDistanceMeters, expected * 0.25)
    }

    @Test
    fun bearingDegreesComputesCardinalDirectionsAccurately() {
        val baseLat = 28.0
        val baseLon = 77.0

        // Target due North (same longitude, higher latitude) -> ~0°
        val north = calculateBearingDegrees(baseLat, baseLon, baseLat + 0.1, baseLon)
        assertEquals(0f, north, 1.0f)

        // Target due East (same latitude, higher longitude) -> ~90°
        val east = calculateBearingDegrees(baseLat, baseLon, baseLat, baseLon + 0.1)
        assertEquals(90f, east, 1.5f)

        // Target due South (same longitude, lower latitude) -> ~180°
        val south = calculateBearingDegrees(baseLat, baseLon, baseLat - 0.1, baseLon)
        assertEquals(180f, south, 1.0f)

        // Target due West (same latitude, lower longitude) -> ~270°
        val west = calculateBearingDegrees(baseLat, baseLon, baseLat, baseLon - 0.1)
        assertEquals(270f, west, 1.5f)
    }

    @Test
    fun fuseGpsAndBleDistanceOverridesIndoorGpsDriftWhenClose() {
        // User scenario: Two phones in the same room have an indoor GPS drift error of 52 meters.
        // BLE RSSI is strong (-55 dBm), indicating ~1-2 meters.
        val fused = fuseGpsAndBleDistance(gpsDist = 52, bleDist = 1, rssi = -55)
        assertEquals(1, fused)

        val fusedSlightlyFarther = fuseGpsAndBleDistance(gpsDist = 52, bleDist = 3, rssi = -66)
        assertEquals(3, fusedSlightlyFarther)
    }

    @Test
    fun fuseGpsAndBleDistanceClampsModerateIndoorDrift() {
        // Phones separated by 8m in same floor, indoor GPS reports 60m drift
        val fused = fuseGpsAndBleDistance(gpsDist = 60, bleDist = 5, rssi = -74)
        assertEquals(5, fused)
    }

    @Test
    fun fuseGpsAndBleDistancePreservesOutdoorMacroGps() {
        // Rescuer is 150m away outside, BLE is fringe or lost (-92 dBm)
        val fused = fuseGpsAndBleDistance(gpsDist = 150, bleDist = 25, rssi = -92)
        assertEquals(150, fused)
    }

    @Test
    fun smoothCompassHeadingHandlesZeroWrapCorrectly() {
        val clockwise = smoothCompassHeading(prev = 355f, target = 5f, alpha = 0.5f)
        // Shortest diff is +10°, so 355 + 5 = 360° -> 0°
        assertEquals(0f, clockwise, 0.5f)

        // Wrapping counter-clockwise across 0° (from 5° to 355°)
        val counterClockwise = smoothCompassHeading(prev = 5f, target = 355f, alpha = 0.5f)
        // Shortest diff is -10°, so 5 - 5 = 0°
        assertEquals(0f, counterClockwise, 0.5f)
    }

    @Test
    fun distanceSmootherPublishesSeedOnFirstReading() {
        val smoother = DistanceSmoother(initialMeters = 123)
        assertEquals(123, smoother.update(123))
    }

    @Test
    fun distanceSmootherRejectsSingleFarSpike() {
        // Reported bug: a close pin (~15m) randomly lurches to a GPS-scale
        // number (~480m) for one frame, then returns. A single impulse frame
        // must be discarded outright, never published.
        val smoother = DistanceSmoother(initialMeters = 15)
        repeat(3) { smoother.update(15) }
        val duringSpike = smoother.update(480)
        assertTrue("a single 15m->480m spike must not move the pin (got $duringSpike)", duringSpike <= 20)
        val afterSpike = smoother.update(15)
        assertTrue("distance must stay at ~15m once the spike clears (got $afterSpike)", afterSpike <= 20)
    }

    @Test
    fun distanceSmootherAdoptsSustainedMovement() {
        // A genuine walk from 15m out to 60m is a sustained change, not an
        // impulse, so the median filter must adopt it within a couple of frames.
        val smoother = DistanceSmoother(initialMeters = 15)
        repeat(3) { smoother.update(15) }
        repeat(8) { smoother.update(60) }
        assertEquals(60.0, smoother.currentMeters.toDouble(), 2.0)
    }
}
