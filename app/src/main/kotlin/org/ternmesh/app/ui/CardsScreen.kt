// Who is about: the cards the node holds from other nodes near it, each with the name its sender
// claims beside its address's short code, how long ago it was heard, and whether it is a contact.
// A card's sender becomes a contact only when the user says so, under the name they choose.
package org.ternmesh.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardsScreen(repository: NodeRepository, state: NodeState, open: (Peer) -> Unit, back: () -> Unit) {
    val now = rememberNow()
    val cards = remember(state.records, state.arrived, now) { state.records.cards.values.sortedBy { heardNow(state, it, now) } }
    var choosing by remember { mutableStateOf<Body.Card?>(null) }
    var adding by remember { mutableStateOf<Body.Card?>(null) }
    val report = rememberReport()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.cards_title)) },
            navigationIcon = {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            },
        )
        LazyColumn(Modifier.fillMaxSize()) {
            if (cards.isEmpty()) {
                item { Text(stringResource(R.string.cards_empty), Modifier.padding(24.dp)) }
            } else {
                item {
                    Text(
                        stringResource(R.string.cards_explained),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(cards, key = { it.address.toString() }) { card ->
                val contact = state.records.contacts[card.address]
                ListItem(
                    headlineContent = { CardName(card) },
                    supportingContent = {
                        Column {
                            Text(Sharing.shortCode(card.address), fontFamily = FontFamily.Monospace)
                            Text(stringResource(R.string.heard_ago, spanText(heardNow(state, card, now))))
                            Text(
                                if (contact != null) {
                                    stringResource(R.string.card_contact, contactName(contact))
                                } else {
                                    stringResource(R.string.card_not_contact)
                                },
                            )
                        }
                    },
                    modifier = Modifier.clickable {
                        // A contact already: its conversation. Otherwise the user is asked; nothing is saved or sent yet.
                        if (contact != null) open(Peer.Contact(card.address)) else choosing = card
                    },
                )
                HorizontalDivider()
            }
        }
    }

    choosing?.let { card ->
        AlertDialog(
            onDismissRequest = { choosing = null },
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
                TextButton(onClick = { choosing = null; adding = card }) { Text(stringResource(R.string.card_add)) }
            },
            dismissButton = { TextButton(onClick = { choosing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    adding?.let { card ->
        // The card's name is offered, for the user to keep or change: the contact's name is theirs.
        AddContactDialog(Sharing.text(card.address), suggested = card.name, onDismiss = { adding = null }) { address, name ->
            adding = null
            repository.submit(Body.SaveContact(address, name), report)
        }
    }
}

/** The name a card carries, as what its sender claims, or that it carries none. */
@Composable
private fun CardName(card: Body.Card) {
    if (card.name.isEmpty()) {
        Text(stringResource(R.string.card_no_name), color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = FontStyle.Italic)
    } else {
        Text(stringResource(R.string.card_claim, card.name))
    }
}

/** The entry to who is about, at the head of the contacts: how many cards the node holds. */
@Composable
fun CardsEntry(state: NodeState, onClick: () -> Unit) {
    val count = state.records.cards.size
    ListItem(
        headlineContent = { Text(stringResource(R.string.cards_title)) },
        supportingContent = {
            Text(
                if (count == 0) {
                    stringResource(R.string.cards_entry_none)
                } else {
                    pluralStringResource(R.plurals.cards_count, count, count)
                },
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
    HorizontalDivider()
}

private fun contactName(c: Body.Contact) = c.name.ifEmpty { Conversations.short(c.address.toString()) }
