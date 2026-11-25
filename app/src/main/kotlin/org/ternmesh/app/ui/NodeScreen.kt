// The node itself, as a dashboard: its name, link, battery and airtime at the head, each meter
// opening its own screen of how it went over time, then a row for
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
import org.ternmesh.app.node.BootloaderFailure
import org.ternmesh.app.node.BootloaderStep
import org.ternmesh.app.node.ChosenNode
import org.ternmesh.app.node.FirmwareFailure
import org.ternmesh.app.node.FirmwareUpdate
import org.ternmesh.app.node.NodeRepository
import org.ternmesh.app.node.NodeState
import org.ternmesh.app.node.Phase
import org.ternmesh.app.node.Wiped
import org.ternmesh.app.node.percent
import org.ternmesh.app.node.stayingOff
import org.ternmesh.companion.Body
import org.ternmesh.companion.Companion
import org.ternmesh.companion.Conversations
import org.ternmesh.companion.ErrorCode
import org.ternmesh.companion.Install
import org.ternmesh.companion.Offer
import org.ternmesh.companion.Outcome
import org.ternmesh.companion.Pairing
import org.ternmesh.companion.ReceiverState
import org.ternmesh.companion.Records
import org.ternmesh.companion.Setting
import org.ternmesh.companion.Signed
import org.ternmesh.companion.Sleeping
import org.ternmesh.companion.install
import org.ternmesh.companion.UpdateBy
import org.ternmesh.companion.updateBy
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
    RANGE("range"),
    BATTERY("node/battery"),
    AIRTIME("node/airtime"),
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
    var wiping by remember { mutableStateOf(false) }

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
            // Numbered messages to a contact while the user moves, each logged with where the phone was.
            if (r.contacts.isNotEmpty()) {
                GroupDivider()
                SettingsRow(
                    stringResource(R.string.range_test),
                    icon = TernIcons.Pin, tint = RowTint.nearby,
                    onClick = { go(NodePage.RANGE) },
                )
            }
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
            // SELF says whether Bluetooth is locked from a node of version 17.
            if (self?.locked == 1) {
                GroupDivider()
                SettingsRow(
                    stringResource(R.string.bluetooth_locked_title),
                    icon = TernIcons.Lock, tint = extra.warn,
                    subtitle = stringResource(R.string.bluetooth_locked_explained),
                    chevron = false,
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
                val busy = state.update is FirmwareUpdate.Downloading || state.update is FirmwareUpdate.Sending ||
                    state.update is FirmwareUpdate.Bootloader || state.update is FirmwareUpdate.BootloaderStopped
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
                        state.update !is FirmwareUpdate.Sending && state.update !is FirmwareUpdate.Bootloader,
                    onClick = { turningOff = true },
                )
            }
            // WIPE is version 13's: a node of version 12 or earlier is not asked, and has no row for it.
            if (canWipe(state)) {
                GroupDivider()
                SettingsRow(
                    stringResource(R.string.wipe_node),
                    icon = TernIcons.Trash, tint = MaterialTheme.colorScheme.error,
                    titleColor = MaterialTheme.colorScheme.error,
                    chevron = false,
                    enabled = state.phase == Phase.READY && state.update !is FirmwareUpdate.Downloading &&
                        state.update !is FirmwareUpdate.Sending && state.update !is FirmwareUpdate.Bootloader,
                    onClick = { wiping = true },
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
    state.node?.takeIf { wiping }?.let { node ->
        val report = rememberReport()
        WipeDialog(node, onDismiss = { wiping = false }) { repository.wipe(report) }
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
                        onClick = { go(NodePage.BATTERY) },
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
                        onClick = { go(NodePage.AIRTIME) },
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

/** A meter on the dashboard's head: what it measures, its value, a bar, and a hint; it opens a screen of its own. */
@Composable
private fun Gauge(label: String, value: String, fraction: Float?, hint: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val on = LocalTernColors.current.onHero
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(on.copy(alpha = 0.13f))
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.reading_open), onClick = onClick)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = on.copy(alpha = 0.85f), modifier = Modifier.weight(1f))
            Icon(TernIcons.Chevron, contentDescription = null, tint = on.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
        }
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
internal fun NodeSubScreen(title: String, back: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SubScreenBar(title, back)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp), content = content)
    }
}

