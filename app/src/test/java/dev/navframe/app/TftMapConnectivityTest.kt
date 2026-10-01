package dev.navframe.app

import org.junit.Assert.*
import org.junit.Test

class TftMapConnectivityTest {
    @Test fun phoneStoppingLeavesTftNetworkLeaseActiveAndRefreshesCurrentNetwork() {
        var sdkReferences = 1 // The visible phone MapView already owns a lease.
        var actualNetwork = false
        var observedNetwork: Boolean? = null
        val lease = TftMapConnectivity({ sdkReferences++ }, { observedNetwork = actualNetwork }, { sdkReferences-- })
        lease.refresh()
        assertEquals(false, observedNetwork)
        sdkReferences-- // Screen turns off: MapView.onStop releases the phone lease.
        actualNetwork = true // Mobile data recovers while the phone map is stopped.
        lease.refresh()
        assertEquals(true, observedNetwork)
        assertEquals(1, sdkReferences)
        lease.close()
        lease.close()
        assertEquals(0, sdkReferences)
    }

    @Test fun failedRefreshStillReleasesAcquiredSdkLease() {
        var references = 0
        val lease = TftMapConnectivity({ references++ }, { error("network service unavailable") }, { references-- })
        try { lease.refresh(); fail("Expected refresh failure") } catch (_: IllegalStateException) { }
        assertEquals(1, references)
        lease.close()
        assertEquals(0, references)
    }

    @Test fun closingUnusedRendererDoesNotReleasePhoneLease() {
        var references = 1
        val lease = TftMapConnectivity({ references++ }, { }, { references-- })
        lease.close()
        assertEquals(1, references)
    }
}
