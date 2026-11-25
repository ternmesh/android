// The pieces every screen is built from: a tab's title with the node's status beside it, a pushed
// screen's bar, avatars, signal bars, and grouped rows of settings.
package org.ternmesh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ternmesh.app.R
import org.ternmesh.app.node.NodeState
import org.ternmesh.app.node.Phase

/**
 * The card at the head of a screen, on the dashboard's teal: the node's dashboard, a reading, the
 * address, a trace or a range test each lead with one, so that what a screen is about is found first.
 */
@Composable
fun HeroCard(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val extra = LocalTernColors.current
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(extra.heroTop, extra.heroBottom)))
            .padding(18.dp),
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

/** A button on a [HeroCard]: an icon and a word, on a lighter wash of the card's own colour. */
@Composable
fun HeroButton(icon: ImageVector?, text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val on = LocalTernColors.current.onHero
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = on.copy(alpha = if (enabled) 0.18f else 0.08f),
        contentColor = if (enabled) on else on.copy(alpha = 0.5f),
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            icon?.let {
                Icon(it, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A status line on a [HeroCard]: a dot coloured for how it is going, and what it is doing. */
@Composable
fun HeroStatus(text: String, good: Boolean, modifier: Modifier = Modifier) {
    val on = LocalTernColors.current.onHero
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (good) HeroGood else HeroWarn))
        Spacer(Modifier.width(7.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = on.copy(alpha = 0.85f))
    }
}

/** The status dot's colours on a [HeroCard], light enough to read on its teal. */
val HeroGood = Color(0xFF9FF0C6)
val HeroWarn = Color(0xFFFFD08A)

/** One choice of several in a [SettingsGroup]: a radio button, its name, and what it means under it. */
@Composable
fun ChoiceRow(title: String, detail: String?, selected: Boolean, choose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = choose)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** A tab's own title, large, with the node's status at its end, and [actions] before it. */
@Composable
fun TabTitle(title: String, state: NodeState, toNode: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        actions()
        if (toNode != null) NodeStatusPill(state, toNode)
    }
}

/**
 * The node's name, whether the link is up, and its battery: on every tab, so that whether the node
 * is there is never more than a glance away. Tapping it opens the node.
 */
@Composable
fun NodeStatusPill(state: NodeState, onClick: () -> Unit) {
    val extra = LocalTernColors.current
    val ready = state.phase == Phase.READY
    val dot = when {
        ready -> extra.good
        state.phase == Phase.STOPPED || state.phase == Phase.OFF -> MaterialTheme.colorScheme.error
        else -> extra.warn
    }
    val name = state.node?.shown ?: stringResource(R.string.unnamed_device)
    val percent = state.records.power?.percent?.takeIf { it in 0..100 }
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.semantics(mergeDescendants = true) {},
    ) {
        Row(Modifier.padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(7.dp))
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
            )
            if (percent != null && ready) {
                Text(
                    " · $percent%",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A pushed screen's bar: back, and its title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreenBar(title: String, back: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        actions = actions,
    )
}

private val avatarHues = listOf(
    Color(0xFF0B4F6C), Color(0xFF0F766E), Color(0xFF7C3AED), Color(0xFFC2410C),
    Color(0xFFB45309), Color(0xFFBE185D), Color(0xFF4D7C0F), Color(0xFF1D4ED8),
)

/** What picks a person's avatar colour, the same on every screen: their address, or the group's id. */
fun avatarKey(peer: org.ternmesh.companion.Peer): String = when (peer) {
    is org.ternmesh.companion.Peer.Contact -> peer.address.toString()
    is org.ternmesh.companion.Peer.Group -> org.ternmesh.app.node.Notifier.key(peer)
}

/** The letters an avatar shows: the first of each of the first two words, or a single one. */
internal fun initials(name: String): String {
    val words = name.split(' ', '(', ')', '·', '-', '_').filter { w -> w.any { it.isLetterOrDigit() } }
    val letters = words.take(2).mapNotNull { w -> w.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() }
    return letters.joinToString("").ifEmpty { "?" }
}

/**
 * A round badge of [name]'s initials, its colour always the same for the same name; a group's is a
 * rounded square, so that people and groups differ at a glance. [key] picks the colour when the name
 * may change, an address's text for instance.
 */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, group: Boolean = false, size: Dp = 44.dp, key: String = name, unknown: Boolean = false) {
    val shape = if (group) RoundedCornerShape(size * 0.3f) else CircleShape
    val bg = if (unknown) MaterialTheme.colorScheme.surfaceContainerHighest else avatarHues[Math.floorMod(key.hashCode(), avatarHues.size)]
    val fg = if (unknown) MaterialTheme.colorScheme.onSurfaceVariant else Color.White
    Box(modifier.size(size).clip(shape).background(bg), contentAlignment = Alignment.Center) {
        Text(
            if (unknown) "?" else initials(name),
            color = fg,
            fontSize = (size.value * 0.36f).sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * How well a neighbour is heard, as one to four bars. The thresholds are SNR in dB: above 5 is
 * strong, 0 is comfortable, and below -7 is near where the faster spreading factors stop hearing.
 */
fun signalBars(snrQuarterDb: Int): Int {
    val db = snrQuarterDb / 4.0
    return when {
        db >= 5 -> 4
        db >= 0 -> 3
        db >= -7 -> 2
        else -> 1
    }
}

@Composable
fun SignalBars(snrQuarterDb: Int, modifier: Modifier = Modifier) {
    val on = signalBars(snrQuarterDb)
    val description = stringResource(R.string.signal_bars, on)
    val lit = if (on >= 2) LocalTernColors.current.good else LocalTernColors.current.warn
    Row(
        modifier.height(14.dp).semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (i in 0 until 4) {
            Box(
                Modifier.width(3.dp).height((5 + 3 * i).dp).clip(RoundedCornerShape(1.dp))
                    .background(if (i < on) lit else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

/** The small capitals over a group of rows. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = SectionLabel,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
    )
}

/** A line or two under a group, saying what its settings do. */
@Composable
fun Footnote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 4.dp),
    )
}

/** Rows that belong together, on one rounded card, divided by hairlines. */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(content = content)
    }
}

@Composable
fun GroupDivider() = HorizontalDivider(Modifier.padding(start = 62.dp), color = MaterialTheme.colorScheme.outlineVariant)

/** A tinted square behind a row's icon, so that each kind of setting is found by colour as well as by name. */
@Composable
fun RowIcon(icon: ImageVector, tint: Color) {
    Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(tint), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
    }
}

/** One setting or place to go: an icon, a name, what it is now, and a chevron when it opens a screen. */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    value: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    subtitle: String? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    chevron: Boolean = true,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        icon?.let { RowIcon(it, tint) }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = if (enabled) titleColor else titleColor.copy(alpha = 0.4f),
            )
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        value?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp))
        }
        trailing?.invoke()
        if (onClick != null && chevron) {
            Icon(TernIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

/** A small rounded label beside a name: "Group", "Contact", "Relay". */
@Composable
fun Tag(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, background: Color = MaterialTheme.colorScheme.surfaceContainerHighest) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(background).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** A full-width pill-shaped search field. */
@Composable
fun SearchField(query: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TernIcons.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.weight(1f).padding(start = 10.dp, top = 11.dp, bottom = 11.dp),
            decorationBox = { inner ->
                if (query.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                inner()
            },
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = { onChange("") }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Clear, stringResource(R.string.cancel), modifier = Modifier.size(18.dp))
            }
        }
    }
}
