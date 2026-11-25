// Finding out how frames go (version 18): a trace to one node, its hops out and back with how well
// each heard the one before; and a range test, numbered messages to a contact while the user moves,
// each logged with where the phone was and what became of it, to export as CSV.
package org.ternmesh.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import org.ternmesh.app.R
import org.ternmesh.app.node.LocationFeed
import org.ternmesh.app.node.NodeRepository
import org.ternmesh.app.node.NodeState
import org.ternmesh.app.node.Phase
import org.ternmesh.app.node.RangeRow
import org.ternmesh.app.node.RangeTest
import org.ternmesh.app.node.rangeCsv
import org.ternmesh.app.node.rangeRows
import org.ternmesh.app.node.rangeTally
import org.ternmesh.companion.Address
import org.ternmesh.companion.Companion
import org.ternmesh.companion.Conversations
import org.ternmesh.companion.MessageState
import org.ternmesh.companion.Peer
import org.ternmesh.companion.TraceHop
import java.io.File
import java.text.DateFormat
import java.util.Date

/** The trace screen to the node whose routing id is [id]. */
fun traceRoute(id: Long) = "trace/%08x".format(id)

/** The range test screen, to [to] to begin with, or to a contact the user chooses. */
fun rangeRoute(to: Address?) = if (to == null) "range" else "range/$to"

/**
 * A trace to the routing id [to]: begun on opening, unless one to it is already out, and again
 * when asked. While it is out it says where it is; once it ends, how, and each node on the way out
 * and back, by name where the app has one, with how well it heard the one before. A node with no
 * name is given one on tapping it.
 */