/**
 * The node's address, laid out as the dashboard is: a head on the dashboard's colours with its QR
 * code, its short code and the two ways to send it; then how others use each, and the full address.
 */
@Composable
fun AddressScreen(state: NodeState, back: () -> Unit) {
    val context = LocalContext.current
    val say = LocalSay.current
    val extra = LocalTernColors.current
    NodeSubScreen(stringResource(R.string.node_my_address), back) {
        val s = state.records.self ?: return@NodeSubScreen
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(extra.heroTop, extra.heroBottom)))
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                state.node?.shown ?: stringResource(R.string.unnamed_device),
                style = MaterialTheme.typography.titleMedium,
                color = extra.onHero,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The code stays black on white whatever the theme, or a scanner may not read it.
            Box(Modifier.padding(top = 14.dp).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(10.dp)) {
                QrCode(s.address, Modifier.size(200.dp))
            }
            Text(
                stringResource(R.string.short_code).uppercase(),
                style = SectionLabel,
                color = extra.onHero.copy(alpha = 0.75f),
                modifier = Modifier.padding(top = 16.dp),
            )
            SelectionContainer {
                Text(
                    Sharing.shortCode(s.address),
                    style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 2.sp),
                    fontFamily = FontFamily.Monospace,
                    color = extra.onHero,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroButton(TernIcons.Share, stringResource(R.string.share_link), Modifier.weight(1f)) { shareLink(context, s.address) }
                HeroButton(TernIcons.Copy, stringResource(R.string.copy), Modifier.weight(1f)) {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.node_address), Sharing.text(s.address)))
                    say(context.getString(R.string.copied))
                }
            }
        }

        SectionHeader(stringResource(R.string.address_how))
        SettingsGroup {
            SettingsRow(
                stringResource(R.string.address_scan),
                icon = TernIcons.Qr, tint = RowTint.link,
                subtitle = stringResource(R.string.qr_explained),
            )
            GroupDivider()
            SettingsRow(
                stringResource(R.string.address_read_out),
                icon = TernIcons.Chats, tint = RowTint.nearby,
                subtitle = stringResource(R.string.short_code_explained),
            )
        }

        SectionHeader(stringResource(R.string.node_address_full))
        SettingsGroup {
            SelectionContainer {
                Text(
                    Sharing.grouped(s.address),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

/**
 * The nodes the node hears, by a contact's name, the name the user gave it on this phone, or what a
 * card held from it claims, each with its role, when it was last heard, and how well, in bars and
 * in dB; from a node of version 18, each with Trace. One with no name is given one on tapping it.
 */
@Composable
fun NeighboursScreen(repository: NodeRepository, state: NodeState, back: () -> Unit, trace: (Long) -> Unit = {}) {
    val r = state.records
    var choosing by remember { mutableStateOf<Body.Card?>(null) }
    var naming by remember { mutableStateOf<Long?>(null) }
    NodeSubScreen(stringResource(R.string.node_nearby_nodes), back) {
        val neighbours = r.neighbours.values.sortedBy { it.heard }
        if (neighbours.isEmpty()) EmptyState(stringResource(R.string.nearby_empty_title), stringResource(R.string.neighbours_none))
        var unnamed = false
        for (n in neighbours) {
            val contact = Conversations.contactOf(r, n.routingId)
            // Not a contact's: named by the user, on this phone, or else by the card the node holds
            // from it, as what it claims, if any.
            val name = Conversations.nameOf(r, n.routingId) ?: state.relayNames[n.routingId]?.takeIf { contact == null }
            val card = if (name == null) Conversations.cardOf(r, n.routingId) else null
            if (name == null && card == null && contact == null) unnamed = true
            val line = listOf(
                stringResource(if (n.role == 1) R.string.role_relay_short else R.string.role_leaf_short),
                stringResource(R.string.heard_ago, spanText(n.heard.toLong())),
                stringResource(R.string.snr_db, n.snrQuarterDb / 4.0),
            ).joinToString(" · ")
            PersonRow(
                avatar = {
                    when {
                        name != null -> Avatar(name, size = 40.dp, key = Conversations.contactOf(r, n.routingId)?.address?.toString() ?: name)
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
                trailing = if (canTrace(state)) ({
                    TextButton(onClick = { trace(n.routingId) }, enabled = state.phase == Phase.READY) { Text(stringResource(R.string.neighbour_trace)) }
                }) else null,
                // Tapping a card asks about making a contact, as tapping it among the people near does;
                // tapping one no contact has, and no card names, names it on this phone.
                onClick = card?.let { c -> { choosing = c } } ?: if (contact == null) ({ naming = n.routingId }) else null,
            )
        }
        // Neither a contact's, even one without a name, nor a card's: why it is only a number.
        if (unnamed) Footnote(stringResource(R.string.neighbour_no_card), Modifier.padding(top = 8.dp))
        Footnote(stringResource(R.string.signal_explained), Modifier.padding(top = 4.dp))
        // A leaf answers traces only from a node it shares a session with: said before Trace is pressed.
        if (canTrace(state) && neighbours.any { leafWithoutSession(r, it.routingId) }) {
            Footnote(stringResource(R.string.trace_leaf_hint), Modifier.padding(top = 4.dp))
        }
    }
    choosing?.let { card -> MakeContactFromCard(repository, card) { choosing = null } }
    naming?.let { id -> NameRelayDialog(repository, state, id) { naming = null } }
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
            val enabled = state.update !is FirmwareUpdate.Downloading && state.update !is FirmwareUpdate.Sending &&
                state.update !is FirmwareUpdate.Bootloader
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

        // Sleeping is version 15's: a node of version 14 or earlier says nothing of it, and has no section.
        state.records.sleep?.takeIf { canSleep(state) }?.let { sleep ->
            SectionHeader(stringResource(R.string.sleep_section))
            SettingsGroup { SleepSettings(state.records, s, sleep) { repository.submit(Body.Set(it), report) } }
            Footnote(stringResource(if (s.role == 1) R.string.sleep_relay_explained else R.string.sleep_explained))
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
internal fun RoleChoice(title: String, tag: String, explained: String, selected: Boolean, choose: () -> Unit) {
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
            SettingsGroup {
                val report = rememberReport()
                // In order, each only once the one before is answered: the name, then cards on.
                fun send(settings: List<Setting>) {
                    val first = settings.firstOrNull() ?: return
                    repository.submit(Body.Set(first)) { o -> report(o); if (o is Outcome.Answered) send(settings.drop(1)) }
                }
                PresenceCard(s, ::send)
            }
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

        // SET 10 and SELF's locked are version 17's. Locked, the node takes nothing more over
        // Bluetooth, so this phone can lock it but not unlock it.
        if (canManage(state) && s.locked != null) {
            var asking by remember { mutableStateOf(false) }
            SectionHeader(stringResource(R.string.lock_section))
            SettingsGroup {
                SettingsRow(
                    stringResource(R.string.lock_bluetooth),
                    subtitle = stringResource(if (s.locked == 1) R.string.lock_bluetooth_on else R.string.lock_bluetooth_off),
                    trailing = { Switch(checked = s.locked == 1, enabled = s.locked != 1, onCheckedChange = { if (it) asking = true }) },
                )
            }
            Footnote(stringResource(R.string.lock_this_explained))
            if (asking) {
                LockDialog(state.node?.shown ?: stringResource(R.string.unnamed_device), onDismiss = { asking = false }) {
                    repository.submit(Body.Set(Setting.Lock(1)), report)
                }
            }
        }
    }
}

/** Whether the node can be managed over the mesh, have owners and lock its Bluetooth: version 17's. */
fun canManage(state: NodeState) = (state.version ?: 0) >= 17

/** Asks before locking a node's Bluetooth, saying first, as the specification has a client say, who alone can unlock it. */
@Composable
internal fun LockDialog(name: String, onDismiss: () -> Unit, lock: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lock_ask_title, name)) },
        text = { Text(stringResource(R.string.lock_ask_explained)) },
        confirmButton = { TextButton(onClick = { onDismiss(); lock() }) { Text(stringResource(R.string.lock_ask_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
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

/** Whether the node can be set to sleep, and says whether it does: `SET` 8 and 9 and `SLEEP` are version 15's. */
fun canSleep(state: NodeState) = (state.version ?: 0) >= 15

/** The wakes offered, in seconds: from `WAKE_MIN` to `WAKE_MAX`. */
private val WAKES = listOf(300, 600, 900, 1800, 3600)

/** The wake turning sleep on starts with. */
private const val WAKE_FIRST = 600

/**
 * Sleeping, for a leaf: on or off, how often it wakes, how long it listens, and what it does now.
 * Turning it on asks first, since messages to the node then wait for it to wake.
 */
@Composable
internal fun SleepSettings(records: Records, self: Body.Self, sleep: Body.Sleep, set: (Setting) -> Unit) {
    val context = LocalContext.current
    var asking by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    val on = sleep.wake != 0
    SettingsRow(
        stringResource(R.string.sleep_on),
        subtitle = sleepText(context, records, self, sleep),
        trailing = {
            Switch(checked = on, onCheckedChange = {
                if (it) asking = true else set(Setting.Wake(0))
            })
        },
    )
    if (on) {
        GroupDivider()
        Box {
            SettingsRow(
                stringResource(R.string.sleep_wake),
                value = context.getString(R.string.sleep_every, spanText(sleep.wake.toLong())),
                onClick = { choosing = true },
            )
            DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                for (w in WAKES) {
                    DropdownMenuItem(
                        text = { Text(context.getString(R.string.sleep_every, spanText(w.toLong()))) },
                        onClick = { choosing = false; set(Setting.Wake(w)) },
                    )
                }
            }
        }
        GroupDivider()
        NumberSetting(stringResource(R.string.sleep_listen), "${sleep.listen}", unit = "s") { text ->
            text.toIntOrNull()?.takeIf { it in Sleeping.LISTEN_MIN..Sleeping.LISTEN_MAX }
                ?.let { set(Setting.Listen(it)) }
        }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.sleep_ask_title)) },
            text = { Text(stringResource(R.string.sleep_ask_explained, spanText(WAKE_FIRST.toLong()))) },
            confirmButton = {
                TextButton(onClick = { asking = false; set(Setting.Wake(WAKE_FIRST)) }) {
                    Text(stringResource(R.string.sleep_ask_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** What a sleeping node does now, as `SLEEP`'s `state` says, its keeper named where the app can. */
private fun sleepText(context: android.content.Context, records: Records, self: Body.Self, sleep: Body.Sleep): String {
    fun relay(id: Long) = Conversations.nameOf(records, id) ?: Conversations.cardOf(records, id)?.name?.ifEmpty { null }
        ?: routingText(id)
    return when {
        self.role == 1 -> context.getString(R.string.sleep_state_relay)
        sleep.state == Sleeping.ASLEEP -> context.getString(R.string.sleep_state_asleep, relay(sleep.keeper))
        sleep.state == Sleeping.WAITING -> context.getString(R.string.sleep_state_waiting)
        sleep.state == Sleeping.RESTING -> context.getString(R.string.sleep_state_resting, relay(sleep.keeper), spanText(sleep.retry.toLong()))
        else -> context.getString(R.string.sleep_state_awake)
    }
}

/** Whether the node can be turned off from the app: `OFF` is version 9's. */
fun canTurnOff(state: NodeState) = (state.version ?: 0) >= 9

/** Whether the node can be erased from the app: `WIPE` is version 13's. */
fun canWipe(state: NodeState) = (state.version ?: 0) >= 13

/**
 * Asks before erasing the node, and says first what the specification has a client say: its
 * address, contacts, groups and messages go for good, and it must be paired again.
 */
@Composable
private fun WipeDialog(node: ChosenNode, onDismiss: () -> Unit, wipe: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wipe_node_title, node.shown ?: stringResource(R.string.unnamed_device))) },
        text = { Text(stringResource(R.string.wipe_node_explained)) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); wipe() }) {
                Text(stringResource(R.string.wipe), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Says the node was erased, once it has, and whether the user must still remove its pairing by hand. */
@Composable
fun WipedDialog(wiped: Wiped, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.wiped_title, wiped.name ?: stringResource(R.string.unnamed_device))) },
        text = { Text(stringResource(if (wiped.bondRemoved) R.string.wiped_text else R.string.wiped_text_bond)) },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.ok)) } },
    )
}

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
 * What to send for the node's card, in order: the name typed, if it is not the one the node has,
 * and then, if [turnOn] and the card is off, cards on. Turning it on, by the switch or by saving a
 * name while it is off, takes the name typed with it, so the card never goes out with an old one.
 */
