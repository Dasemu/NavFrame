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

/** Native Android location sources and compass. Positions stay in memory and are never logged. */
class AndroidLocationProvider(context: Context) : LocationProvider {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(LocationManager::class.java)
    private val fixes = MutableSharedFlow<LocationFix>(replay = 1, extraBufferCapacity = 1)
    override val locations = fixes.asSharedFlow()
    private val enabled = MutableStateFlow(false)
    val providerEnabled = enabled.asStateFlow()
    private val headings = MutableStateFlow(Float.NaN)
    val bearings = headings.asStateFlow()
    private val compass = AndroidCompass(this.context) { updateBearing() }
    private val registered = mutableSetOf<String>()
    private var lastLocation: Location? = null
    val currentBearing: Float get() { updateBearing(); return headings.value }
    var lastFixElapsedMs: Long = 0
        private set
    internal var headingFromCompass = false
        private set
    private var listening = false
    private fun updateBearing() {
        val fix = lastLocation
        val course = if (fix?.hasBearing() == true && (!fix.hasBearingAccuracy() || fix.bearingAccuracyDegrees <= 45f)) fix.bearing else Float.NaN
        val recent = fix != null && locationAgeMs(fix) in 0..MAX_FIX_AGE_MS
        val compassHeading = compass.heading()
        val speed = if (recent && fix!!.hasSpeed()) fix.speed else 0f
        headingFromCompass = compassHeading.isFinite() && !(speed >= 2f && recent && course.isFinite())
        headings.value = selectHeading(if (recent) course else Float.NaN, speed, compassHeading)
    }
    private fun accept(location: Location) {
        if (!validLocation(location)) return
        val previous = lastLocation
        if (previous != null && validLocation(previous)) {
            val newerMs = (location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000
            if (location.accuracy > MAP_ACCURACY_METERS && previous.accuracy <= MAP_ACCURACY_METERS) return
            if (newerMs < 0 || (newerMs < 5_000 && location.accuracy > previous.accuracy * 2 + 20)) return
        }
        lastLocation = Location(location)
        lastFixElapsedMs = location.elapsedRealtimeNanos / 1_000_000
        refreshEnabled()
        compass.updatePosition(GeoPoint(location.latitude, location.longitude))
        fixes.tryEmit(LocationFix(
            GeoPoint(location.latitude, location.longitude), currentBearing,
            if (location.hasSpeed() && location.speed.isFinite()) location.speed.coerceAtLeast(0f) else 0f,
            location.accuracy, location.time,
        ))
    }
    private fun refreshEnabled() { enabled.value = registered.any { it != LocationManager.PASSIVE_PROVIDER && manager.isProviderEnabled(it) } }
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) { if (listening) accept(location) }
        override fun onProviderEnabled(provider: String) { refreshEnabled() }
        override fun onProviderDisabled(provider: String) { refreshEnabled(); if (!enabled.value) lastFixElapsedMs = 0 }
        @Deprecated("Legacy Android callback")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }
    }
    override suspend fun start() {
        check(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            "Autoriza ubicación precisa para usar la ubicación"
        }
        if (listening) return
        lastFixElapsedMs = 0
        lastLocation = null
        val available = manager.allProviders.filter { it in setOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused", LocationManager.PASSIVE_PROVIDER) }
        check(available.any { it != LocationManager.PASSIVE_PROVIDER }) { "Ubicación no disponible en este dispositivo" }
        listening = true
        try {
            for (provider in available) {
                try {
                    manager.requestLocationUpdates(provider, 500L, 0f, listener, Looper.getMainLooper())
                    registered += provider
                } catch (_: IllegalArgumentException) { /* An optional provider can disappear during registration. */ }
            }
            check(registered.any { it != LocationManager.PASSIVE_PROVIDER }) { "No se pudo activar la ubicación" }
            refreshEnabled()
            compass.start()
            // Seed promptly from a recent fix Android already has, instead of waiting for a new satellite fix.
            registered.filter { manager.isProviderEnabled(it) }.mapNotNull { try { manager.getLastKnownLocation(it) } catch (_: IllegalArgumentException) { null } }
                .filter(::validLocation).minByOrNull { it.accuracy + locationAgeMs(it) / 100f }?.let(::accept)
        } catch (error: Exception) { close(); throw error }
    }
    override suspend fun stop() { close() }
    fun close() {
        try { if (listening) manager.removeUpdates(listener) }
        finally {
            compass.close(); registered.clear(); listening = false; enabled.value = false
            lastFixElapsedMs = 0; lastLocation = null; headings.value = Float.NaN; headingFromCompass = false
        }
    }
    companion object {
        const val MAX_FIX_AGE_MS = 15_000L
        const val MAP_ACCURACY_METERS = 100f
        const val GUIDANCE_ACCURACY_METERS = 25f
        internal fun locationAgeMs(location: Location) = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        internal fun validLocation(location: Location): Boolean = locationAgeMs(location) in 0..MAX_FIX_AGE_MS &&
            location.latitude.isFinite() && location.latitude in -90.0..90.0 && location.longitude.isFinite() && location.longitude in -180.0..180.0 &&
            location.hasAccuracy() && location.accuracy.isFinite() && location.accuracy >= 0
    }
}
