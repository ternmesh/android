// The addresses the user has saved, with their names: the only ones the node lets make first
// contact. A contact is added from the QR code on its owner's Node tab, from their link, or by its
// address typed in; each shows the short code its owner can check against their own. At their head,
// on a node that speaks cards, is the way to who is about.
package org.ternmesh.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import org.ternmesh.companion.Sharing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import org.ternmesh.companion.Peer

@Composable
fun ContactsScreen(
    repository: NodeRepository,
    state: NodeState,
    incoming: Address?,
    taken: () -> Unit,
    about: () -> Unit,
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
    val report = rememberReport()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            if (hasCards(state)) item(key = "cards") { CardsEntry(state, about) }
            if (contacts.isEmpty()) item { Text(stringResource(R.string.contacts_empty), Modifier.padding(24.dp)) }
            items(contacts, key = { it.address.toString() }) { c ->
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text(c.name.ifEmpty { Conversations.short(c.address.toString()) }) },
                    supportingContent = {
                        Column {
                            Text(Sharing.shortCode(c.address), fontFamily = FontFamily.Monospace)
                            Text(stringResource(if (c.session == 1) R.string.session_yes else R.string.session_no))
                        }
                    },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.message)) }, onClick = { menu = false; open(Peer.Contact(c.address)) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.show_code)) }, onClick = { menu = false; showing = c })
                                DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, onClick = { menu = false; shareLink(context, c.address) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; renaming = c })
                                DropdownMenuItem(text = { Text(stringResource(R.string.remove)) }, onClick = { menu = false; removing = c })
                            }
                        }
                    },
                    modifier = Modifier.clickable { open(Peer.Contact(c.address)) },
                )
                HorizontalDivider()
            }
        }
        FloatingActionButton(onClick = { given = ""; adding = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
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
    removing?.let { c ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.remove_title, c.name.ifEmpty { Conversations.short(c.address.toString()) })) },
            text = { Text(stringResource(R.string.remove_explained)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    repository.submit(Body.RemoveContact(c.address), report)
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
