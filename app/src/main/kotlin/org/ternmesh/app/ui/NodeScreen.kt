// The node itself, as a dashboard: its name, link, battery and airtime at the head, then a row for
// each thing about it, each opening a screen of its own: its address to give others, the nodes it
// hears, its radio, what it shows of itself, its pairing PIN, and its firmware; and, last,
// switching to another node, turning it off, or forgetting it.
package org.ternmesh.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.selection.selectable
import org.ternmesh.app.R
import org.ternmesh.app.node.ChosenNode
import org.ternmesh.app.node.FirmwareFailure
import org.ternmesh.app.node.FirmwareUpdate
import org.ternmesh.app.node.NodeRepository
import org.ternmesh.app.node.NodeState
import org.ternmesh.app.node.Phase
import org.ternmesh.app.node.percent
import org.ternmesh.app.node.stayingOff
import org.ternmesh.companion.Body
import org.ternmesh.companion.Companion
import org.ternmesh.companion.Conversations
import org.ternmesh.companion.ErrorCode
import org.ternmesh.companion.Offer
import org.ternmesh.companion.Pairing
import org.ternmesh.companion.ReceiverState
import org.ternmesh.companion.Setting
import org.ternmesh.companion.Sharing

/** The regions the specification's profiles define, each with where it is for. */
internal val REGIONS = listOf(
    "AU915" to R.string.region_au915,
    "CL915" to R.string.region_cl915,
    "EU868" to R.string.region_eu868,
    "KZ863" to R.string.region_kz863,
    "MY919" to R.string.region_my919,
    "NZ915" to R.string.region_nz915,
    "RU868" to R.string.region_ru868,
    "TH920" to R.string.region_th920,
    "US915" to R.string.region_us915,
)

/** The screens the node's dashboard opens. */
enum class NodePage(val route: String) {
    ADDRESS("node/address"),
    NEIGHBOURS("node/neighbours"),
    RADIO("node/radio"),
    PRIVACY("node/privacy"),
    PIN("node/pin"),
    ABOUT("node/about"),
}

/** Each kind of setting's colour, so that it is found by colour as well as by name. */
private object RowTint {
    val nearby = Color(0xFF0F766E)
    val radio = Color(0xFF0B4F6C)
    val privacy = Color(0xFF7C3AED)
    val pin = Color(0xFFB45309)
    val about = Color(0xFF475569)
    val link = Color(0xFF1D4ED8)
    val switch = Color(0xFF64748B)
}

