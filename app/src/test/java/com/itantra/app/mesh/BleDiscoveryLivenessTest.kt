package com.itantra.app.mesh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the two ways the rescue page used to lose nearby peers:
 *
 *  1. a "scanning" flag that no longer matched the platform scan, so a scan the
 *     platform killed silently could never be re-armed (the peer list stayed
 *     empty until the app was relaunched), and
 *  2. a peer presence TTL shorter than the worst-case advertise restart, so a
 *     live device was pruned from the list and then reappeared.
 *
 * The timing arithmetic is asserted directly, so changing any of the constants
 * involved fails here instead of silently reintroducing the field symptoms.
 */
class BleDiscoveryLivenessTest {

    private fun liveness(
        sessionStartedEpochMs: Long,
        callbackCount: Int,
        lastCallbackEpochMs: Long
    ) = ScanSessionLiveness(
        sessionStartedEpochMs = sessionStartedEpochMs,
        callbackCount = callbackCount,
        lastCallbackEpochMs = lastCallbackEpochMs
    )

    private fun stalled(
        scanRequested: Boolean = true,
        believedScanning: Boolean = true,
        adapterEnabled: Boolean = true,
        liveness: ScanSessionLiveness,
        nowEpochMs: Long
    ) = shouldRestartStalledScan(
        scanRequested = scanRequested,
        believedScanning = believedScanning,
        adapterEnabled = adapterEnabled,
        liveness = liveness,
        nowEpochMs = nowEpochMs,
        warmupMs = BleMeshManager.SCAN_STALL_WARMUP_MS,
        stallTimeoutMs = BleMeshManager.SCAN_STALL_TIMEOUT_MS,
        minCallbacks = BleMeshManager.SCAN_STALL_MIN_CALLBACKS
    )

    @Test
    fun scanThatWentSilentAfterProvenLivenessIsRestarted() {
        // Session ran for 60 s and delivered plenty, then went quiet for 20 s.
        assertTrue(
            stalled(
                liveness = liveness(
                    sessionStartedEpochMs = 1_000L,
                    callbackCount = 400,
                    lastCallbackEpochMs = 61_000L
                ),
                nowEpochMs = 81_000L
            )
        )
    }

    @Test
    fun scanStillDeliveringIsNeverRestarted() {
        assertFalse(
            stalled(
                liveness = liveness(
                    sessionStartedEpochMs = 1_000L,
                    callbackCount = 400,
                    lastCallbackEpochMs = 79_000L
                ),
                nowEpochMs = 81_000L
            )
        )
    }

    @Test
    fun silenceShorterThanStallTimeoutIsNotRestarted() {
        assertFalse(
            stalled(
                liveness = liveness(
                    sessionStartedEpochMs = 1_000L,
                    callbackCount = 400,
                    lastCallbackEpochMs = 62_000L
                ),
                nowEpochMs = 81_000L // 19 s of silence, 1 s short of the timeout
            )
        )
    }

    @Test
    fun quietEnvironmentThatWasNeverAliveIsNotRestarted() {
        // A radio with nothing around it must not be mistaken for a dead scan:
        // only two packets were ever heard in this session.
        assertFalse(
            stalled(
                liveness = liveness(
                    sessionStartedEpochMs = 1_000L,
                    callbackCount = 2,
                    lastCallbackEpochMs = 5_000L
                ),
                nowEpochMs = 200_000L
            )
        )
    }

    @Test
    fun scanInsideWarmupIsNotRestarted() {
        assertFalse(
            stalled(
                liveness = liveness(
                    sessionStartedEpochMs = 1_000L,
                    callbackCount = 400,
                    lastCallbackEpochMs = 5_000L
                ),
                nowEpochMs = 20_000L // 19 s into a 30 s warm-up
            )
        )
    }

    @Test
    fun sessionThatNeverStartedIsNotRestarted() {
        assertFalse(
            stalled(
                liveness = ScanSessionLiveness(),
                nowEpochMs = 500_000L
            )
        )
    }

    @Test
    fun scanNotRequestedIsNeverRestarted() {
        assertFalse(
            stalled(
                scanRequested = false,
                liveness = liveness(1_000L, 400, 61_000L),
                nowEpochMs = 200_000L
            )
        )
    }

    @Test
    fun scanOnADisabledAdapterIsNeverRestarted() {
        assertFalse(
            stalled(
                adapterEnabled = false,
                liveness = liveness(1_000L, 400, 61_000L),
                nowEpochMs = 200_000L
            )
        )
    }

    /**
     * Symptom 2 ("appears, then vanishes"): the advertise watchdog can take the
     * beacon off the air for a full period plus one failed-restart retry chain,
     * so a presence TTL below that window prunes a device that is still there.
     */
    @Test
    fun presenceTtlOutlastsWorstCaseAdvertiseGap() {
        val worstCaseOffAirMs = BleMeshManager.ADVERT_WATCHDOG_PERIOD_MS +
            BleMeshManager.ADVERTISE_RETRY_DELAY_MS * 3
        assertTrue(
            "peer presence TTL (${BleMeshManager.BEACON_STALE_MS} ms) must outlast the " +
                "worst-case advertise off-air window ($worstCaseOffAirMs ms), otherwise a " +
                "live device is pruned from the list and reappears",
            BleMeshManager.BEACON_STALE_MS > worstCaseOffAirMs
        )
    }

    /**
     * Once the bounded retry schedule gives up, the watchdog is the only path
     * back on the air, so it has to fit inside the presence TTL too.
     */
    @Test
    fun watchdogRecoveryFitsInsidePresenceTtl() {
        assertTrue(
            "advertise watchdog (${BleMeshManager.ADVERT_WATCHDOG_PERIOD_MS} ms) must fit " +
                "inside the presence TTL (${BleMeshManager.BEACON_STALE_MS} ms)",
            BleMeshManager.BEACON_STALE_MS > BleMeshManager.ADVERT_WATCHDOG_PERIOD_MS
        )
    }
}
