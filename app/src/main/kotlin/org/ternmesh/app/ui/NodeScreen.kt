// The node itself: its address to give others, as a QR code, a link and the short code; its battery
// and airtime, the nodes it hears, the four settings a client may change, and its firmware and
// updating it; and disconnecting from it, changing it, or forgetting it.
package org.ternmesh.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.ternmesh.app.R
import org.ternmesh.app.node.ChosenNode
import org.ternmesh.app.node.FirmwareFailure
import org.ternmesh.app.node.FirmwareUpdate
import org.ternmesh.app.node.NodeRepository
import org.ternmesh.app.node.NodeState
import org.ternmesh.app.node.Phase
import org.ternmesh.app.node.percent
import org.ternmesh.companion.Body
import org.ternmesh.companion.ErrorCode
import org.ternmesh.companion.Offer
import org.ternmesh.companion.Setting
import org.ternmesh.companion.Sharing

/** The regions the specification's profiles define. */
internal val REGIONS = listOf("EU868", "US915")

@Composable
fun NodeScreen(repository: NodeRepository, state: NodeState) {
    val context = LocalContext.current
    val say = LocalSay.current
    val report = rememberReport()
    val r = state.records
    val self = r.self
    var forgetting by remember { mutableStateOf<ChosenNode?>(null) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section(stringResource(R.string.node_identity)) {
            Text(state.node?.name ?: stringResource(R.string.unnamed_device), style = MaterialTheme.typography.titleMedium)
            Text(phaseText(context, state))
            state.firmware?.let { fw ->
                Text("${stringResource(R.string.node_firmware)}: $fw · ${stringResource(R.string.node_protocol, state.version ?: 0)}")
            }
            self?.let { s ->
                Text(stringResource(R.string.node_address), style = MaterialTheme.typography.labelLarge)
                QrCode(s.address, Modifier.fillMaxWidth().align(Alignment.CenterHorizontally))
                Text(stringResource(R.string.qr_explained), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.short_code), style = MaterialTheme.typography.labelLarge)
                Text(Sharing.shortCode(s.address), style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
                Text(stringResource(R.string.short_code_explained), style = MaterialTheme.typography.bodySmall)
                SelectionContainer { Text(Sharing.grouped(s.address), fontFamily = FontFamily.Monospace) }
                Row {
                    TextButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.node_address), Sharing.text(s.address)))
                        say(context.getString(R.string.copied))
                    }) { Text(stringResource(R.string.copy)) }
                    TextButton(onClick = { shareLink(context, s.address) }) { Text(stringResource(R.string.share)) }
                }
                Text("${stringResource(R.string.node_clock)}: ${timeText(context, s.time).ifEmpty { stringResource(R.string.clock_unset) }}")
            }
        }

        r.power?.let { p ->
            Section(stringResource(R.string.battery)) {
                val percent = if (p.percent == 255) stringResource(R.string.unknown) else "${p.percent}%"
                Text(stringResource(R.string.battery_value, percent, p.millivolts / 1000.0))
                if (p.flags and 1 != 0) Text(stringResource(R.string.charging))
                if (p.flags and 2 != 0) Text(stringResource(R.string.external_power))
            }
        }

        r.airtime?.let { a ->
            Section(stringResource(R.string.airtime)) {
                if (a.period == 0L) {
                    Text(stringResource(R.string.airtime_unlimited))
                } else {
                    Text(stringResource(R.string.airtime_value, a.used / 1000.0, a.allowed / 1000.0, spanText(a.period)))
                }
                if (a.wait > 0) Text(stringResource(R.string.airtime_wait, a.wait / 1000.0))
            }
        }

        Section(stringResource(R.string.neighbours)) {
            val neighbours = r.neighbours.values.sortedBy { it.heard }
            if (neighbours.isEmpty()) Text(stringResource(R.string.neighbours_none))
            for (n in neighbours) {
                val role = stringResource(if (n.role == 1) R.string.role_relay_short else R.string.role_leaf_short)
                Text(
                    stringResource(
                        R.string.neighbour_line, "%08x".format(n.routingId), n.snrQuarterDb / 4.0,
                        stringResource(R.string.heard_ago, spanText(n.heard.toLong())),
                    ) + " · " + role,
                )
            }
        }

        self?.let { s ->
            Section(stringResource(R.string.settings)) {
                Choice(
                    stringResource(R.string.region), s.region.ifEmpty { stringResource(R.string.region_none) },
                    REGIONS.map { it to it },
                    // An image is for one region, and a new region restarts the node: not while one is on its way.
                    enabled = state.update !is FirmwareUpdate.Downloading && state.update !is FirmwareUpdate.Sending,
                ) { repository.submit(Body.Set(Setting.Region(it)), report) }
                HorizontalDivider()
                Choice(
                    stringResource(R.string.role),
                    stringResource(if (s.role == 1) R.string.role_relay else R.string.role_leaf),
                    listOf(stringResource(R.string.role_leaf) to 0, stringResource(R.string.role_relay) to 1),
                ) { repository.submit(Body.Set(Setting.Role(it)), report) }
                HorizontalDivider()
                NumberSetting(stringResource(R.string.power), stringResource(R.string.power_value, s.power), "${s.power}") { text ->
                    text.toIntOrNull()?.let { repository.submit(Body.Set(Setting.Power(it)), report) }
                }
                HorizontalDivider()
                NumberSetting(stringResource(R.string.passkey), stringResource(R.string.passkey_hint), "") { text ->
                    val passkey = if (text.isEmpty()) Setting.Passkey.RANDOM else text.toLongOrNull()?.takeIf { it in 0..999_999 }
                    passkey?.let { repository.submit(Body.Set(Setting.Passkey(it)), report) }
                }
            }
        }

        if (state.board != null || state.version != null) {
            Section(stringResource(R.string.firmware)) { FirmwareUpdates(repository, state) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.phase == Phase.DISCONNECTED) {
                Button(onClick = repository::connect) { Text(stringResource(R.string.connect)) }
            } else {
                OutlinedButton(onClick = repository::disconnect) { Text(stringResource(R.string.disconnect)) }
            }
            OutlinedButton(onClick = repository::leave) { Text(stringResource(R.string.change_node)) }
        }
        state.node?.let { node ->
            TextButton(onClick = { forgetting = node }) {
                Text(stringResource(R.string.forget_node), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    forgetting?.let { node -> ForgetDialog(node, onDismiss = { forgetting = null }) { repository.forget(node.address) } }
}

/**
 * The node's release and board, and updating it: checking ternmesh.org, asking before an update,
 * how far one has gone, and what it came to.
 */
@Composable
private fun FirmwareUpdates(repository: NodeRepository, state: NodeState) {
    val context = LocalContext.current
    val board = state.board
    var confirming by remember { mutableStateOf<FirmwareUpdate.Checked?>(null) }

    if (board.isNullOrEmpty()) {
        // A node of version 3 or earlier says no board; one built without a way to update says an empty one.
        Text(stringResource(if (board == null) R.string.firmware_too_old else R.string.firmware_usb_only))
        return
    }
    val release = state.release.orEmpty()
    Text(
        if (release.isEmpty()) stringResource(R.string.firmware_no_release, board) else stringResource(R.string.firmware_release, release, board),
    )
    val ready = state.phase == Phase.READY
    when (val u = state.update) {
        FirmwareUpdate.Idle -> Button(onClick = repository::checkForUpdate, enabled = ready) { Text(stringResource(R.string.update_check)) }
        FirmwareUpdate.Checking -> Text(stringResource(R.string.update_checking))
        FirmwareUpdate.CheckFailed -> {
            Text(stringResource(R.string.update_check_failed))
            TextButton(onClick = repository::checkForUpdate, enabled = ready) { Text(stringResource(R.string.retry)) }
        }
        is FirmwareUpdate.Checked -> {
            val region = state.records.self?.region.orEmpty()
            when {
                u.image == null && region.isEmpty() -> Text(stringResource(R.string.update_no_region))
                u.image == null -> Text(stringResource(R.string.update_no_image, u.release, board, region))
                u.offer == Offer.CURRENT -> Text(stringResource(R.string.update_up_to_date, u.release))
                else -> {
                    Text(stringResource(if (u.offer == Offer.NEWER) R.string.update_newer else R.string.update_unknown, u.release))
                    Button(onClick = { confirming = u }, enabled = ready) { Text(stringResource(R.string.update)) }
                }
            }
            if (u.image == null || u.offer == Offer.CURRENT) {
                TextButton(onClick = repository::checkForUpdate, enabled = ready) { Text(stringResource(R.string.update_check)) }
            }
        }
        is FirmwareUpdate.Downloading -> Progress(
            stringResource(
                R.string.update_downloading, u.release, percent(u) ?: 0,
                Formatter.formatFileSize(context, u.received), Formatter.formatFileSize(context, u.size),
            ),
            u.received.toFloat() / u.size,
            cancel = repository::cancelUpdate,
        )
        is FirmwareUpdate.Sending -> Progress(
            if (u.waiting) {
                stringResource(R.string.update_waiting)
            } else {
                stringResource(
                    R.string.update_sending, u.release, percent(u) ?: 0,
                    Formatter.formatFileSize(context, u.sent), Formatter.formatFileSize(context, u.size),
                )
            },
            u.sent.toFloat() / u.size,
            // Once every byte is sent the node has been told to run them: that cannot be taken back.
            cancel = repository::cancelUpdate.takeIf { u.sent < u.size },
        )
        is FirmwareUpdate.Restarting -> {
            Text(stringResource(if (u.confirmed) R.string.update_restarting else R.string.update_unconfirmed))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        is FirmwareUpdate.Done -> Ended(stringResource(R.string.update_done, u.release), repository::dismissUpdate)
        is FirmwareUpdate.NotRunning -> Ended(
            stringResource(
                if (u.confirmed) R.string.update_not_started else R.string.update_not_finished,
                u.wanted, u.running.ifEmpty { stringResource(R.string.no_release) },
            ),
            repository::dismissUpdate,
        )
        is FirmwareUpdate.Refused -> Ended(
            when (u.code) {
                ErrorCode.NO_ROOM -> stringResource(R.string.update_no_room)
                ErrorCode.NOT_AN_IMAGE -> stringResource(R.string.update_not_an_image)
                else -> stringResource(R.string.update_refused, errorText(context, u.code))
            },
            repository::dismissUpdate,
        )
        is FirmwareUpdate.Failed -> Ended(
            stringResource(
                when (u.why) {
                    FirmwareFailure.DOWNLOAD -> R.string.update_download_failed
                    FirmwareFailure.CORRUPT -> R.string.update_corrupt
                    FirmwareFailure.UNSUPPORTED -> R.string.update_unsupported
                    FirmwareFailure.CHANGED -> R.string.update_changed
                },
            ),
            repository::dismissUpdate,
        )
    }

    confirming?.let { c ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.update_confirm_title, c.release)) },
            text = { Text(stringResource(R.string.update_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    c.image?.let { repository.startUpdate(c.release, it) }
                }) { Text(stringResource(R.string.update)) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Progress(text: String, fraction: Float, cancel: (() -> Unit)?) {
    Text(text)
    LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    cancel?.let { TextButton(onClick = it) { Text(stringResource(R.string.cancel)) } }
}

/** What an update came to, until the user has read it. */
@Composable
private fun Ended(text: String, dismiss: () -> Unit) {
    Text(text)
    TextButton(onClick = dismiss) { Text(stringResource(R.string.ok)) }
}

/** Asks before [node] is forgotten, saying what that deletes and what it does not. */
@Composable
fun ForgetDialog(node: ChosenNode, onDismiss: () -> Unit, forget: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.forget_node_title, node.name ?: stringResource(R.string.unnamed_device))) },
        text = { Text(stringResource(R.string.forget_node_explained)) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); forget() }) {
                Text(stringResource(R.string.forget), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun <T> Choice(label: String, current: String, options: List<Pair<String, T>>, enabled: Boolean = true, chosen: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(current)
        }
        TextButton(onClick = { open = true }, enabled = enabled) { Text(stringResource(R.string.set)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((text, value) in options) {
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; chosen(value) })
            }
        }
    }
}

@Composable
private fun NumberSetting(label: String, current: String, initial: String, done: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(current, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> if (v.all { it.isDigit() || it == '-' } && v.length <= 7) text = v },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { done(text) }) { Text(stringResource(R.string.set)) }
        }
    }
}
