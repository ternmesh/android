// Every conversation, newest first, with what was said last and how much is unread; and the first
// contacts the node turned away, for the user to save or dismiss.
package org.ternmesh.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.ternmesh.app.R
import org.ternmesh.app.node.NodeRepository
import org.ternmesh.app.node.NodeState
import org.ternmesh.companion.Body
import org.ternmesh.companion.Companion
import org.ternmesh.companion.Conversations
import org.ternmesh.companion.Item
import org.ternmesh.companion.Outcome
import org.ternmesh.companion.Peer

@Composable
fun ChatsScreen(repository: NodeRepository, state: NodeState, open: (Peer) -> Unit) {
    val context = LocalContext.current
    val conversations = remember(state.records) { Conversations.of(state.records) }
    var menu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.asked, key = { "asked:${it.address}" }) { a -> AskedCard(repository, state, a) }
            if (conversations.isEmpty()) {
                item { Text(stringResource(R.string.chats_empty), Modifier.padding(24.dp)) }
            }
            items(conversations, key = { org.ternmesh.app.node.Notifier.key(it.peer) }) { c ->
                ListItem(
                    headlineContent = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    overlineContent = if (c.peer is Peer.Group) ({ Text(stringResource(R.string.group_label)) }) else null,
                    supportingContent = c.last?.let { last ->
                        { Text(preview(context, last), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    },
                    trailingContent = {
                        Column(horizontalAlignment = Alignment.End) {
                            c.last?.let { Text(timeText(context, timeOf(it)), style = MaterialTheme.typography.labelSmall) }
                            if (c.unread > 0) Badge { Text("${c.unread}") }
                        }
                    },
                    modifier = Modifier.clickable { open(c.peer) },
                )
                HorizontalDivider()
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            FloatingActionButton(onClick = { menu = true }) { Icon(Icons.Filled.Add, stringResource(R.string.new_chat)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.new_chat)) }, onClick = { menu = false; picking = true })
                DropdownMenuItem(text = { Text(stringResource(R.string.new_group)) }, onClick = { menu = false; naming = true })
            }
        }
    }

    if (picking) {
        ContactPicker(state, onDismiss = { picking = false }) { address ->
            picking = false
            open(Peer.Contact(address))
        }
    }
    if (naming) {
        val report = rememberReport()
        NameDialog(stringResource(R.string.new_group), "", Companion.NAME_MAX, stringResource(R.string.make), onDismiss = { naming = false }) { name ->
            naming = false
            repository.submit(Body.MakeGroup(name)) { o ->
                val made = (o as? Outcome.Answered)?.body as? Body.Made
                if (made != null) open(Peer.Group(made.group)) else report(o)
            }
        }
    }
}

@Composable
private fun AskedCard(repository: NodeRepository, state: NodeState, a: Body.Asked) {
    var saving by remember { mutableStateOf(false) }
    val who = Conversations.short(a.address.toString())
    Card(Modifier.padding(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.asked_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(if (a.why == 2) R.string.asked_no_room else R.string.asked_not_contact, who))
            Text(a.address.toString(), style = MaterialTheme.typography.bodySmall)
            Row {
                if (a.why != 2) TextButton(onClick = { saving = true }) { Text(stringResource(R.string.save_contact)) }
                TextButton(onClick = { repository.dismissAsked(a) }) { Text(stringResource(R.string.dismiss)) }
            }
        }
    }
    if (saving) {
        val report = rememberReport()
        NameDialog(stringResource(R.string.save_contact), "", Companion.NAME_MAX, stringResource(R.string.save), onDismiss = { saving = false }) { name ->
            saving = false
            repository.submit(Body.SaveContact(a.address, name)) { o ->
                report(o)
                if (o is Outcome.Answered) repository.dismissAsked(a)
            }
        }
    }
}

/** A contact to write to, from the saved ones. */
@Composable
fun ContactPicker(state: NodeState, onDismiss: () -> Unit, picked: (org.ternmesh.companion.Address) -> Unit) {
    val contacts = state.records.contacts.values.sortedBy { it.name.lowercase() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_contact)) },
        text = {
            if (contacts.isEmpty()) {
                Text(stringResource(R.string.no_contacts))
            } else {
                LazyColumn {
                    items(contacts, key = { it.address.toString() }) { c ->
                        ListItem(
                            headlineContent = { Text(c.name.ifEmpty { Conversations.short(c.address.toString()) }) },
                            modifier = Modifier.clickable { picked(c.address) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Asks for a name of at most [limit] UTF-8 bytes. */
@Composable
fun NameDialog(title: String, initial: String, limit: Int, confirm: String, onDismiss: () -> Unit, done: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { if (utf8Length(it) <= limit) text = it },
                label = { Text(stringResource(R.string.name)) },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { done(text.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun preview(context: android.content.Context, item: Item): String = when (item) {
    is Body.Message -> item.text
    is Body.GroupMessage -> item.text
    is Body.Invite -> context.getString(R.string.invite_from, item.name)
    else -> ""
}

fun timeOf(item: Item): Long = when (item) {
    is Body.Message -> item.time
    is Body.GroupMessage -> item.time
    is Body.Invite -> item.time
    else -> 0
}
