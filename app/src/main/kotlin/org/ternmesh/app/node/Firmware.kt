// New firmware for the node: the project's manifest and images, fetched from ternmesh.org, and where
// an update of the node is, for the screens. Giving the image to the node is the protocol module's
// Updater, which NodeRepository drives over its connection.
package org.ternmesh.app.node

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.ternmesh.companion.Manifest
import org.ternmesh.companion.Offer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Where an update of the node's firmware is. */
sealed interface FirmwareUpdate {
    data object Idle : FirmwareUpdate

    data object Checking : FirmwareUpdate

    /**
     * The manifest read. [image] is the one for the node's board and region, null if none is
     * published; [offer] says whether its release is newer than the node's.
     */
    data class Checked(val release: String, val image: Manifest.Image?, val offer: Offer) : FirmwareUpdate

    /** The manifest could not be fetched or read. */
    data object CheckFailed : FirmwareUpdate

    data class Downloading(val release: String, val received: Long, val size: Long) : FirmwareUpdate

    /** Giving the image to the node. [waiting] while the link is down: it goes on once the node is back. */
    data class Sending(val release: String, val sent: Long, val size: Long, val waiting: Boolean) : FirmwareUpdate

    /**
     * All of it sent, and the node told to run it. [confirmed] if it answered: then it is restarting
     * into the image. If not, it may be, or may not have heard. Its release says which once it is back.
     */
    data class Restarting(val release: String, val confirmed: Boolean) : FirmwareUpdate

    /** The node is back, running [release]. */
    data class Done(val release: String) : FirmwareUpdate

    /** The node is back running [running], not [wanted]. [confirmed]: it had taken the image, so it did not start it. */
    data class NotRunning(val running: String, val wanted: String, val confirmed: Boolean) : FirmwareUpdate

    /** The node refused the update with this code. */
    data class Refused(val code: Int) : FirmwareUpdate

    data class Failed(val why: FirmwareFailure) : FirmwareUpdate
}

/** How far a download or a send has gone, in percent; null for an update doing neither. */
fun percent(u: FirmwareUpdate): Int? = when (u) {
    is FirmwareUpdate.Downloading -> (u.received * 100 / u.size).toInt()
    is FirmwareUpdate.Sending -> (u.sent * 100 / u.size).toInt()
    else -> null
}

enum class FirmwareFailure {
    /** The image could not be downloaded. */
    DOWNLOAD,

    /** What was downloaded is not the image the manifest names: its size or SHA-256 is wrong. */
    CORRUPT,

    /** The node's firmware does not speak a version of the protocol that has updates. */
    UNSUPPORTED,

    /** The node's board or region is no longer the one the image was chosen for. */
    CHANGED,
}

internal object FirmwareDownload {
    /** The most a manifest may be: it lists a few dozen images. */
    private const val MANIFEST_MAX = 256 * 1024

    suspend fun manifest(): Manifest = withContext(Dispatchers.IO) {
        val bytes = fetch(Manifest.LATEST, MANIFEST_MAX.toLong(), exact = false) {}
        try {
            Manifest.parse(String(bytes, Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            throw IOException("not a manifest: ${e.message}")
        }
    }

    /**
     * The image, checked against the manifest's size and SHA-256 before it is returned. [progress] is
     * called from the download's thread with the bytes received so far. Throws [Corrupt] for an image
     * that is not the one named, IOException for one that could not be fetched.
     */
    suspend fun image(image: Manifest.Image, progress: (Long) -> Unit): ByteArray = withContext(Dispatchers.IO) {
        val bytes = fetch(image.url, image.size, exact = true, progress)
        if (!MessageDigest.getInstance("SHA-256").digest(bytes).contentEquals(image.digest)) throw Corrupt()
        bytes
    }

    class Corrupt : IOException("not the image the manifest names")

    private suspend fun fetch(url: String, limit: Long, exact: Boolean, progress: (Long) -> Unit): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = true
            if (c.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${c.responseCode} for $url")
            val out = ByteArrayOutputStream(if (exact) limit.toInt() else 8192)
            val buffer = ByteArray(16 * 1024)
            c.inputStream.use { input ->
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > limit) throw if (exact) Corrupt() else IOException("longer than $limit bytes")
                    progress(out.size().toLong())
                }
            }
            if (exact && out.size().toLong() != limit) throw Corrupt()
            return out.toByteArray()
        } finally {
            c.disconnect()
        }
    }
}