internal fun cardRequests(on: Boolean, saved: String, typed: String, turnOn: Boolean): List<Setting> = buildList {
    if (typed.trim() != saved) add(Setting.CardName(typed.trim()))
    if (turnOn && !on) add(Setting.Cards(1))
}

/**
 * Whether the node sends cards, and the name they carry: one switch, with the name under it. Each is
 * sent only when the user changes it here: being seen is theirs to choose, so the switch says plainly
 * what turning it on puts on the air. While the card is off, saving the name turns it on too, and the
 * button says so: a name saved to a card that stays off was what confused people.
 */
@Composable
internal fun PresenceCard(self: Body.Self, set: (List<Setting>) -> Unit) {
    val on = self.cards == 1
    val current = self.cardName.orEmpty()
    var name by remember(current) { mutableStateOf(current) }
    val bytes = utf8Length(name.trim())
    val tooLong = bytes > Companion.CARD_NAME_MAX
    SettingsRow(
        stringResource(R.string.card_on),
        subtitle = stringResource(if (on) R.string.card_on_yes else R.string.card_on_no),
        trailing = {
            Switch(
                checked = on,
                enabled = !tooLong || on,
                onCheckedChange = { set(if (it) cardRequests(on, current, name, turnOn = true) else listOf(Setting.Cards(0))) },
            )
        },
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
            onClick = { set(cardRequests(on, current, name, turnOn = true)) },
            enabled = !tooLong && name.trim() != current,
        ) { Text(stringResource(if (on) R.string.save else R.string.card_name_save_on)) }
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
    if (updateBy(board, state.updatedBy) == UpdateBy.NONE) {
        // A node of version 12 says its board whether or not a client can update it: an nRF52840
        // board whose bootloader cannot take an image over Bluetooth is flashed over USB.
        Text(stringResource(R.string.firmware_bootloader_usb_only))
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
                // A release from before signing, to a node that takes only signed images over the link.
                install(u.image.signed(), state.version, u.image.update) == Install.REFUSE ->
                    Text(stringResource(R.string.update_unsigned, u.release))
                else -> {
                    Text(stringResource(if (u.offer == Offer.NEWER) R.string.update_newer else R.string.update_unknown, u.release))
                    Button(onClick = { confirming = u }, enabled = ready) { Text(stringResource(R.string.update)) }
                }
            }
            if (u.image == null || u.offer == Offer.CURRENT || install(u.image.signed(), state.version, u.image.update) == Install.REFUSE) {
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
        FirmwareUpdate.NotTrusted -> Ended(stringResource(R.string.update_not_trusted), repository::dismissUpdate)
        is FirmwareUpdate.Refused -> Ended(
            when (u.code) {
                ErrorCode.NO_ROOM -> stringResource(R.string.update_no_room)
                ErrorCode.NOT_AN_IMAGE -> stringResource(R.string.update_not_an_image)
                else -> stringResource(R.string.update_refused, errorText(context, u.code))
            },
            repository::dismissUpdate,
        )
        is FirmwareUpdate.Bootloader -> when (u.step) {
            BootloaderStep.SENDING -> Progress(
                stringResource(
                    R.string.update_boot_sending, u.release, percent(u) ?: 0,
                    Formatter.formatFileSize(context, u.sent), Formatter.formatFileSize(context, u.size),
                ) + "\n\n" + stringResource(R.string.update_boot_window),
                if (u.size > 0) u.sent.toFloat() / u.size else 0f,
                // Stopped part of the way, the node would wait in its bootloader: it is not offered.
                cancel = null,
            )
            else -> {
                Text(
                    stringResource(
                        when (u.step) {
                            BootloaderStep.ASKING -> R.string.update_boot_asking
                            BootloaderStep.FINDING -> R.string.update_boot_finding
                            BootloaderStep.CONNECTING -> R.string.update_boot_connecting
                            BootloaderStep.ERASING -> R.string.update_boot_erasing
                            else -> R.string.update_boot_checking
                        },
                    ) + if (u.step == BootloaderStep.CHECKING) "" else "\n\n" + stringResource(R.string.update_boot_window),
                )
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        is FirmwareUpdate.BootloaderStopped -> {
            Text(
                when (u.why) {
                    BootloaderFailure.NOT_FOUND -> stringResource(R.string.update_boot_not_found)
                    BootloaderFailure.NO_LINK -> stringResource(R.string.update_boot_no_link)
                    BootloaderFailure.STOPPED -> stringResource(R.string.update_boot_stopped, dfuText(context, u.dfu?.why))
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = repository::retryBootloader) { Text(stringResource(R.string.update_boot_try_again)) }
                TextButton(onClick = repository::dismissUpdate) { Text(stringResource(R.string.update_boot_leave)) }
            }
        }
        is FirmwareUpdate.Failed -> Ended(
            stringResource(
                when (u.why) {
                    FirmwareFailure.DOWNLOAD -> R.string.update_download_failed
                    FirmwareFailure.CORRUPT -> R.string.update_corrupt
                    FirmwareFailure.UNSUPPORTED -> R.string.update_unsupported
                    FirmwareFailure.CHANGED -> R.string.update_changed
                    FirmwareFailure.UNSIGNED -> R.string.update_unsigned_failed
                },
            ),
            repository::dismissUpdate,
        )
    }

    confirming?.let { c ->
        val signed = c.image?.signed()
        // Not signed by a release key this app holds: said first, and installed only if asked again.
        val warn = signed != null && install(signed, state.version, c.image.update) == Install.WARN
        val bootloader = c.image?.update == UpdateBy.BOOTLOADER
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(if (warn) R.string.update_warn_title else R.string.update_confirm_title, c.release)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        warn && signed == Signed.UNSIGNED -> Text(stringResource(R.string.update_warn_unsigned, c.release), color = MaterialTheme.colorScheme.error)
                        warn -> Text(stringResource(R.string.update_warn_not_verified, c.release), color = MaterialTheme.colorScheme.error)
                        else -> Text(stringResource(R.string.update_signed))
                    }
                    Text(stringResource(if (bootloader) R.string.update_confirm_text_bootloader else R.string.update_confirm_text))
                    if (bootloader) Text(stringResource(R.string.update_boot_window))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    c.image?.let { repository.startUpdate(c.release, it, insisted = warn) }
                }) {
                    if (warn) Text(stringResource(R.string.update_anyway), color = MaterialTheme.colorScheme.error) else Text(stringResource(R.string.update))
                }
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
internal fun NumberSetting(label: String?, initial: String, unit: String? = null, password: Boolean = false, done: (String) -> Unit) {
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
