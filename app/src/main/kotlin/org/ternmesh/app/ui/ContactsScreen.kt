// The People tab: the addresses the user has saved, with their names, the only ones the node lets
// make first contact; and, beside them, who is near. A contact is added from the QR code on its
// owner's Node tab, from their link, or by its address typed in; its menu shows the short code its
// owner can check against their own.
package org.ternmesh.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.ternmesh.companion.Sharing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ternmesh.app.R
import org.ternmesh.app.node.NodeRepository
import org.ternmesh.app.node.NodeState
import org.ternmesh.companion.Address
import org.ternmesh.companion.Body
import org.ternmesh.companion.Companion
import org.ternmesh.companion.Conversations
import org.ternmesh.companion.Outcome
import org.ternmesh.companion.Peer

/** Contacts or who is near, under one tab: the two ways of answering "who can I talk to?" */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(
    repository: NodeRepository,
    state: NodeState,
    incoming: Address?,
    taken: () -> Unit,
    toNode: () -> Unit,
    manage: (Address) -> Unit = {},
    open: (Peer) -> Unit,
) {
    var nearby by rememberSaveable { mutableStateOf(false) }
    // A link to add is taken by the contacts' dialog.
    LaunchedEffect(incoming) { if (incoming != null) nearby = false }
    val near = remember(state.records) {
        val cards = if (hasCards(state)) state.records.cards.keys.mapNotNull { state.records.routingIdOf(it) }.toSet() else emptySet()
        cards.size + state.records.neighbours.keys.count { it !in cards }
    }
    Column(Modifier.fillMaxSize()) {
        TabTitle(stringResource(R.string.tab_people), state, toNode)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            SegmentedButton(
                selected = !nearby,
                onClick = { nearby = false },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                icon = {},
            ) { Text(stringResource(R.string.people_contacts)) }
            SegmentedButton(
                selected = nearby,
                onClick = { nearby = true },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                icon = {},
            ) { Text(if (near > 0) stringResource(R.string.people_nearby_count, near) else stringResource(R.string.people_nearby)) }
        }
        Box(Modifier.weight(1f).padding(top = 6.dp)) {
            if (nearby) NearbyList(repository, state, open) else ContactsList(repository, state, incoming, taken, manage, open)
        }
    }
}

