package dev.navframe.app

import android.Manifest
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import dev.navframe.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidLocationProviderTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(LocationManager::class.java)
    @Before fun permissions() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
    }
    private fun fix(provider: String = LocationManager.GPS_PROVIDER, accuracy: Float = 7f, ageMs: Long = 0) = Location(provider).apply {
        latitude = 43.36; longitude = -5.85; this.accuracy = accuracy; time = 1000
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - ageMs * 1_000_000
    }
    private fun tick() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }

    @Test fun recentNetworkCacheIsAvailableImmediatelyAndAllListenersAreReleased() = runBlocking {
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix(LocationManager.NETWORK_PROVIDER, 60f))
        val provider = AndroidLocationProvider(context)
        try {
            provider.start()
            assertEquals(60f, provider.locations.replayCache.single().accuracyMeters)
            assertTrue(shadowOf(manager).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isNotEmpty())
            assertTrue(shadowOf(manager).getLocationUpdateListeners(LocationManager.NETWORK_PROVIDER).isNotEmpty())
        } finally { provider.close() }
        assertTrue(shadowOf(manager).locationUpdateListeners.isEmpty())
        assertFalse(provider.providerEnabled.value)
    }

    @Test fun staleInvalidAndFutureCachedFixesAreRejected() = runBlocking {
        for (location in listOf(fix(ageMs = 15_001), fix(ageMs = -1000), fix().apply { latitude = 91.0 }, fix().apply { accuracy = Float.NaN })) {
            shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, location)
            val provider = AndroidLocationProvider(context)
            try { provider.start(); assertTrue(provider.locations.replayCache.isEmpty()) }
            finally { provider.close() }
        }
    }

    @Test fun coarseNetworkUpdatesCannotReplaceARecentAccurateFix() = runBlocking {
        val provider = AndroidLocationProvider(context)
        try {
            provider.start()
            shadowOf(manager).simulateLocation(fix()); tick()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
            shadowOf(manager).simulateLocation(fix(LocationManager.NETWORK_PROVIDER, 500f).apply { latitude = 43.4 }); tick()
            assertEquals(7f, provider.locations.replayCache.single().accuracyMeters)
            assertEquals(43.36, provider.locations.replayCache.single().position.latitude, 0.000001)
        } finally { provider.close() }
    }

    @Test fun disablingOneProviderDoesNotInvalidateTheOtherAndPassiveAloneIsNotReady() = runBlocking {
        val provider = AndroidLocationProvider(context)
        try {
            provider.start()
            shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false); tick()
            assertTrue(provider.providerEnabled.value)
            shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false); tick()
            if ("fused" in manager.allProviders) { shadowOf(manager).setProviderEnabled("fused", false); tick() }
            assertFalse(provider.providerEnabled.value)
        } finally { provider.close() }
    }

    @Test fun serviceAllowsApproximateRecentPositionForMapWithoutClaimingPreciseGuidance() {
        val controller = Robolectric.buildService(TftService::class.java).create()
        val service = controller.get()
        try {
            service.setPhoneVisible(true); tick()
            shadowOf(manager).simulateLocation(fix(LocationManager.NETWORK_PROVIDER, 80f)); tick()
            assertTrue(service.hasFreshGpsFix())
            assertTrue(service.gpsReady.value)
            assertEquals(80f, service.navigationState.value.accuracyMeters)
            assertEquals(NavigationStatus.NAVIGATING, service.navigationState.value.navigationStatus)
            val route = RouteResult(listOf(GeoPoint(43.36, -5.85), GeoPoint(43.37, -5.85)), emptyList(), 1100, 100)
            assertEquals(NavigationStatus.GPS_LOST, RouteGuidanceTracker(route).update(LocationFix(GeoPoint(43.36, -5.85), Float.NaN, 0f, 80f, 1000), 0, 0).navigationStatus)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
            assertFalse(service.hasFreshGpsFix())
            assertEquals(NavigationStatus.GPS_LOST, service.navigationState.value.navigationStatus)
            assertNull(service.navigationState.value.accuracyMeters)
            service.setPhoneVisible(false); tick()
            assertFalse(service.hasFreshGpsFix())
        } finally { controller.destroy() }
    }

    @Test fun compassDrivesStationaryHeadingButMovingGpsWinsAndSensorsStopOnClose() = runBlocking {
        val sensors = context.getSystemService(SensorManager::class.java)
        val sensor = ShadowSensor.newInstance(Sensor.TYPE_ROTATION_VECTOR)
        shadowOf(sensors).addSensor(sensor)
        val provider = AndroidLocationProvider(context)
        try {
            provider.start()
            val event = ShadowSensorManager.createSensorEvent(4).apply {
                this.sensor = sensor; accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
                values[0] = 0f; values[1] = 0f; values[2] = 0f; values[3] = 1f
            }
            shadowOf(sensors).sendSensorEventToListeners(event); tick()
            assertTrue(provider.currentBearing.isFinite())
            assertTrue(provider.headingFromCompass)
            shadowOf(manager).simulateLocation(fix().apply { speed = 12f; bearing = 90f }); tick()
            assertEquals(90f, provider.currentBearing)
            assertFalse(provider.headingFromCompass)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
            shadowOf(manager).simulateLocation(fix().apply { speed = 0f; bearing = 90f }); tick()
            assertTrue(provider.headingFromCompass)
            event.accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
            shadowOf(sensors).sendSensorEventToListeners(event); tick()
            assertTrue(provider.currentBearing.isNaN())
        } finally { provider.close() }
        assertTrue(shadowOf(sensors).listeners.isEmpty())
    }

    @Test fun headingSelectionAndSmoothingRespectNorthWrapAndMissingSensors() {
        assertEquals(90f, selectHeading(90f, 12f, 180f))
        assertEquals(180f, selectHeading(90f, 0f, 180f))
        assertTrue(selectHeading(90f, 0f, Float.NaN).isNaN())
        assertTrue(selectHeading(Float.NaN, 12f, Float.NaN).isNaN())
        assertEquals(354f, smoothHeading(350f, 10f))
        assertEquals(6f, smoothHeading(10f, 350f))
    }
}