@Composable
fun NodeScreen(repository: NodeRepository, state: NodeState, go: (NodePage) -> Unit) {
    val r = state.records
    val self = r.self
    val extra = LocalTernColors.current
    var forgetting by remember { mutableStateOf<ChosenNode?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var turningOff by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        TabTitle(stringResource(R.string.tab_node), state, toNode = null)
        NodeHero(repository, state, go, rename = { renaming = true })

        SettingsGroup(Modifier.padding(top = 4.dp)) {
            SettingsRow(
                stringResource(R.string.node_nearby_nodes),
                icon = TernIcons.Wave, tint = RowTint.nearby,
                value = r.neighbours.size.let { if (it == 0) stringResource(R.string.node_heard_none) else pluralStringResource(R.plurals.node_heard_count, it, it) },
                onClick = { go(NodePage.NEIGHBOURS) },
            )
            self?.let { s ->
                GroupDivider()
                SettingsRow(
                    stringResource(R.string.node_radio),
                    icon = TernIcons.Radio, tint = RowTint.radio,
                    value = listOf(
                        s.region.ifEmpty { stringResource(R.string.region_none) },
                        stringResource(if (s.role == 1) R.string.role_relay_short else R.string.role_leaf_short),
                    ).joinToString(" · "),
                    valueColor = if (s.region.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { go(NodePage.RADIO) },
                )
            }
            if (hasPrivacy(state)) {
                GroupDivider()
                SettingsRow(
                    stringResource(R.string.node_privacy),
                    icon = TernIcons.Eye, tint = RowTint.privacy,
                    value = self?.cards?.takeIf { hasCards(state) }?.let {
                        stringResource(if (it == 1) R.string.node_privacy_card_on else R.string.node_privacy_card_off)
                    },
                    onClick = { go(NodePage.PRIVACY) },
                )
            }
            self?.let { s ->
                GroupDivider()
                SettingsRow(
                    stringResource(R.string.passkey),
                    icon = TernIcons.Lock, tint = RowTint.pin,
                    value = passkeyShort(s.pairing),
                    valueColor = if (s.pairing == Pairing.DEFAULT) extra.warn else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { go(NodePage.PIN) },
                )
            }
        }

        if (state.board != null || state.version != null) {
            Spacer(Modifier.height(14.dp))
            SettingsGroup {
                val ready = (state.update as? FirmwareUpdate.Checked)?.let { it.image != null && it.offer != Offer.CURRENT } == true
                val busy = state.update is FirmwareUpdate.Downloading || state.update is FirmwareUpdate.Sending
                SettingsRow(
                    stringResource(R.string.node_about),
                    icon = TernIcons.Chip, tint = RowTint.about,
                    value = when {
                        busy -> stringResource(R.string.node_updating)
                        ready -> stringResource(R.string.node_update_ready)
                        else -> state.release?.takeIf { it.isNotEmpty() }
                    },
                    valueColor = if (ready || busy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { go(NodePage.ABOUT) },
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            SettingsRow(
                stringResource(R.string.change_node),
                icon = TernIcons.Swap, tint = RowTint.switch,
                subtitle = stringResource(R.string.change_node_explained),
                chevron = false,
                onClick = repository::leave,
            )
            // OFF is version 9's: a node of version 8 or earlier is not asked, and has no row for it.
            if (canTurnOff(state)) {
                GroupDivider()
                SettingsRow(
                    stringResource(R.string.turn_off),
                    icon = TernIcons.Power, tint = MaterialTheme.colorScheme.error,
                    titleColor = MaterialTheme.colorScheme.error,
                    chevron = false,
                    // Not while an image is on its way: the node would stop taking it.
                    enabled = state.phase == Phase.READY && state.update !is FirmwareUpdate.Downloading &&
                        state.update !is FirmwareUpdate.Sending,
                    onClick = { turningOff = true },
                )
            }
            state.node?.let { node ->
                GroupDivider()
                SettingsRow(
                    stringResource(R.string.forget_node),
                    icon = TernIcons.Trash, tint = MaterialTheme.colorScheme.error,
                    titleColor = MaterialTheme.colorScheme.error,
                    chevron = false,
                    onClick = { forgetting = node },
                )
            }
        }
    }
    state.node?.takeIf { renaming }?.let { node ->
        NameDialog(
            stringResource(R.string.rename_node),
            node.shown.orEmpty(),
            Companion.NAME_MAX,
            stringResource(R.string.save),
            onDismiss = { renaming = false },
            explained = stringResource(R.string.rename_node_explained),
        ) { label ->
            renaming = false
            repository.rename(node.address, label)
        }
    }
    forgetting?.let { node -> ForgetDialog(node, onDismiss = { forgetting = null }) { repository.forget(node.address) } }
    state.node?.takeIf { turningOff }?.let { node ->
        val report = rememberReport()
        TurnOffDialog(node, onDismiss = { turningOff = false }) { repository.turnOff(report) }
    }
}

/** Whether the node has anything of the privacy screen's: cards from version 6, a receiver from version 8. */
private fun hasPrivacy(state: NodeState): Boolean {
    val self = state.records.self
    val cards = hasCards(state) && self?.cards != null
    val receiver = state.records.fix?.takeIf { hasFix(state) }?.receiver?.let { it != ReceiverState.NONE } == true
    return cards || receiver
}

/**
 * The head of the dashboard: the node's name, what the link is doing, and its battery and airtime
 * as meters, with the two things done most often from here: giving its address, and connecting.
 */
@Composable
private fun NodeHero(repository: NodeRepository, state: NodeState, go: (NodePage) -> Unit, rename: () -> Unit) {
    val context = LocalContext.current
    val extra = LocalTernColors.current
    val r = state.records
    val ready = state.phase == Phase.READY
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(extra.heroTop, extra.heroBottom))),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.node?.shown ?: stringResource(R.string.unnamed_device),
                    style = MaterialTheme.typography.headlineSmall,
                    color = extra.onHero,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (state.node != null) {
                    IconButton(onClick = rename, modifier = Modifier.size(36.dp)) {
                        Icon(TernIcons.Edit, stringResource(R.string.rename), tint = extra.onHero.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (ready) Color(0xFF9FF0C6) else Color(0xFFFFD08A)))
                Spacer(Modifier.width(7.dp))
                Text(
                    listOfNotNull(
                        phaseText(context, state),
                        state.board?.takeIf { it.isNotEmpty() },
                        r.self?.let { stringResource(if (it.role == 1) R.string.role_relay_short else R.string.role_leaf_short) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.onHero.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (r.power != null || r.airtime != null) {
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val p = r.power
                    Gauge(
                        stringResource(R.string.battery),
                        value = p?.percent?.takeIf { it in 0..100 }?.let { "$it%" } ?: stringResource(R.string.unknown),
                        fraction = p?.percent?.takeIf { it in 0..100 }?.let { it / 100f },
                        hint = when {
                            p == null -> null
                            p.flags and 1 != 0 -> stringResource(R.string.charging)
                            p.flags and 2 != 0 -> stringResource(R.string.external_power)
                            p.millivolts > 0 -> stringResource(R.string.battery_volts, p.millivolts / 1000.0)
                            else -> null
                        },
                        modifier = Modifier.weight(1f),
                    )
                    val a = r.airtime
                    Gauge(
                        stringResource(R.string.airtime_label),
                        value = when {
                            a == null -> stringResource(R.string.unknown)
                            a.period == 0L -> stringResource(R.string.airtime_no_limit_short)
                            else -> "${(100 * a.used / a.allowed.coerceAtLeast(1)).coerceIn(0, 100)}%"
                        },
                        fraction = a?.takeIf { it.period != 0L }?.let { (it.used.toFloat() / it.allowed.coerceAtLeast(1)).coerceIn(0f, 1f) },
                        hint = when {
                            a == null -> null
                            a.wait > 0 -> stringResource(R.string.airtime_wait, a.wait / 1000.0)
                            a.period == 0L -> stringResource(R.string.airtime_unlimited)
                            a.used * 10 >= a.allowed * 9 -> stringResource(R.string.airtime_nearly_out)
                            a.used * 2 >= a.allowed -> stringResource(R.string.airtime_half)
                            else -> stringResource(R.string.airtime_plenty)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (r.self != null) HeroButton(TernIcons.Qr, stringResource(R.string.node_my_address), Modifier.weight(1f)) { go(NodePage.ADDRESS) }
                if (state.phase.stayingOff) {
                    HeroButton(TernIcons.Link, stringResource(R.string.connect), Modifier.weight(1f), onClick = repository::connect)
                } else {
                    HeroButton(TernIcons.Unlink, stringResource(R.string.disconnect), Modifier.weight(1f), onClick = repository::disconnect)
                }
            }
        }
    }
}

@Composable
private fun Gauge(label: String, value: String, fraction: Float?, hint: String?, modifier: Modifier = Modifier) {
    val on = LocalTernColors.current.onHero
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(on.copy(alpha = 0.13f)).padding(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = on.copy(alpha = 0.85f))
        Text(value, style = MaterialTheme.typography.headlineSmall, color = on, maxLines = 1)
        Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(on.copy(alpha = 0.25f))) {
            fraction?.let { Box(Modifier.fillMaxWidth(it).height(6.dp).clip(RoundedCornerShape(3.dp)).background(on)) }
        }
        hint?.let {
            Text(it, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal), color = on.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 6.dp), maxLines = 2)
        }
    }
}

@Composable
private fun HeroButton(icon: ImageVector, text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val on = LocalTernColors.current.onHero
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = on.copy(alpha = 0.18f), contentColor = on, modifier = modifier) {
        Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** A screen the dashboard opens: its bar, and its content scrolling under it. */
@Composable
private fun NodeSubScreen(title: String, back: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SubScreenBar(title, back)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp), content = content)
    }
}

