// The phone's own position while a range test runs, for its log: asked for every few seconds,
// moving or not, and only with the user's permission. Nothing of it goes to the node. It runs on
// the main thread, as the repository does.
package org.ternmesh.app.node

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock

class PhoneFixes(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var listening = false
    private var latest: Location? = null

    // Every method spelled out, as in LocationFeed: before Android 11 the three beside
    // onLocationChanged have no default.
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = take(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Called only before Android 10")
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }

    /** Listens, if the user allows it; again after permission is given, it starts. */
    @SuppressLint("MissingPermission") // permitted() is asked first, and a permission taken away since only fails the call
    fun start() {
        if (listening || manager == null || !LocationFeed.permitted(context)) return
        listening = true
        val providers = LocationFeed.providers(manager)
        for (p in providers) {
            runCatching { manager.requestLocationUpdates(p, INTERVAL_MS, 0f, listener, Looper.getMainLooper()) }
        }
        providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
            ?.let(::take)
    }

    fun stop() {
        if (!listening) return
        listening = false
        manager?.removeUpdates(listener)
        latest = null
    }

    /** The newest fix, if it is recent enough to say where a ping went from; null for none. */
    fun now(): PhoneFix? {
        val l = latest ?: return null
        val age = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos).coerceAtLeast(0) / 1_000_000_000
        if (age > STALE_S) return null
        return PhoneFix(l.latitude, l.longitude, l.accuracy.takeIf { l.hasAccuracy() && it > 0 }, age)
    }

    private fun take(location: Location) {
        val l = latest
        // A network fix just after a satellite one is not better because it is newer.
        val worse = l != null && location.hasAccuracy() && l.hasAccuracy() && location.accuracy > l.accuracy * 2 &&
            (location.elapsedRealtimeNanos - l.elapsedRealtimeNanos) < BETTER_NS
        if (!worse) latest = location
    }

    private companion object {
        const val INTERVAL_MS = 5_000L

        /** A fix older than this says nothing of where a ping went from, moving. */
        const val STALE_S = 120L

        /** How long a much better fix stands against a worse one that comes after it. */
        const val BETTER_NS = 20_000_000_000L
    }
}
