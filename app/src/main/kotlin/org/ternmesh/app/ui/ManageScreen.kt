// Managing a node over the mesh (companion version 17, draft/managing.md): its own state, as its
// last sync over the mesh gave it, the settings an owner may change from afar, its Bluetooth lock,
// its owners, and a restart. Each request goes over the mesh, one at a time, and can take minutes.
package org.ternmesh.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ternmesh.app.R
import org.ternmesh.app.node.Managed
import org.ternmesh.app.node.NodeRepository
import org.ternmesh.app.node.NodeState
import org.ternmesh.companion.Address
import org.ternmesh.companion.Body
import org.ternmesh.companion.Companion
import org.ternmesh.companion.Conversations
import org.ternmesh.companion.ErrorCode
import org.ternmesh.companion.ManageStep
import org.ternmesh.companion.Outcome
import org.ternmesh.companion.ReceiverState
import org.ternmesh.companion.Remote
import org.ternmesh.companion.Setting

/**
 * The node at [address], managed over the mesh while this screen is open. It answers only if it
 * holds the app's node as one of its owners; to anyone else it says nothing at all.
 */
@Composable
fun ManageScreen(repository: NodeRepository, state: NodeState, address: Address, back: () -> Unit) {
    DisposableEffect(address) {
        repository.manage(address)
        onDispose { repository.stopManaging() }
    }
    val context = LocalContext.current
    val say = LocalSay.current
    val name = nodeName(context, state.records, address)
    val m = state.managed?.takeIf { it.address == address }
    val report: (Outcome) -> Unit = remember(name, say) {
        { o -> if (o !is Outcome.Answered && o != Outcome.Closed) say(manageOutcomeText(context, name, o)) }
    }
    // A change answered is asked about again, so what is shown is what the node now holds.
    val ask: (Body) -> Unit = { body ->
        repository.askManaged(body) { o ->
            report(o)
            if (o is Outcome.Answered) repository.syncManaged()
        }
    }
    var restarting by remember { mutableStateOf(false) }
    var locking by remember { mutableStateOf(false) }
    var owning by remember { mutableStateOf<Body.Contact?>(null) }
    var unowning by remember { mutableStateOf<Body.Contact?>(null) }
    val busy = m?.asking != null || m?.settleUntil != null

    NodeSubScreen(stringResource(R.string.manage_title, name), back) {
        Footnote(stringResource(R.string.manage_explained, name), Modifier.padding(top = 8.dp))

        // What is on its way now, what it waits for, and a way to stop waiting.
        if (m != null) ManageProgress(m, name, stop = repository::stopAsking, modifier = Modifier.padding(top = 8.dp))
        val failed = m?.failed
        if (m?.asking == null && failed != null) {
            Text(
                manageOutcomeText(context, name, failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
            )
            TextButton(onClick = repository::syncManaged, modifier = Modifier.padding(start = 12.dp)) { Text(stringResource(R.string.retry)) }
        }

        if (m == null) return@NodeSubScreen
        val r = m.records
        val self = r.self ?: return@NodeSubScreen

        SectionHeader(stringResource(R.string.manage_state))
        SettingsGroup {
            val rows = buildList {
                add(stringResource(R.string.role) to stringResource(if (self.role == 1) R.string.role_relay_short else R.string.role_leaf_short))
                add(stringResource(R.string.region) to self.region.ifEmpty { stringResource(R.string.region_none) })
                add(stringResource(R.string.power) to stringResource(R.string.power_value, self.power))
                add(
                    stringResource(R.string.battery) to
                        (r.power?.percent?.takeIf { it in 0..100 }?.let { "$it%" } ?: stringResource(R.string.unknown)),
                )
                add(stringResource(R.string.manage_hears) to "${r.neighbours.size}")
                self.locked?.let {
                    add(stringResource(R.string.manage_bluetooth) to stringResource(if (it == 1) R.string.bluetooth_locked else R.string.bluetooth_unlocked))
                }
                add(stringResource(R.string.node_clock) to timeText(context, self.time).ifEmpty { stringResource(R.string.clock_unset) })
                val firmware = m.firmware
                val version = m.version
                if (firmware != null && version != null) add(stringResource(R.string.manage_firmware) to context.getString(R.string.manage_firmware_value, firmware, version))
            }
            rows.forEachIndexed { i, (k, v) ->
                if (i > 0) GroupDivider()
                SettingsRow(k, value = v, chevron = false)
            }
        }
        TextButton(onClick = repository::syncManaged, enabled = !busy, modifier = Modifier.padding(start = 12.dp)) {
            Text(stringResource(R.string.manage_ask_again))
        }

        SectionHeader(stringResource(R.string.manage_settings))
        SettingsGroup {
            RoleChoice(stringResource(R.string.role_leaf_title), stringResource(R.string.role_leaf_short), stringResource(R.string.role_leaf_explained), self.role != 1) {
                if (!busy) ask(Body.Set(Setting.Role(0)))
            }
            GroupDivider()
            RoleChoice(stringResource(R.string.role_relay_title), stringResource(R.string.role_relay_short), stringResource(R.string.role_relay_explained), self.role == 1) {
                if (!busy) ask(Body.Set(Setting.Role(1)))
            }
        }
        Spacer(Modifier.height(8.dp))
        SettingsGroup {
            NumberSetting(stringResource(R.string.power), "${self.power}", unit = "dBm") { text ->
                text.toIntOrNull()?.let { if (!busy) ask(Body.Set(Setting.Power(it))) }
            }
            self.locked?.let { locked ->
                GroupDivider()
                SettingsRow(
                    stringResource(R.string.lock_bluetooth),
                    subtitle = stringResource(if (locked == 1) R.string.lock_bluetooth_on else R.string.lock_bluetooth_off),
                    trailing = {
                        Switch(checked = locked == 1, enabled = !busy, onCheckedChange = {
                            if (it) locking = true else ask(Body.Set(Setting.Lock(0)))
                        })
                    },
                )
            }
            r.fix?.takeIf { it.receiver != ReceiverState.NONE }?.let { fix ->
                GroupDivider()
                SettingsRow(
                    stringResource(R.string.receiver_on),
                    trailing = {
                        Switch(checked = fix.receiver != ReceiverState.OFF, enabled = !busy, onCheckedChange = {
                            ask(Body.Set(Setting.Receiver(if (it) 1 else 0)))
                        })
                    },
                )
            }
        }
        // Its presence card and its sleep, with the controls and limits the app's own node has.
        // Neither restarts the node, so the PING that confirms each goes at once.
        if (self.cards != null && self.cardName != null) {
            SectionHeader(stringResource(R.string.card_section))
            SettingsGroup { PresenceCard(self) { ask(Body.Set(it)) } }
        }
        // SLEEP comes from a node of version 15: below it, there is no section.
        r.sleep?.let { sleep ->
            SectionHeader(stringResource(R.string.sleep_section))
            SettingsGroup { SleepSettings(r, self, sleep) { ask(Body.Set(it)) } }
            Footnote(stringResource(if (self.role == 1) R.string.sleep_relay_explained else R.string.manage_sleep_explained))
        }

        // A change over the mesh is on trial until the node hears again: say where that stands.
        val trial = when {
            m.trialUntil != null && (m.asking != null || m.settleUntil != null) -> stringResource(R.string.manage_trial)
            m.trialUntil != null -> stringResource(R.string.manage_unconfirmed)
            m.confirmed -> stringResource(R.string.manage_confirmed)
            else -> null
        }
        trial?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 10.dp))
            if (m.trialUntil != null && !busy) {
                TextButton(onClick = { repository.askManaged(Body.Ping, report) }, modifier = Modifier.padding(start = 12.dp)) {
                    Text(stringResource(R.string.manage_confirm))
                }
            }
        }
        Footnote(stringResource(R.string.manage_settings_explained))

        SectionHeader(stringResource(R.string.manage_owners))
        SettingsGroup {
            val contacts = r.contacts.values.sortedBy { contactName(context, state, it).lowercase() }
            if (contacts.isEmpty()) {
                Text(stringResource(R.string.manage_no_contacts), modifier = Modifier.padding(16.dp))
            }
            contacts.forEachIndexed { i, c ->
                if (i > 0) GroupDivider()
                SettingsRow(
                    contactName(context, state, c),
                    trailing = {
                        Switch(checked = c.owner == 1, enabled = !busy && c.owner != null, onCheckedChange = { on ->
                            when {
                                on -> owning = c
                                c.address == state.records.self?.address -> unowning = c
                                else -> ask(Body.Owner(c.address, 0))
                            }
                        })
                    },
                )
            }
        }
        Footnote(stringResource(R.string.manage_owners_explained, Companion.OWNERS))

        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            SettingsRow(
                stringResource(R.string.manage_restart),
                icon = TernIcons.Power, tint = MaterialTheme.colorScheme.error,
                titleColor = MaterialTheme.colorScheme.error,
                chevron = false,
                enabled = !busy,
                onClick = { restarting = true },
            )
        }
    }

    if (locking) {
        LockDialog(name, onDismiss = { locking = false }) { ask(Body.Set(Setting.Lock(1))) }
    }
    owning?.let { c ->
        AlertDialog(
            onDismissRequest = { owning = null },
            title = { Text(stringResource(R.string.make_owner_remote_title, contactName(context, state, c), name)) },
            text = { Text(stringResource(R.string.make_owner_remote_explained)) },
            confirmButton = {
                TextButton(onClick = { owning = null; ask(Body.Owner(c.address, 1)) }) { Text(stringResource(R.string.make_owner)) }
            },
            dismissButton = { TextButton(onClick = { owning = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    unowning?.let { c ->
        AlertDialog(
            onDismissRequest = { unowning = null },
            title = { Text(stringResource(R.string.stop_owning_title, name)) },
            text = { Text(stringResource(R.string.stop_owning_explained)) },
            confirmButton = {
                TextButton(onClick = {
                    unowning = null
                    // No longer an owner, it would answer nothing more: back to the contacts.
                    repository.askManaged(Body.Owner(c.address, 0)) { o -> report(o); if (o is Outcome.Answered) back() }
                }) {
                    Text(stringResource(R.string.remove_owner), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { unowning = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (restarting) {
        AlertDialog(
            onDismissRequest = { restarting = false },
            title = { Text(stringResource(R.string.manage_restart_title, name)) },
            text = { Text(stringResource(R.string.manage_restart_explained)) },
            confirmButton = {
                TextButton(onClick = { restarting = false; repository.askManaged(Body.Restart, report) }) {
                    Text(stringResource(R.string.manage_restart), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { restarting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** One of the managed node's contacts, by this user's name for it where they have one, else by its user's, else its code. */
private fun contactName(context: Context, state: NodeState, c: Body.Contact): String = when {
    c.address == state.records.self?.address || c.address in state.records.contacts -> nodeName(context, state.records, c.address)
    else -> c.name.ifEmpty { Conversations.short(c.address.toString()) }
}

/** What is being asked of the managed node, in a few words. */
private fun askingText(context: Context, body: Body): String = context.getString(
    when (body) {
        is Body.Hello -> R.string.manage_asking_hello
        is Body.Sync -> R.string.manage_asking_sync
        is Body.Set -> R.string.manage_asking_set
        Body.Ping -> R.string.manage_asking_ping
        is Body.Owner -> R.string.manage_asking_owner
        Body.Restart -> R.string.manage_asking_restart
        else -> R.string.manage_asking_other
    },
)

/**
 * What a request over the mesh came to: `ERROR` 15 that the app's node is not an owner; no answer,
 * that the node is asleep or out of reach, or does not take this one as an owner and said nothing.
 */
internal fun manageOutcomeText(context: Context, name: String, outcome: Outcome): String = when {
    outcome == Outcome.NoAnswer -> context.getString(R.string.manage_no_answer, name.replaceFirstChar { it.uppercase() })
    outcome is Outcome.Refused && outcome.code == ErrorCode.NOT_AN_OWNER -> context.getString(R.string.manage_not_owner, name)
    else -> outcomeText(context, outcome)
}

/**
 * What is being asked of the managed node, and what it waits for now: making first contact,
 * finding a route, the air, the node's sleep, or its answer. With a way to stop waiting.
 */
@Composable
internal fun ManageProgress(m: Managed, name: String, stop: (() -> Unit)?, modifier: Modifier = Modifier) {
    val asking = m.asking
    if (asking == null && m.settleUntil == null) return
    val context = LocalContext.current
    SettingsGroup(modifier) {
        Row(
            Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = if (asking != null && stop != null) 4.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Column {
                if (asking != null) {
                    val step = ManageStep.of(m.carried, m.reason, m.wait)
                    Text(manageStepText(context, step, name))
                    val what = listOfNotNull(
                        askingText(context, asking),
                        m.attempt.takeIf { it > 0 }?.let { context.getString(R.string.manage_try, it, Remote.TRIES) },
                    ).joinToString(" · ")
                    Text(what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (ManageStep.waitsForWake(step)) {
                        manageAsleepText(context, name, m.wake)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    Text(stringResource(R.string.manage_settling))
                }
            }
        }
        if (asking != null && stop != null) {
            TextButton(onClick = stop, modifier = Modifier.padding(start = 52.dp, bottom = 4.dp)) { Text(stringResource(R.string.manage_stop)) }
        }
    }
}