/** The node's address: its QR code to scan, the short code to read out, and the link to send. */
@Composable
fun AddressScreen(state: NodeState, back: () -> Unit) {
    val context = LocalContext.current
    val say = LocalSay.current
    NodeSubScreen(stringResource(R.string.node_my_address), back) {
        val s = state.records.self ?: return@NodeSubScreen
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White).padding(12.dp)) {
                QrCode(s.address, Modifier.size(220.dp))
            }
        }
        Footnote(stringResource(R.string.qr_explained), Modifier.padding(top = 8.dp))
        SectionHeader(stringResource(R.string.short_code))
        Text(
            Sharing.shortCode(s.address),
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Footnote(stringResource(R.string.short_code_explained))
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { shareLink(context, s.address) }, modifier = Modifier.weight(1f)) {
                Icon(TernIcons.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.share_link))
            }
            FilledTonalButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.node_address), Sharing.text(s.address)))
                say(context.getString(R.string.copied))
            }, modifier = Modifier.weight(1f)) {
                Icon(TernIcons.Copy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.copy))
            }
        }
        SectionHeader(stringResource(R.string.node_address_full))
        SelectionContainer {
            Text(
                Sharing.grouped(s.address),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

/**
 * The nodes the node hears, by a contact's name or by what a card held from it claims, each with
 * its role, when it was last heard, and how well, in bars and in dB.
 */
@Composable
fun NeighboursScreen(repository: NodeRepository, state: NodeState, back: () -> Unit) {
    val r = state.records
    var choosing by remember { mutableStateOf<Body.Card?>(null) }
    NodeSubScreen(stringResource(R.string.node_nearby_nodes), back) {
        val neighbours = r.neighbours.values.sortedBy { it.heard }
        if (neighbours.isEmpty()) EmptyState(stringResource(R.string.nearby_empty_title), stringResource(R.string.neighbours_none))
        var unnamed = false
        for (n in neighbours) {
            val name = Conversations.nameOf(r, n.routingId)
            // Not a contact's: named by the card the node holds from it, as what it claims, if any.
            val card = if (name == null) Conversations.cardOf(r, n.routingId) else null
            if (name == null && card == null && r.contacts.values.none { it.address.routingId == n.routingId }) unnamed = true
            val line = listOf(
                stringResource(if (n.role == 1) R.string.role_relay_short else R.string.role_leaf_short),
                stringResource(R.string.heard_ago, spanText(n.heard.toLong())),
                stringResource(R.string.snr_db, n.snrQuarterDb / 4.0),
            ).joinToString(" · ")
            PersonRow(
                avatar = {
                    when {
                        name != null -> Avatar(name, size = 40.dp, key = r.contacts.values.firstOrNull { it.address.routingId == n.routingId }?.address?.toString() ?: name)
                        card != null -> Avatar(card.name, size = 40.dp, key = card.address.toString(), unknown = card.name.isEmpty())
                        else -> Avatar("", size = 40.dp, unknown = true)
                    }
                },
                title = {
                    when {
                        name != null -> Text(name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                        card != null -> CardName(card)
                        else -> Text(routingText(n.routingId), style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
                    }
                },
                subtitle = line,
                signal = n.snrQuarterDb,
                // Tapping a card asks about making a contact, as tapping it among the people near does.
                onClick = card?.let { c -> { choosing = c } },
            )
        }
        // Neither a contact's, even one without a name, nor a card's: why it is only a number.
        if (unnamed) Footnote(stringResource(R.string.neighbour_no_card), Modifier.padding(top = 8.dp))
        Footnote(stringResource(R.string.signal_explained), Modifier.padding(top = 4.dp))
    }
    choosing?.let { card -> MakeContactFromCard(repository, card) { choosing = null } }
}

/** Where the node is, which it transmits by; whether it passes on others' frames; and how loud. */
@Composable
fun RadioScreen(repository: NodeRepository, state: NodeState, back: () -> Unit) {
    val report = rememberReport()
    NodeSubScreen(stringResource(R.string.node_radio), back) {
        val s = state.records.self ?: return@NodeSubScreen
        SectionHeader(stringResource(R.string.region))
        SettingsGroup {
            val description = REGIONS.firstOrNull { it.first == s.region }?.second
            var open by remember { mutableStateOf(false) }
            // An image is for one region, and a new region restarts the node: not while one is on its way.
            val enabled = state.update !is FirmwareUpdate.Downloading && state.update !is FirmwareUpdate.Sending
            Box {
                SettingsRow(
                    s.region.ifEmpty { stringResource(R.string.region_none) },
                    subtitle = description?.let { stringResource(it) } ?: stringResource(R.string.region_choose),
                    titleColor = if (s.region.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    enabled = enabled,
                    onClick = { open = true },
                )
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for ((name, where) in REGIONS) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(name, fontWeight = FontWeight.SemiBold)
                                    Text(stringResource(where), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = { open = false; repository.submit(Body.Set(Setting.Region(name)), report) },
                        )
                    }
                }
            }
        }
        Footnote(stringResource(R.string.region_explained))

        SectionHeader(stringResource(R.string.role))
        SettingsGroup {
            RoleChoice(stringResource(R.string.role_leaf_title), stringResource(R.string.role_leaf_short), stringResource(R.string.role_leaf_explained), s.role != 1) {
                repository.submit(Body.Set(Setting.Role(0)), report)
            }
            GroupDivider()
            RoleChoice(stringResource(R.string.role_relay_title), stringResource(R.string.role_relay_short), stringResource(R.string.role_relay_explained), s.role == 1) {
                repository.submit(Body.Set(Setting.Role(1)), report)
            }
        }

        SectionHeader(stringResource(R.string.power))
        SettingsGroup {
            NumberSetting(
                if (s.most == 1) stringResource(R.string.power_at_most) else null,
                "${s.power}",
                unit = "dBm",
            ) { text ->
                text.toIntOrNull()?.let { repository.submit(Body.Set(Setting.Power(it)), report) }
            }
            // The most the node may transmit at, which it works out for its board, its antenna and
            // its region, and works out again when the region changes.
            if (canSendAtTheMost(state) && s.most != 1) {
                TextButton(
                    onClick = { repository.submit(Body.Set(Setting.Power(Companion.POWER_MOST)), report) },
                    modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
                ) { Text(stringResource(R.string.power_most)) }
            }
        }
        Footnote(stringResource(R.string.settings_restart))
    }
}

@Composable
private fun RoleChoice(title: String, tag: String, explained: String, selected: Boolean, choose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = { if (!selected) choose() })
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Column(Modifier.weight(1f).padding(top = 10.dp, end = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                Tag(tag)
            }
            Text(explained, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** What others can learn of the node: its presence card, and where it is, from its own receiver. */
@Composable
fun PrivacyScreen(repository: NodeRepository, state: NodeState, back: () -> Unit) {
    NodeSubScreen(stringResource(R.string.node_privacy), back) {
        // SELF says cards from a node of version 6; below it there are none to turn on.
        state.records.self?.takeIf { hasCards(state) && it.cards != null }?.let { s ->
            SectionHeader(stringResource(R.string.card_section))
            SettingsGroup { PresenceCard(repository, s) }
            Footnote(stringResource(R.string.card_on_explained))
        }
        // FIX comes from a node of version 8, and says whether it has a receiver to turn on or off.
        state.records.fix?.takeIf { hasFix(state) && it.receiver != ReceiverState.NONE }?.let { f ->
            SectionHeader(stringResource(R.string.receiver_section))
            SettingsGroup { ReceiverSwitch(repository, f) }
            Footnote(stringResource(R.string.receiver_explained))
        }
    }
}

/** What the node pairs with, and choosing another. */
@Composable
fun PinScreen(repository: NodeRepository, state: NodeState, back: () -> Unit) {
    val report = rememberReport()
    val extra = LocalTernColors.current
    NodeSubScreen(stringResource(R.string.passkey), back) {
        val s = state.records.self ?: return@NodeSubScreen
        // What the node pairs with is in SELF from a node of version 11; below it, only how to set one.
        val pairing = passkeyText(s.pairing)
        if (pairing != null) {
            SectionHeader(stringResource(R.string.passkey_now))
            SettingsGroup {
                SettingsRow(pairing, valueColor = extra.warn, chevron = false,
                    titleColor = if (s.pairing == Pairing.DEFAULT) extra.warn else MaterialTheme.colorScheme.onSurface)
            }
            if (s.pairing == Pairing.DEFAULT) Footnote(stringResource(R.string.passkey_choose_explained))
        }
        SectionHeader(stringResource(R.string.passkey_new))
        SettingsGroup {
            NumberSetting(stringResource(R.string.passkey_hint), "", password = true) { text ->
                val passkey = if (text.isEmpty()) Setting.Passkey.DEFAULT else text.toLongOrNull()?.takeIf { it in 0..999_999 }
                passkey?.let { repository.submit(Body.Set(Setting.Passkey(it)), report) }
            }
            if (s.pairing == Pairing.SET) {
                TextButton(
                    onClick = { repository.submit(Body.Set(Setting.Passkey(Setting.Passkey.DEFAULT)), report) },
                    modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
                ) { Text(stringResource(R.string.passkey_use_default)) }
            }
        }
        Footnote(stringResource(R.string.passkey_takes_effect))
    }
}

/** The node's firmware and updating it, and the little else there is to know about it. */
@Composable
fun AboutScreen(repository: NodeRepository, state: NodeState, back: () -> Unit) {
    val context = LocalContext.current
    NodeSubScreen(stringResource(R.string.node_about), back) {
        if (state.board != null || state.version != null) {
            SectionHeader(stringResource(R.string.firmware))
            SettingsGroup {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { FirmwareUpdates(repository, state) }
            }
        }
        SectionHeader(stringResource(R.string.node_details))
        SettingsGroup {
            val rows = buildList {
                state.release?.takeIf { it.isNotEmpty() }?.let { add(stringResource(R.string.about_release) to it) }
                state.board?.takeIf { it.isNotEmpty() }?.let { add(stringResource(R.string.about_board) to it) }
                state.version?.let { add(stringResource(R.string.about_protocol) to "$it") }
                state.node?.takeIf { it.label != null }?.name?.let { add(stringResource(R.string.about_bluetooth) to it) }
                state.records.self?.let { s -> add(stringResource(R.string.node_clock) to timeText(context, s.time).ifEmpty { stringResource(R.string.clock_unset) }) }
            }
            rows.forEachIndexed { i, (k, v) ->
                if (i > 0) GroupDivider()
                SettingsRow(k, value = v, chevron = false)
            }
        }
    }
}

/** What the node pairs with, in a word or two, for the dashboard's row. */
@Composable
private fun passkeyShort(pairing: Int?): String? = when (pairing) {
    Pairing.SCREEN -> stringResource(R.string.passkey_screen_short)
    Pairing.DEFAULT -> stringResource(R.string.passkey_default_short)
    Pairing.SET -> stringResource(R.string.passkey_set)
    else -> null
}

/** What the node pairs with, as `SELF`'s `pairing` says; null from a node of version 10 or earlier. */
@Composable
private fun passkeyText(pairing: Int?): String? = when (pairing) {
    Pairing.SCREEN -> stringResource(R.string.passkey_screen)
    Pairing.DEFAULT -> stringResource(R.string.passkey_default)
    Pairing.SET -> stringResource(R.string.passkey_set)
    else -> null
}

/**
 * While a node without a screen still pairs with the default passkey, anyone in range who knows
 * 123456 can pair with it, as the specification warns. Once each connection this says so, above
 * every tab, and offers to choose another; dismissed, it stays away until the node connects again.
 */
@Composable
internal fun DefaultPasskeyBanner(repository: NodeRepository, state: NodeState) {
    var dismissed by remember(state.node?.address) { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    LaunchedEffect(state.phase) { if (state.phase != Phase.READY) dismissed = false }
    if (state.phase != Phase.READY || state.records.self?.pairing != Pairing.DEFAULT) return
    if (!dismissed) {
        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.passkey_choose_banner), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { dismissed = true }) { Text(stringResource(R.string.passkey_choose_later)) }
                TextButton(onClick = { choosing = true }) { Text(stringResource(R.string.passkey_choose)) }
            }
        }
    }
    if (choosing) {
        val report = rememberReport()
        PasskeyDialog(onDismiss = { choosing = false }) { passkey ->
            choosing = false
            repository.submit(Body.Set(Setting.Passkey(passkey)), report)
        }
    }
}

/** Asks for six digits, and says why: anyone who knows the default can pair until they are set. */
@Composable
private fun PasskeyDialog(onDismiss: () -> Unit, set: (Long) -> Unit) {
    var text by remember { mutableStateOf("") }
    // The default again would leave the node as open as it was.
    val isDefault = text == "%06d".format(Setting.Passkey.SCREENLESS_DEFAULT)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.passkey_choose_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.passkey_choose_explained))
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> if (v.length <= 6 && v.all { it.isDigit() }) text = v },
                    label = { Text(stringResource(R.string.passkey_choose_field)) },
                    singleLine = true,
                    isError = isDefault,
                    supportingText = { if (isDefault) Text(stringResource(R.string.passkey_choose_not_default)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { set(text.toLong()) }, enabled = text.length == 6 && !isDefault) {
                Text(stringResource(R.string.passkey_choose_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Whether the node can be asked for the most it may transmit at: `SET` 3's -128 is version 10's. */
fun canSendAtTheMost(state: NodeState) = (state.version ?: 0) >= 10

/** Whether the node can be turned off from the app: `OFF` is version 9's. */
fun canTurnOff(state: NodeState) = (state.version ?: 0) >= 9

/**
 * Asks before turning the node off, and says first what the specification has a client say: only
 * someone at the node can turn it on again.
 */
@Composable
private fun TurnOffDialog(node: ChosenNode, onDismiss: () -> Unit, turnOff: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.turn_off_title, node.shown ?: stringResource(R.string.unnamed_device))) },
        text = { Text(stringResource(R.string.turn_off_explained)) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); turnOff() }) {
                Text(stringResource(R.string.turn_off), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Whether the node sends cards, and the name they carry. Each is sent only when the user changes it
 * here: being seen is theirs to choose, so the switch says plainly what turning it on puts on the air.
 */
@Composable
private fun PresenceCard(repository: NodeRepository, self: Body.Self) {
    val report = rememberReport()
    val on = self.cards == 1
    val current = self.cardName.orEmpty()
    var name by remember(current) { mutableStateOf(current) }
    val bytes = utf8Length(name.trim())
    val tooLong = bytes > Companion.CARD_NAME_MAX
    SettingsRow(
        stringResource(R.string.card_on),
        subtitle = stringResource(if (on) R.string.card_on_yes else R.string.card_on_no),
        trailing = { Switch(checked = on, onCheckedChange = { repository.submit(Body.Set(Setting.Cards(if (it) 1 else 0)), report) }) },
    )
    GroupDivider()
    Text(
        stringResource(R.string.card_name_explained),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
    )
    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.card_name)) },
            placeholder = { Text(stringResource(R.string.card_no_name)) },
            singleLine = true,
            isError = tooLong,
            supportingText = {
                if (tooLong) {
                    Text(stringResource(R.string.card_name_too_long, Companion.CARD_NAME_MAX))
                } else {
                    Text(pluralStringResource(R.plurals.bytes_left, Companion.CARD_NAME_MAX - bytes, Companion.CARD_NAME_MAX - bytes))
                }
            },
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { repository.submit(Body.Set(Setting.CardName(name.trim())), report) },
            enabled = !tooLong && name.trim() != current,
        ) { Text(stringResource(R.string.set)) }
    }
}

