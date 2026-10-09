// The phone's position, given to the node while the node shares its position with someone: the
// node rounds it to each destination's cell, so the exact fix goes no further than the node.
//
// It listens only while the repository says to, and only with the user's permission. It runs on
// the main thread, as the repository does.
package org.ternmesh.app.node

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.ternmesh.companion.Body
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class LocationFeed(private val context: Context, private val give: (Body.SetPosition) -> Unit) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var listening = false

    /** When the last position went to the node, on the elapsed clock; none since listening began, 0. */
    private var lastGiven = 0L

    private val listener = LocationListener { location -> take(location) }

    /** Listens while [wanted] and the user allows it; stops otherwise. */
    @SuppressLint("MissingPermission") // permitted() is asked first, and a permission taken away since only fails the call
    fun want(wanted: Boolean) {
        val on = wanted && permitted(context) && manager != null
        if (on == listening) return
        listening = on
        if (!on) {
            manager?.removeUpdates(listener)
            return
        }
        lastGiven = 0
        val providers = providers()
        // Every half minute, moving or not: the node does not share a fix more than an hour old, and
        // a user standing still is still where they are.
        for (p in providers) {
            runCatching { manager!!.requestLocationUpdates(p, INTERVAL_MS, 0f, listener, Looper.getMainLooper()) }
        }
        // What the phone already knows, if it is recent, so the node need not wait for the next fix.
        providers.mapNotNull { runCatching { manager!!.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
            ?.takeIf { ageSeconds(it) < RECENT_S }
            ?.let(::take)
    }

    private fun providers(): List<String> {
        val m = manager ?: return emptyList()
        // The fused provider weighs satellites against networks itself; without it, both, and the
        // better fix wins as each arrives.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && m.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
            return listOf(LocationManager.FUSED_PROVIDER)
        }
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
            runCatching { m.isProviderEnabled(it) }.getOrDefault(false)
        }
    }

    private fun take(location: Location) {
        if (!listening) return
        val now = SystemClock.elapsedRealtime()
        // Not more often than every few seconds, as the specification asks of a client.
        if (lastGiven != 0L && now - lastGiven < MIN_GAP_MS) return
        lastGiven = now
        give(positionOf(location))
    }

    private fun ageSeconds(location: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos).coerceAtLeast(0) / 1_000_000_000

    /** [location] as `SET_POSITION` gives it: Android's altitude is already above the WGS 84 ellipsoid. */
    private fun positionOf(location: Location) = Body.SetPosition(
        lat = (location.latitude * 1e7).roundToLong().coerceIn(-900_000_000, 900_000_000).toInt(),
        lon = (location.longitude * 1e7).roundToLong().coerceIn(-1_800_000_000, 1_800_000_000).toInt(),
        altitude = if (location.hasAltitude()) {
            location.altitude.roundToInt().coerceIn(-32767, 32767)
        } else {
            org.ternmesh.companion.Companion.NO_ALTITUDE
        },
        accuracy = if (location.hasAccuracy() && location.accuracy > 0) ceil(location.accuracy.toDouble()).toInt().coerceIn(1, 65535) else 0,
        age = ageSeconds(location).coerceAtMost(65535).toInt(),
    )

    companion object {
        private const val INTERVAL_MS = 30_000L
        private const val MIN_GAP_MS = 15_000L
        private const val RECENT_S = 600L

        fun permitted(context: Context) = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }
}
