// Every conversation, newest first, with what was said last and how much is unread, and a search
// over their names and what was said in them; and the first contacts the node turned away, for the
// user to save or dismiss.
package org.ternmesh.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.ternmesh.app.R
import org.ternmesh.app.node.NodeRepository
import org.ternmesh.app.node.NodeState
import org.ternmesh.companion.Body
import org.ternmesh.companion.Companion
import org.ternmesh.companion.Conversation
import org.ternmesh.companion.Conversations
import org.ternmesh.companion.Item
import org.ternmesh.companion.Outcome
import org.ternmesh.companion.Peer
import org.ternmesh.companion.Sharing

@Composable
fun ChatsScreen(repository: NodeRepository, state: NodeState, toNode: () -> Unit, open: (Peer) -> Unit) {
    val context = LocalContext.current
    val conversations = remember(state.records) { Conversations.of(state.records) }
    var menu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(conversations, query) { conversations.filter { matches(it, query.trim()) } }
    var pressed by remember { mutableStateOf<Peer?>(null) }
    var forgetting by remember { mutableStateOf<Conversation?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
            item(key = "title") { TabTitle(stringResource(R.string.tab_chats), state, toNode) }
            if (conversations.isNotEmpty()) {
                item(key = "search") {
                    SearchField(query, { query = it }, stringResource(R.string.search_messages), Modifier.padding(bottom = 6.dp))
                }
            }
            if (query.isBlank()) items(state.asked, key = { "asked:${it.address}" }) { a -> AskedCard(repository, state, a) }
            if (query.isBlank()) {
                items(state.ownerChanges, key = { "owner:${it.from}:${it.contact}:${it.owner}:${it.by}" }) { c -> OwnerChangedCard(repository, state, c) }
            }
            if (conversations.isEmpty()) {
                item { EmptyState(stringResource(R.string.chats_empty_title), stringResource(R.string.chats_empty)) }
            } else if (shown.isEmpty()) {
                item { Text(stringResource(R.string.search_none), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(shown, key = { org.ternmesh.app.node.Notifier.key(it.peer) }) { c ->
                // Pressed and held: what may be done to the conversation from here.
                val held = canForget(state, c)
                Box {
                    ConversationRow(state, c, onLongClick = if (held) ({ pressed = c.peer }) else null) { open(c.peer) }
                    DropdownMenu(expanded = held && pressed == c.peer, onDismissRequest = { pressed = null }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete_chat)) },
                            onClick = { pressed = null; forgetting = c },
                        )
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            FloatingActionButton(
                onClick = { menu = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Icon(Icons.Filled.Add, stringResource(R.string.new_chat)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.new_chat)) }, onClick = { menu = false; picking = true })
                DropdownMenuItem(text = { Text(stringResource(R.string.new_group)) }, onClick = { menu = false; naming = true })
                if (hasJoinCodes(state)) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.join_group)) }, onClick = { menu = false; joining = true })
                }
            }
        }
    }

    if (picking) {
        NewChatDialog(state, onDismiss = { picking = false }) { address ->
            picking = false
            open(Peer.Contact(address))
        }
    }
    if (joining) JoinDialog(repository, state, "", onDismiss = { joining = false }, open = open)
    forgetting?.let { c ->
        // As it stands now, not as it was when pressed: what the user is shown is what goes.
        val now = conversations.firstOrNull { it.peer == c.peer } ?: c
        ForgetDialog(repository, now, rememberReport(), onDismiss = { forgetting = null }, done = {})
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

/**
 * One conversation in the list: who, what was said last and by whom, when, and how much is unread.
 * [onLongClick], if any, is pressing and holding it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(state: NodeState, c: Conversation, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val group = c.peer is Peer.Group
    val unread = c.unread > 0
    val press = if (onLongClick == null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() },
            onLongClickLabel = stringResource(R.string.more),
        )
    }
    Row(
        Modifier.fillMaxWidth().then(press).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Avatar(c.name, group = group, size = 50.dp, key = avatarKey(c.peer))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    c.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (group) Tag(stringResource(R.string.group_label))
            }
            c.last?.let { last ->
                Text(
                    previewWithWriter(context, state, last),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            c.last?.let {
                Text(
                    timeText(context, timeOf(it)),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (unread) {
                Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                    Text("${c.unread}")
                }
            }
        }
    }
}

/** A list with nothing in it yet: what will be here, and how to start. */
@Composable
internal fun EmptyState(title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun AskedCard(repository: NodeRepository, state: NodeState, a: Body.Asked) {
    var saving by remember { mutableStateOf(false) }
    val who = Conversations.short(a.address.toString())
    val card = state.records.cards[a.address]
    Card(Modifier.padding(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.asked_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(if (a.why == 2) R.string.asked_no_room else R.string.asked_not_contact, who))
            // The address in an ASKED is proved, and a card from it is signed by it: its name is still only a claim.
            card?.name?.takeIf { it.isNotEmpty() }?.let { Text(stringResource(R.string.asked_card, it)) }
            // As is the name it gave itself in first contact (version 19), shown with its short code above.
            a.name?.takeIf { it.isNotEmpty() }?.let { Text(stringResource(R.string.asked_says, it)) }
            Text(a.address.toString(), style = MaterialTheme.typography.bodySmall)
            Row {
                if (a.why != 2 && a.address !in state.records.contacts) TextButton(onClick = { saving = true }) { Text(stringResource(R.string.save_contact)) }
                TextButton(onClick = { repository.dismissAsked(a) }) { Text(stringResource(R.string.dismiss)) }
            }
        }
    }
    if (saving) {
        val report = rememberReport()
        // The name it gave itself is offered, else its card's: the user keeps or changes it.
        val offered = a.name?.ifEmpty { null } ?: card?.name.orEmpty()
        NameDialog(stringResource(R.string.save_contact), offered, Companion.NAME_MAX, stringResource(R.string.save), onDismiss = { saving = false }) { name ->
            saving = false
            repository.submit(Body.SaveContact(a.address, name)) { o ->
                report(o)
                if (o is Outcome.Answered) repository.dismissAsked(a)
            }
        }
    }
}

/** A node said who its owners are changed: the user may not have, and may want to look. */
@Composable
private fun OwnerChangedCard(repository: NodeRepository, state: NodeState, c: Body.OwnerChanged) {
    val context = LocalContext.current
    Card(Modifier.padding(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.owners_changed_title), style = MaterialTheme.typography.titleSmall)
            Text(ownerChangedText(context, state.records, c))
            Text(stringResource(R.string.owners_changed_hint), style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { repository.dismissOwnerChange(c) }) { Text(stringResource(R.string.dismiss)) }
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

/**
 * A conversation to start: with an address or link pasted, typed or scanned, or with a saved
 * contact. The node sends to any address, making first contact with one that is not a contact; the
 * other node may turn it away unless this one is its contact.
 */
@Composable
private fun NewChatDialog(state: NodeState, onDismiss: () -> Unit, picked: (org.ternmesh.companion.Address) -> Unit) {
    val contacts = state.records.contacts.values.sortedBy { it.name.lowercase() }
    var text by remember { mutableStateOf("") }
    val say = LocalSay.current
    val context = LocalContext.current
    // Pasted text comes with a line break or a space at either end, which is no part of it.
    val read = Sharing.read(text.trim())
    val own = read != null && read == state.records.self?.address
    val address = read?.takeUnless { own }
    val scan = rememberScanner { found ->
        if (Sharing.read(found.trim()) != null) text = found.trim() else say(context.getString(R.string.scan_not_tern))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_chat)) },
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
                        own -> ({ Text(stringResource(R.string.address_own)) })
                        text.isNotBlank() -> ({ Text(stringResource(R.string.address_invalid)) })
                        else -> null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (contacts.isNotEmpty()) {
                    Text(stringResource(R.string.or_contact), style = MaterialTheme.typography.titleSmall)
                    LazyColumn {
                        items(contacts, key = { it.address.toString() }) { c ->
                            ListItem(
                                headlineContent = { Text(c.name.ifEmpty { Conversations.short(c.address.toString()) }) },
                                modifier = Modifier.clickable { picked(c.address) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { address?.let(picked) }, enabled = address != null) { Text(stringResource(R.string.open_chat)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Asks for a name of at most [limit] UTF-8 bytes. */
@Composable
fun NameDialog(
    title: String,
    initial: String,
    limit: Int,
    confirm: String,
    onDismiss: () -> Unit,
    explained: String? = null,
    done: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (utf8Length(it) <= limit) text = it },
                    label = { Text(stringResource(R.string.name)) },
                    singleLine = true,
                )
                explained?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { done(text.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Whether [c] has [query] in its name or in anything said in it, ignoring case. */
private fun matches(c: Conversation, query: String): Boolean {
    if (query.isEmpty() || c.name.contains(query, ignoreCase = true)) return true
    return c.items.any { item ->
        when (item) {
            is Body.Message -> item.text.contains(query, ignoreCase = true)
            is Body.GroupMessage -> item.text.contains(query, ignoreCase = true)
            is Body.Invite -> item.name.contains(query, ignoreCase = true)
            else -> false
        }
    }
}

/**
 * The last thing said, with who said it in a group: "You:" for the user, or the writer's name as
 * claimed, as the conversation shows it.
 */
private fun previewWithWriter(context: android.content.Context, state: NodeState, item: Item): String {
    val text = preview(context, item)
    if (item is Body.Message && item.state != org.ternmesh.companion.MessageState.RECEIVED) return context.getString(R.string.preview_you, text)
    if (item !is Body.GroupMessage) return text
    if (item.state != org.ternmesh.companion.MessageState.RECEIVED) return context.getString(R.string.preview_you, text)
    // A name it matches is what the writer claimed, and said to be (draft/groups.md, Receiving).
    val name = Conversations.nameOf(state.records, item.from)?.let { context.getString(R.string.writer_claimed, it) }
        ?: "%08x".format(item.from)
    return context.getString(R.string.preview_writer, name, text)
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