/**
 * The node's satellite receiver, on or off, and what it is doing. Off saves the node's battery; its
 * last fix is kept, and a phone sharing its position gives it a newer one.
 */
@Composable
private fun ReceiverSwitch(repository: NodeRepository, fix: Body.Fix) {
    val context = LocalContext.current
    val report = rememberReport()
    val on = fix.receiver != ReceiverState.OFF
    SettingsRow(
        stringResource(R.string.receiver_on),
        subtitle = receiverText(context, fix.receiver, fix.source, fix.satellites),
        trailing = { Switch(checked = on, onCheckedChange = { repository.submit(Body.Set(Setting.Receiver(if (it) 1 else 0)), report) }) },
    )
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
        title = { Text(stringResource(R.string.forget_node_title, node.shown ?: stringResource(R.string.unnamed_device))) },
        text = { Text(stringResource(R.string.forget_node_explained)) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); forget() }) {
                Text(stringResource(R.string.forget), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A number typed in and set: [label] says what it is now, or what to type. */
@Composable
private fun NumberSetting(label: String?, initial: String, unit: String? = null, password: Boolean = false, done: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { v -> if (v.all { it.isDigit() || it == '-' } && v.length <= 7) text = v },
            singleLine = true,
            label = label?.let { { Text(it) } },
            suffix = unit?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.NumberPassword else KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { done(text) }) { Text(stringResource(R.string.set)) }
    }
}