@Composable
fun TraceScreen(repository: NodeRepository, state: NodeState, to: Long, back: () -> Unit) {
    val context = LocalContext.current
    val r = state.records
    val names = state.relayNames
    val target = routingLabel(context, r, names, to)
    val run = state.traces[to]
    val ready = state.phase == Phase.READY && canTrace(state)
    var begun by rememberSaveable(to) { mutableStateOf(false) }
    var naming by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(to, ready) {
        if (!begun && ready) {
            begun = true
            repository.trace(to)
        }
    }
    NodeSubScreen(stringResource(R.string.trace_title, target), back) {
        val traced = run?.traced
        val on = LocalTernColors.current.onHero
        HeroCard {
            Text(target, style = MaterialTheme.typography.headlineSmall, color = on, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                !canTrace(state) -> HeroStatus(stringResource(R.string.trace_needs_update), good = false, Modifier.padding(top = 4.dp))
                run == null -> {}
                run.running -> Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = on, trackColor = on.copy(alpha = 0.25f), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(traceStepText(context, run), style = MaterialTheme.typography.bodyMedium, color = on.copy(alpha = 0.9f))
                }
                run.failed != null -> HeroStatus(
                    stringResource(R.string.trace_failed, outcomeText(context, run.failed)),
                    good = false,
                    Modifier.padding(top = 4.dp),
                )
                traced != null -> {
                    HeroStatus(traceResultText(context, r, names, traced, target), good = traced.answered, Modifier.padding(top = 4.dp))
                    if (traced.answered) {
                        Text(
                            traceSummary(context, traced),
                            style = MaterialTheme.typography.titleMedium,
                            color = on,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }
            if (canTrace(state)) {
                HeroButton(
                    TernIcons.Wave,
                    stringResource(if (run == null) R.string.trace_start else R.string.trace_again),
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    enabled = ready && run?.running != true,
                ) { repository.trace(to) }
            }
        }
        Footnote(stringResource(R.string.trace_explained, target), Modifier.padding(top = 4.dp))
        // A leaf with no session with this node drops the trace: said before it runs, not only after.
        if (leafWithoutSession(r, to) && traced == null) {
            Footnote(stringResource(R.string.trace_leaf_hint), Modifier.padding(top = 4.dp))
        }
        if (!canTrace(state)) return@NodeSubScreen
        if (traced != null && traced.answered) {
            val self = r.self?.address?.let(r::routingIdOf)
            SectionHeader(stringResource(R.string.trace_way_out))
            SettingsGroup {
                HopRow(stringResource(R.string.trace_you), null, null, onClick = null)
                traced.wayOut.forEachIndexed { i, hop ->
                    GroupDivider()
                    HopRow(state, hop, answerer = i == traced.wayOut.size - 1) { naming = hop.routingId }
                }
            }
            SectionHeader(stringResource(R.string.trace_way_back))
            SettingsGroup {
                for (hop in traced.wayBack) {
                    HopRow(state, hop, answerer = false) { naming = hop.routingId }
                    GroupDivider()
                }
                HopRow(stringResource(R.string.trace_you), traced.snr, null, onClick = null)
            }
            // A node the user can name: neither this one, nor one a contact has.
            if (traced.hops.any { it.routingId != self && Conversations.contactOf(r, it.routingId) == null }) {
                Footnote(stringResource(R.string.trace_name_hint), Modifier.padding(top = 8.dp))
            }
        }
        Footnote(stringResource(R.string.trace_disclaimer), Modifier.padding(top = 8.dp))
    }
    naming?.let { id -> NameRelayDialog(repository, state, id) { naming = null } }
}

/** One node a trace passed through, and how well it heard the one before; tapping one no contact has names it. */
@Composable
private fun HopRow(state: NodeState, hop: TraceHop, answerer: Boolean, name: () -> Unit) {
    val context = LocalContext.current
    val r = state.records
    val self = r.self?.address?.let(r::routingIdOf)
    val nameable = hop.routingId != self && Conversations.contactOf(r, hop.routingId) == null
    val known = routingName(context, r, state.relayNames, hop.routingId)
    HopRow(
        known ?: routingText(hop.routingId),
        hop.snrQuarterDb,
        if (answerer) stringResource(R.string.trace_answerer) else null,
        mono = known == null,
        onClick = if (nameable) name else null,
    )
}

@Composable
private fun HopRow(title: String, snr: Int?, tag: String?, mono: Boolean = false, onClick: (() -> Unit)?) {
    val context = LocalContext.current
    PersonRow(
        avatar = { Avatar(title, size = 36.dp, unknown = mono) },
        title = {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                fontFamily = if (mono) FontFamily.Monospace else null,
            )
        },
        subtitle = snr?.let { context.getString(R.string.trace_heard, snrText(context, it)) },
        tag = tag,
        signal = snr,
        onClick = onClick,
    )
}

/** Names the routing id [id] on this phone alone: a relay, or any node no contact names. */
@Composable
internal fun NameRelayDialog(repository: NodeRepository, state: NodeState, id: Long, done: () -> Unit) {
    NameDialog(
        stringResource(R.string.name_relay),
        state.relayNames[id].orEmpty(),
        Companion.NAME_MAX,
        stringResource(R.string.save),
        onDismiss = done,
        explained = routingText(id) + "\n\n" + stringResource(R.string.name_relay_explained),
    ) { name ->
        done()
        repository.nameRelay(id, name)
    }
}

/**
 * The range test: a contact and how often, Start and Stop, and the log, newest first, each ping
 * with when it went, where the phone was, and what became of it. While it runs the screen stays on
 * (the window's keep-screen-on, not a wake lock: the test runs while its screen is open, and the
 * node's service keeps the link). Leaving the screen ends it, after asking if there is a log, but
 * turning the phone does not. Export shares the log as CSV.
 */
@Composable
fun RangeTestScreen(repository: NodeRepository, state: NodeState, initial: Address?, back: () -> Unit) {
    val context = LocalContext.current
    val r = state.records
    val range = state.range
    var chosen by rememberSaveable { mutableStateOf(initial?.toString()) }
    // While it runs, the contact it pings; stopped, the one chosen next, or the last.
    val to = range?.to?.takeIf { range.running } ?: chosen?.let(Address::fromHex) ?: range?.to
    var seconds by rememberSaveable { mutableIntStateOf((RangeTest.DEFAULT_INTERVAL_MS / 1000).toInt()) }
    var choosing by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val running = range?.running == true
    val logged = range?.pings?.isNotEmpty() == true

    // The screen stays on while it runs, and the test ends with the screen, but not when the
    // activity is only made again, the phone turned.
    val view = LocalView.current
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(Unit) {
        onDispose { if (context.activity()?.isChangingConfigurations != true) repository.closeRangeTest() }
    }
    var permitted by remember { mutableStateOf(LocationFeed.permitted(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permitted = LocationFeed.permitted(context)
        repository.rangePermissionChanged()
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val leave = { if (logged) leaving = true else back() }
    BackHandler(enabled = logged) { leaving = true }

    val rows = remember(range?.pings, r) { rangeRows(range?.pings.orEmpty(), r) }
    val name = to?.let { Conversations.name(r, Peer.Contact(it)) }
    // The log is of the contact it pinged, whoever is chosen next.
    val export = range?.to?.takeIf { logged }?.let { pinged -> { exportRange(context, state, pinged, Conversations.name(r, Peer.Contact(pinged))) } }
    Column(Modifier.fillMaxSize()) {
        SubScreenBar(stringResource(R.string.range_test), leave) {
            if (export != null) {
                IconButton(onClick = export) {
                    Icon(TernIcons.Share, stringResource(R.string.range_export))
                }
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                val (delivered, sent) = rangeTally(rows)
                val on = LocalTernColors.current.onHero
                HeroCard {
                    Text(
                        if (logged) stringResource(R.string.range_tally, delivered, sent) else stringResource(R.string.range_ready),
                        style = MaterialTheme.typography.headlineSmall,
                        color = on,
                    )
                    HeroStatus(
                        when {
                            running -> stringResource(R.string.range_running, ((range!!.nextAt - now) / 1000).coerceAtLeast(0).toInt())
                            logged -> stringResource(R.string.range_stopped)
                            else -> name?.let { stringResource(R.string.range_ready_to, it) } ?: stringResource(R.string.range_choose)
                        },
                        good = running || (to != null && !logged),
                        Modifier.padding(top = 4.dp),
                    )
                    if (logged && sent > 0) {
                        Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(on.copy(alpha = 0.25f))) {
                            Box(Modifier.fillMaxWidth(delivered.toFloat() / sent).height(6.dp).clip(RoundedCornerShape(3.dp)).background(on))
                        }
                    }
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (running) {
                            HeroButton(TernIcons.Power, stringResource(R.string.range_stop), Modifier.weight(1f), onClick = repository::stopRangeTest)
                        } else {
                            HeroButton(TernIcons.Send, stringResource(R.string.range_begin), Modifier.weight(1f), enabled = to != null) {
                                if (to != null) {
                                    if (!permitted) ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                    repository.startRangeTest(to, seconds * 1000L)
                                }
                            }
                        }
                        if (export != null) HeroButton(TernIcons.Share, stringResource(R.string.range_export), Modifier.weight(1f), onClick = export)
                    }
                }
                Footnote(stringResource(R.string.range_test_explained, name ?: stringResource(R.string.range_choose)), Modifier.padding(top = 4.dp))

                SectionHeader(stringResource(R.string.range_settings))
                SettingsGroup {
                    SettingsRow(
                        stringResource(R.string.range_to),
                        icon = TernIcons.People, tint = RangeTint.to,
                        value = name ?: stringResource(R.string.range_choose),
                        enabled = !running,
                        onClick = { choosing = true },
                    )
                    GroupDivider()
                    Row(Modifier.padding(start = 14.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        RowIcon(TernIcons.Wave, RangeTint.every)
                        Text(
                            stringResource(R.string.range_every, seconds),
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                            color = if (running) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Slider(
                        value = seconds.toFloat(),
                        onValueChange = { seconds = (Math.round(it / 5f) * 5).coerceIn(30, 60) },
                        valueRange = 30f..60f,
                        steps = 5,
                        enabled = !running,
                        modifier = Modifier.padding(start = 62.dp, end = 16.dp, bottom = 4.dp),
                    )
                }
                if (!permitted) {
                    Spacer(Modifier.height(12.dp))
                    SettingsGroup {
                        SettingsRow(
                            stringResource(R.string.range_allow_location),
                            icon = TernIcons.Pin, tint = LocalTernColors.current.warn,
                            subtitle = stringResource(R.string.range_no_location),
                            onClick = { ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                        )
                    }
                }
                if (!canTrace(state) && state.version != null) Footnote(stringResource(R.string.range_needs_update), Modifier.padding(top = 4.dp))
                if (rows.isNotEmpty()) SectionHeader(stringResource(R.string.range_log))
            }
            items(rows.asReversed(), key = { it.ping.n }) { row ->
                PingRow(row, rangeRowText(context, r, state.relayNames, row))
            }
        }
    }

    if (choosing) {
        ContactPicker(state, onDismiss = { choosing = false }) { address ->
            choosing = false
            chosen = address.toString()
        }
    }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text(stringResource(R.string.range_leave_title)) },
            text = { Text(stringResource(R.string.range_leave_explained)) },
            confirmButton = {
                TextButton(onClick = {
                    leaving = false
                    back()
                }) { Text(stringResource(R.string.range_leave)) }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    leaving = false
                    export?.invoke()
                }) { Text(stringResource(R.string.range_export)) }
            },
        )
    }
}

/** The colours of the range test's settings, found by colour as the node's are. */
private object RangeTint {
    val to = Color(0xFF1D4ED8)
    val every = Color(0xFF0F766E)
}

/**
 * One ping of the range test's log: its number on a badge coloured for what became of it, when it
 * went and what became of it, and where the phone was.
 */
@Composable
private fun PingRow(row: RangeRow, text: String) {
    val p = row.ping
    val extra = LocalTernColors.current
    val failed = p.unsent != null || row.state == MessageState.NOT_DELIVERED
    val hue = when {
        row.delivered -> extra.good
        failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(hue.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Text("${p.n}", style = MaterialTheme.typography.labelLarge, color = hue, maxLines = 1)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f))
                Text(
                    DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(p.at)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                p.fix?.let { f ->
                    f.accuracy?.let { stringResource(R.string.range_fix, f.lat, f.lon, Math.round(it)) }
                        ?: stringResource(R.string.range_fix_unsure, f.lat, f.lon)
                } ?: stringResource(R.string.range_no_fix),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The log as a CSV file in the app's cache, handed to whatever the user shares it with. */
private fun exportRange(context: Context, state: NodeState, to: Address, name: String) {
    val range = state.range ?: return
    val r = state.records
    val csv = rangeCsv(rangeRows(range.pings, r), to, { id -> routingName(context, r, state.relayNames, id) })
    val dir = File(context.cacheDir, "range").apply { mkdirs() }
    // One file at a time: an older log shared before is no longer needed.
    dir.listFiles()?.forEach { it.delete() }
    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(Date(range.pings.firstOrNull()?.at ?: System.currentTimeMillis()))
    val file = File(dir, "tern-range-$stamp.csv")
    file.writeText(csv)
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.range_export_subject, name))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.range_export)))
}

/** The activity a composable's context belongs to. */
private fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
