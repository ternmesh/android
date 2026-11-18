// Who is near: the cards the node holds from other nodes near it, each with the name its sender
// claims, how long ago it was heard, and whether it is a contact; and the neighbours it hears with
// no card. A card's sender becomes a contact only when the user says so, under the name they choose.
package org.ternmesh.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.ternmesh.app.R
import org.ternmesh.app.node.NodeRepository
import org.ternmesh.app.node.NodeState
import org.ternmesh.companion.Body
import org.ternmesh.companion.Conversations
import org.ternmesh.companion.Peer
import org.ternmesh.companion.Sharing

/** Whether the node speaks cards: version 6 or later. Below it there are none to show or set. */
fun hasCards(state: NodeState) = (state.version ?: 0) >= 6

/** How many seconds ago the node heard [card], now: its `heard` counts on from when the record came. */
fun heardNow(state: NodeState, card: Body.Card, now: Long): Long = card.heard + (now - (state.arrived[card] ?: now)) / 1000

/**
 * Who is near: the people whose cards the node holds, first, each by the name it claims and how
 * well the node hears it when it is a neighbour too; then the neighbours with no card, by a
 * contact's name or their routing id. Tapping a contact opens the conversation; tapping a card
 * asks about making a contact of it.
 */
@Composable
fun NearbyList(repository: NodeRepository, state: NodeState, open: (Peer) -> Unit) {
    val context = LocalContext.current
    val now = rememberNow()
    val r = state.records
    val cards = remember(r, state.arrived, now) {
        if (hasCards(state)) r.cards.values.sortedBy { heardNow(state, it, now) } else emptyList()
    }
    val byRouting = remember(r) { r.neighbours.values.associateBy { it.routingId } }
    val carded = remember(r, cards) { cards.mapNotNull { r.routingIdOf(it.address) }.toSet() }
    val bare = remember(r, carded) { r.neighbours.values.filter { it.routingId !in carded }.sortedBy { it.heard } }
    var choosing by remember { mutableStateOf<Body.Card?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (cards.isEmpty() && bare.isEmpty()) {
            item { EmptyState(stringResource(R.string.nearby_empty_title), stringResource(if (hasCards(state)) R.string.cards_empty else R.string.neighbours_none)) }
        }
        if (cards.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.nearby_cards)) }
            items(cards, key = { it.address.toString() }) { card ->
                val contact = r.contacts[card.address]
                val heard = r.routingIdOf(card.address)?.let { byRouting[it] }
                PersonRow(
                    avatar = { Avatar(card.name, key = card.address.toString(), unknown = card.name.isEmpty()) },
                    title = { CardName(card) },
                    tag = if (contact != null) stringResource(R.string.nearby_contact_tag) else null,
                    subtitle = buildList {
                        heard?.let { add(stringResource(if (it.role == 1) R.string.role_relay_short else R.string.role_leaf_short)) }
                        add(stringResource(R.string.seen_ago, spanText(heardNow(state, card, now))))
                    }.joinToString(" · "),
                    signal = heard?.snrQuarterDb,
                ) {
                    // A contact already: its conversation. Otherwise the user is asked; nothing is saved or sent yet.
                    if (contact != null) open(Peer.Contact(card.address)) else choosing = card
                }
            }
        }
        if (bare.isNotEmpty()) {
            item { SectionHeader(stringResource(if (cards.isEmpty()) R.string.nearby_heard else R.string.nearby_heard_no_card)) }
            items(bare, key = { "n${it.routingId}" }) { n ->
                val name = Conversations.nameOf(r, n.routingId)
                val contact = Conversations.contactOf(r, n.routingId)
                PersonRow(
                    avatar = { Avatar(name ?: "", key = contact?.address?.toString() ?: "%08x".format(n.routingId), unknown = name == null) },
                    title = {
                        if (name != null) {
                            Text(name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                        } else {
                            Text(routingText(n.routingId), style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
                        }
                    },
                    tag = if (contact != null) stringResource(R.string.nearby_contact_tag) else null,
                    subtitle = stringResource(if (n.role == 1) R.string.role_relay_short else R.string.role_leaf_short) + " · " +
                        stringResource(R.string.heard_ago, spanText(n.heard.toLong())),
                    signal = n.snrQuarterDb,
                    onClick = contact?.let { c -> { open(Peer.Contact(c.address)) } },
                )
            }
        }
        if (cards.isNotEmpty()) item { Footnote(stringResource(R.string.cards_explained), Modifier.padding(top = 12.dp)) }
        else if (bare.isNotEmpty() && hasCards(state)) item { Footnote(stringResource(R.string.neighbour_no_card), Modifier.padding(top = 12.dp)) }
    }
    choosing?.let { card -> MakeContactFromCard(repository, card) { choosing = null } }
}

/** A routing id as two groups of four hex digits, as a person reads one out. */
internal fun routingText(id: Long): String = "%08x".format(id).let { "${it.substring(0, 4)} ${it.substring(4)}" }

/** One person or node in a list: avatar, name and a tag, a line under it, and its signal. */
@Composable
internal fun PersonRow(
    avatar: @Composable () -> Unit,
    title: @Composable () -> Unit,
    subtitle: String?,
    tag: String? = null,
    signal: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        avatar()
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f, fill = false)) { title() }
                tag?.let {
                    Tag(it, color = LocalTernColors.current.good, background = LocalTernColors.current.good.copy(alpha = 0.14f))
                }
            }
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        signal?.let { SignalBars(it, Modifier.padding(end = 8.dp)) }
        trailing?.invoke()
    }
}

/**
 * What tapping the card of someone not yet a contact does, wherever it is shown: the user is asked
 * first, told what the card's name is and is not, and then asked the name to save them under, with
 * the card's offered. Nothing is saved or sent until they say so; [done] once they are through.
 */
@Composable
internal fun MakeContactFromCard(repository: NodeRepository, card: Body.Card, done: () -> Unit) {
    var adding by remember(card.address) { mutableStateOf(false) }
    val report = rememberReport()
    if (!adding) {
        AlertDialog(
            onDismissRequest = done,
            title = { CardName(card) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Sharing.shortCode(card.address), style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
                    Text(
                        if (card.name.isEmpty()) {
                            stringResource(R.string.card_unnamed_explained)
                        } else {
                            stringResource(R.string.card_named_explained, card.name)
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { adding = true }) { Text(stringResource(R.string.card_add)) }
            },
            dismissButton = { TextButton(onClick = done) { Text(stringResource(R.string.cancel)) } },
        )
    } else {
        // The card's name is offered, for the user to keep or change: the contact's name is theirs.
        AddContactDialog(Sharing.text(card.address), suggested = card.name, onDismiss = done) { address, name ->
            done()
            repository.submit(Body.SaveContact(address, name), report)
        }
    }
}

/** The name a card carries, as what its sender claims, or that it carries none. */
@Composable
internal fun CardName(card: Body.Card) {
    if (card.name.isEmpty()) {
        Text(stringResource(R.string.card_no_name), color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = FontStyle.Italic)
    } else {
        Text(stringResource(R.string.card_claim, card.name), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
    }
}
