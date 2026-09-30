package dev.navframe.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import dev.navframe.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class TftServiceVisibleGpsTest {
    private fun tick() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }

    @Test fun visibleGpsGetsFreshFixWithoutForegroundNavigationAndStopsOnLeaving() {
        val controller = Robolectric.buildService(TftService::class.java).create()
        val service = controller.get()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val manager = service.getSystemService(LocationManager::class.java)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        try {
            service.setPhoneVisible(true); tick()
            val fix = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 43.36; longitude = -5.85; accuracy = 7f; time = 1000; elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            shadowOf(manager).simulateLocation(fix); tick()
            assertTrue(service.hasFreshGpsFix())
            assertTrue(service.gpsReady.value)
            assertFalse(service.isGpsSessionActive()) // No background/location FGS was requested.
            assertEquals(NavigationMode.IDLE, service.navigationMode.value)
            assertEquals(GeoPoint(43.36, -5.85), service.navigationState.value.position)
            service.setPhoneVisible(false); tick()
            assertFalse(service.hasFreshGpsFix())
            assertFalse(service.gpsReady.value)
            shadowOf(manager).simulateLocation(Location(fix).apply { longitude = -5.8; elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() }); tick()
            assertEquals(GeoPoint(43.36, -5.85), service.navigationState.value.position)
        } finally { controller.destroy() }
    }

    @Test fun manualDisconnectPersistsPauseAndRejectsAutomaticServiceStart() {
        val controller = Robolectric.buildService(TftService::class.java).create()
        val service = controller.get()
        try {
            service.disconnect(); tick()
            assertTrue(service.getSharedPreferences("yamaha", Context.MODE_PRIVATE).getBoolean("paused", false))
            service.onStartCommand(Intent().setAction(TftService.ACTION_REAL).putExtra(TftService.EXTRA_ADDRESS, "00:11:22:33:44:55").putExtra(TftService.EXTRA_AUTO, true), 0, 1)
            tick()
            assertFalse(service.isRealSessionRequested())
            assertEquals(ConnectionState.DISCONNECTED, service.connectionState.value)
        } finally { controller.destroy() }
    }
}
