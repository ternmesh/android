// Sharing the user's position with a contact or a group: what the node is told to share, in the
// user's words, and the dialog that is the only way a SHARE is ever sent.
package org.ternmesh.app.ui

import android.Manifest
import android.content.Context
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.ternmesh.app.R
import org.ternmesh.app.node.LocationFeed
import org.ternmesh.app.node.NodeRepository
import org.ternmesh.app.node.NodeState
import org.ternmesh.companion.Body
import org.ternmesh.companion.Conversations
import org.ternmesh.companion.Peer
import kotlin.math.roundToInt

/** The precisions the specification asks a client to offer, coarse to fine. */
private val PRECISIONS = listOf(8, 12, 16, 20, 24)

/** Altitude and accuracy say more of a coarse position than its cell does: offered only from here. */
private const val FIELDS_FROM = 20

private const val ALTITUDE = 1
private const val ACCURACY = 2

/** How the node shares with one contact or group: a `SHARING` or `GROUP_SHARING` in one shape. */
data class Shared(val precision: Int, val fields: Int, val interval: Int, val minutes: Int, val record: Body)

fun sharedWith(state: NodeState, peer: Peer): Shared? = when (peer) {
    is Peer.Contact -> state.records.sharing[peer.address]?.let { Shared(it.precision, it.fields, it.interval, it.minutes, it) }
    is Peer.Group -> state.records.groupSharing[peer.group]?.let { Shared(it.precision, it.fields, it.interval, it.minutes, it) }
}

/** Whether the node speaks positions: version 5 or later. */
fun hasPositions(state: NodeState) = (state.version ?: 0) >= 5

/** How far a cell at [precision] reaches, north to south, in metres. */
fun cellMetres(precision: Int): Double = 360.0 / (1L shl precision) * 111_195.0

fun sizeText(context: Context, metres: Double): String = when {
    metres >= 10_000 -> context.getString(R.string.size_km, (metres / 1000).roundToInt().toString())
    metres >= 1_000 -> context.getString(R.string.size_km, "%.1f".format(metres / 1000))
    else -> context.getString(R.string.size_m, metres.roundToInt().coerceAtLeast(1))
}

/** A precision as a word where the specification names one, or the size of its cell. */
fun precisionText(context: Context, precision: Int): String = when (precision) {
    8 -> context.getString(R.string.precision_8)
    12 -> context.getString(R.string.precision_12)
    16 -> context.getString(R.string.precision_16)
    20 -> context.getString(R.string.precision_20)
    24 -> context.getString(R.string.precision_24)
    else -> context.getString(R.string.precision_within, sizeText(context, cellMetres(precision)))
}

/** How long sharing goes on, counted on from when its record came: "45 min left", or until stopped. */
fun leftText(context: Context, state: NodeState, s: Shared, now: Long = SystemClock.elapsedRealtime()): String {
    if (s.minutes == 0) return context.getString(R.string.until_stopped)
    val since = (now - (state.arrived[s.record] ?: now)) / 1000
    val left = (s.minutes * 60L - since).coerceAtLeast(60)
    return context.getString(R.string.time_left, spanText(left))
}

/** The elapsed clock, read again every half minute, so ages and time left count on while on screen. */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = SystemClock.elapsedRealtime()
        }
    }
    return now
}

/** The line under a conversation's title while the node shares its position there. */
@Composable
fun SharingLine(state: NodeState, peer: Peer, open: () -> Unit) {
    if (!hasPositions(state)) return
    val s = sharedWith(state, peer) ?: return
    val context = LocalContext.current
    val now = rememberNow()
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth().clickable(onClick = open)) {
        Text(
            stringResource(R.string.sharing_line, precisionText(context, s.precision), leftText(context, state, s, now)),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

/**
 * Chooses how exactly, how often and how long the node shares its position with [peer]. The
 * choice is the user's: nothing else sends a `SHARE`. The phone's location is asked for once the
 * user shares, and not before.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShareDialog(repository: NodeRepository, state: NodeState, peer: Peer, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val say = LocalSay.current
    val report = rememberReport()
    val current = sharedWith(state, peer)
    val group = peer is Peer.Group
    val intervals = if (group) listOf(300, 900, 3600) else listOf(60, 300, 900, 3600)
    val durations = listOf(60, 480, 0)
    var precision by remember { mutableIntStateOf(current?.precision ?: 16) }
    var fields by remember { mutableIntStateOf(current?.fields ?: 0) }
    var interval by remember { mutableIntStateOf(current?.interval?.takeIf { it in intervals } ?: if (group) 900 else 300) }
    var minutes by remember { mutableIntStateOf(if (current != null && current.minutes == 0) 0 else 60) }
    var asking by remember { mutableStateOf(false) }
    val noPermission = stringResource(R.string.share_no_permission)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        repository.locationPermissionChanged()
        if (!LocationFeed.permitted(context)) say(noPermission)
        onDismiss()
    }

    fun send(p: Int) {
        val f = if (p >= FIELDS_FROM) fields else 0
        val body = when (peer) {
            is Peer.Contact -> if (p == 0) Body.Share(peer.address, 0, 0, 0, 0) else Body.Share(peer.address, p, f, interval, minutes)
            is Peer.Group -> if (p == 0) Body.ShareGroup(peer.group, 0, 0, 0, 0) else Body.ShareGroup(peer.group, p, f, interval, minutes)
        }
        repository.submit(body, report)
        if (p != 0 && !LocationFeed.permitted(context)) {
            asking = true
            ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else {
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!asking) onDismiss() },
        title = { Text(stringResource(R.string.share_title, Conversations.name(state.records, peer))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.share_explained), style = MaterialTheme.typography.bodySmall)
                Heading(R.string.share_how_exact)
                for (p in PRECISIONS) {
                    val label = if (p == 24) {
                        stringResource(R.string.precision_choice_24)
                    } else {
                        stringResource(R.string.precision_choice, precisionText(context, p), sizeText(context, cellMetres(p)))
                    }
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = precision == p, onClick = { precision = p }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = precision == p, onClick = null)
                        Text(label, Modifier.padding(start = 8.dp))
                    }
                }
                if (precision >= FIELDS_FROM) {
                    Toggle(R.string.share_altitude, fields and ALTITUDE != 0) { fields = fields xor ALTITUDE }
                    Toggle(R.string.share_accuracy, fields and ACCURACY != 0) { fields = fields xor ACCURACY }
                }
                Heading(R.string.share_how_often)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in intervals) {
                        val label = if (i == 3600) stringResource(R.string.share_every_hour) else stringResource(R.string.share_every_min, i / 60)
                        FilterChip(selected = interval == i, onClick = { interval = i }, label = { Text(label) })
                    }
                }
                Heading(R.string.share_how_long)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in durations) {
                        val label = stringResource(
                            when (m) {
                                60 -> R.string.share_hour
                                480 -> R.string.share_8_hours
                                else -> R.string.share_until_stopped
                            },
                        )
                        FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text(label) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { send(precision) }, enabled = !asking) {
                Text(stringResource(if (current == null) R.string.share else R.string.share_update))
            }
        },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { send(0) }, enabled = !asking) { Text(stringResource(R.string.share_stop)) }
                TextButton(onClick = onDismiss, enabled = !asking) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun Heading(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun Toggle(text: Int, on: Boolean, flip: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = flip), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(text), Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = { flip() })
    }
}