@Composable
private fun ContactsList(
    repository: NodeRepository,
    state: NodeState,
    incoming: Address?,
    taken: () -> Unit,
    manage: (Address) -> Unit,
    open: (Peer) -> Unit,
) {
    val contacts = remember(state.records) { state.records.contacts.values.sortedBy { it.name.lowercase() } }
    var adding by remember { mutableStateOf(false) }
    // What the dialog starts with: a link opened from elsewhere, or a code just scanned.
    var given by remember { mutableStateOf("") }
    var showing by remember { mutableStateOf<Body.Contact?>(null) }
    val context = LocalContext.current
    LaunchedEffect(incoming) {
        if (incoming != null) {
            given = Sharing.text(incoming)
            adding = true
            taken()
        }
    }
    var renaming by remember { mutableStateOf<Body.Contact?>(null) }
    var removing by remember { mutableStateOf<Body.Contact?>(null) }
    var owning by remember { mutableStateOf<Body.Contact?>(null) }
    val report = rememberReport()
    // Owners, and managing a node over the mesh, are version 17's: a node of 16 or earlier offers neither.
    val managing = canManage(state)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
            if (contacts.isEmpty()) item { EmptyState(stringResource(R.string.contacts_empty_title), stringResource(R.string.contacts_empty)) }
            items(contacts, key = { it.address.toString() }) { c ->
                var menu by remember { mutableStateOf(false) }
                val name = c.name.ifEmpty { Conversations.short(c.address.toString()) }
                val neighbour = state.records.routingIdOf(c.address)?.let { state.records.neighbours[it] }
                PersonRow(
                    avatar = { Avatar(name, key = c.address.toString()) },
                    title = { Text(name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)) },
                    subtitle = listOfNotNull(
                        stringResource(if (c.session == 1) R.string.session_yes else R.string.session_no),
                        stringResource(R.string.owner_tag).takeIf { c.owner == 1 },
                    ).joinToString(" · "),
                    signal = neighbour?.snrQuarterDb,
                    trailing = {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.message)) }, onClick = { menu = false; open(Peer.Contact(c.address)) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.show_code)) }, onClick = { menu = false; showing = c })
                                DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, onClick = { menu = false; shareLink(context, c.address) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; renaming = c })
                                if (managing) {
                                    // Manage only a node the app knows its node owns; any other, it can ask.
                                    if (c.address in state.owned) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.manage)) }, onClick = { menu = false; manage(c.address) })
                                    } else {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.check_owner)) }, onClick = {
                                            menu = false
                                            // Its dialog is the repository's: it outlives this screen.
                                            repository.manage(c.address, check = true)
                                        })
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.not_yours), style = MaterialTheme.typography.bodySmall) },
                                            onClick = {},
                                            enabled = false,
                                        )
                                    }
                                    if (c.owner == 1) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.remove_owner)) }, onClick = {
                                            menu = false
                                            repository.submit(Body.Owner(c.address, 0), report)
                                        })
                                    } else {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.make_owner)) }, onClick = { menu = false; owning = c })
                                    }
                                }
                                DropdownMenuItem(text = { Text(stringResource(R.string.remove)) }, onClick = { menu = false; removing = c })
                            }
                        }
                    },
                ) { open(Peer.Contact(c.address)) }
            }
        }
        FloatingActionButton(
            onClick = { given = ""; adding = true },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(Icons.Filled.Add, stringResource(R.string.add_contact))
        }
    }

    if (adding) {
        AddContactDialog(given, onDismiss = { adding = false }) { address, name ->
            adding = false
            repository.submit(Body.SaveContact(address, name), report)
        }
    }
    showing?.let { c -> CodeDialog(c) { showing = null } }
    owning?.let { c ->
        // Only ever what the user asked for, once told what an owner can do.
        AlertDialog(
            onDismissRequest = { owning = null },
            title = { Text(stringResource(R.string.make_owner_title, c.name.ifEmpty { Conversations.short(c.address.toString()) })) },
            text = { Text(stringResource(R.string.make_owner_explained, Companion.OWNERS)) },
            confirmButton = {
                TextButton(onClick = {
                    owning = null
                    repository.submit(Body.Owner(c.address, 1), report)
                }) { Text(stringResource(R.string.make_owner)) }
            },
            dismissButton = { TextButton(onClick = { owning = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    removing?.let { c ->
        // A client that offers to forget while it removes sends both, the forgetting first.
        val conversation = Conversations.of(state.records, Peer.Contact(c.address))
        val canAlso = canForget(state, conversation)
        var forgetToo by remember(c) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.remove_title, c.name.ifEmpty { Conversations.short(c.address.toString()) })) },
            text = {
                Column {
                    Text(stringResource(R.string.remove_explained))
                    if (canAlso) AlsoForget(stringResource(R.string.remove_delete_chat), forgetToo) { forgetToo = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    val remove = { repository.submit(Body.RemoveContact(c.address), report) }
                    val through = conversation.last?.id
                    if (canAlso && forgetToo && through != null) {
                        repository.forget(conversation.peer, through) { o -> if (o is Outcome.Answered) remove() else report(o) }
                    } else {
                        remove()
                    }
                }) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    renaming?.let { c ->
        NameDialog(stringResource(R.string.rename), c.name, Companion.NAME_MAX, stringResource(R.string.save), onDismiss = { renaming = null }) { name ->
            renaming = null
            repository.submit(Body.SaveContact(c.address, name), report)
        }
    }
}

@Composable
internal fun AddContactDialog(given: String, suggested: String = "", onDismiss: () -> Unit, done: (Address, String) -> Unit) {
    var text by remember(given) { mutableStateOf(given) }
    var name by remember(suggested) { mutableStateOf(suggested) }
    val say = LocalSay.current
    val context = LocalContext.current
    // Pasted text comes with a line break or a space at either end, which is no part of it.
    val address = Sharing.read(text.trim())
    val scan = rememberScanner { found ->
        if (Sharing.read(found.trim()) != null) text = found.trim() else say(context.getString(R.string.scan_not_tern))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_contact)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = scan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.scan)) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.address)) },
                    isError = text.isNotBlank() && address == null,
                    supportingText = when {
                        address != null -> ({ Text(stringResource(R.string.check_code, Sharing.shortCode(address))) })
                        text.isNotBlank() -> ({ Text(stringResource(R.string.address_invalid)) })
                        else -> null
                    },
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (utf8Length(it) <= Companion.NAME_MAX) name = it },
                    label = { Text(stringResource(R.string.name)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { address?.let { done(it, name.trim()) } }, enabled = address != null) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A contact's code, for passing the same node on to someone else. */
@Composable
private fun CodeDialog(c: Body.Contact, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(c.name.ifEmpty { Conversations.short(c.address.toString()) }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                QrCode(c.address, Modifier.fillMaxWidth())
                Text(Sharing.shortCode(c.address), style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
                Text(stringResource(R.string.contact_code_explained), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}
