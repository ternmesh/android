// The addresses the user has saved, with their names: the only ones the node lets make first
// contact. A contact is added by its address, which its owner reads off their own Node tab.
package org.ternmesh.app.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextOverflow
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
fun ContactsScreen(repository: NodeRepository, state: NodeState, open: (Peer) -> Unit) {
    val contacts = remember(state.records) { state.records.contacts.values.sortedBy { it.name.lowercase() } }
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Body.Contact?>(null) }
    val report = rememberReport()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            if (contacts.isEmpty()) item { Text(stringResource(R.string.contacts_empty), Modifier.padding(24.dp)) }
            items(contacts, key = { it.address.toString() }) { c ->
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text(c.name.ifEmpty { Conversations.short(c.address.toString()) }) },
                    supportingContent = {
                        Column {
                            Text(c.address.toString(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(if (c.session == 1) R.string.session_yes else R.string.session_no))
                        }
                    },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.message)) }, onClick = { menu = false; open(Peer.Contact(c.address)) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; renaming = c })
                                DropdownMenuItem(text = { Text(stringResource(R.string.remove)) }, onClick = {
                                    menu = false
                                    repository.submit(Body.RemoveContact(c.address), report)
                                })
                            }
                        }
                    },
                    modifier = Modifier.clickable { open(Peer.Contact(c.address)) },
                )
                HorizontalDivider()
            }
        }
        FloatingActionButton(onClick = { adding = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Filled.Add, stringResource(R.string.add_contact))
        }
    }

    if (adding) {
        AddContactDialog(onDismiss = { adding = false }) { address, name ->
            adding = false
            repository.submit(Body.SaveContact(address, name), report)
        }
    }
    renaming?.let { c ->
        NameDialog(stringResource(R.string.rename), c.name, Companion.NAME_MAX, stringResource(R.string.save), onDismiss = { renaming = null }) { name ->
            renaming = null
            repository.submit(Body.SaveContact(c.address, name), report)
        }
    }
}

@Composable
private fun AddContactDialog(onDismiss: () -> Unit, done: (Address, String) -> Unit) {
    var hex by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    // Pasted addresses come with spaces, line breaks and capitals; none of them matter.
    val address = Address.fromHex(hex.filter { !it.isWhitespace() }.lowercase())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_contact)) },
        text = {
            Column {
                OutlinedTextField(
                    value = hex,
                    onValueChange = { hex = it },
                    label = { Text(stringResource(R.string.address)) },
                    isError = hex.isNotBlank() && address == null,
                    supportingText = if (hex.isNotBlank() && address == null) ({ Text(stringResource(R.string.address_invalid)) }) else null,
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
