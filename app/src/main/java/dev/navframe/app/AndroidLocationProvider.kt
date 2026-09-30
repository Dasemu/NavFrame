package dev.navframe.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import dev.navframe.core.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Android GPS only. Locations stay in memory and are never written to logs. */
class AndroidLocationProvider(context: Context) : LocationProvider {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(LocationManager::class.java)
    private val fixes = MutableSharedFlow<LocationFix>(replay = 1, extraBufferCapacity = 1)
    override val locations = fixes.asSharedFlow()
    private val enabled = MutableStateFlow(false)
    val providerEnabled = enabled.asStateFlow()
    var lastFixElapsedMs: Long = 0
        private set
    private var listening = false
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
            if (ageMs !in 0..15_000 || !location.latitude.isFinite() || !location.longitude.isFinite()) return
            lastFixElapsedMs = location.elapsedRealtimeNanos / 1_000_000
            enabled.value = true
            fixes.tryEmit(LocationFix(
                GeoPoint(location.latitude, location.longitude),
                if (location.hasBearing()) location.bearing else Float.NaN,
                if (location.hasSpeed() && location.speed.isFinite()) location.speed.coerceAtLeast(0f) else 0f,
                if (location.hasAccuracy()) location.accuracy else Float.POSITIVE_INFINITY,
                location.time,
            ))
        }
        override fun onProviderEnabled(provider: String) { enabled.value = true }
        override fun onProviderDisabled(provider: String) { enabled.value = false; lastFixElapsedMs = 0 }
        @Deprecated("Legacy Android callback")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }
    }
    override suspend fun start() {
        check(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            "Autoriza ubicación precisa para usar el GPS"
        }
        check(LocationManager.GPS_PROVIDER in manager.allProviders) { "GPS no disponible en este dispositivo" }
        if (listening) return
        lastFixElapsedMs = 0
        enabled.value = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, listener, Looper.getMainLooper())
            listening = true
        } catch (error: Exception) { manager.removeUpdates(listener); throw error }
    }
    override suspend fun stop() { close() }
    fun close() {
        try { if (listening) manager.removeUpdates(listener) }
        finally { listening = false; enabled.value = false; lastFixElapsedMs = 0 }
    }
}
