// One conversation: its messages and invites oldest first, where each sent one is and, while it
// waits, what for; and a box to write in that counts what the protocol counts, UTF-8 bytes.
package org.ternmesh.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.ternmesh.app.R
import org.ternmesh.app.node.NodeRepository
import org.ternmesh.app.node.NodeState
import org.ternmesh.app.node.Phase
import org.ternmesh.companion.Body
import org.ternmesh.companion.Companion
import org.ternmesh.companion.Conversations
import org.ternmesh.companion.Item
import org.ternmesh.companion.MessageState
import org.ternmesh.companion.Outcome
import org.ternmesh.companion.Peer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(repository: NodeRepository, state: NodeState, peer: Peer, back: () -> Unit) {
    val conversation = remember(state.records, peer) { Conversations.of(state.records, peer) }
    val list = rememberLazyListState()
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<ChatDialog?>(null) }
    val report = rememberReport()

    // While this is on screen its new messages are read as they come, and need no notification.
    // Only while the chat is resumed: covered by a dialog or another app, it is not being read.
    LifecycleResumeEffect(peer) {
        repository.viewing = peer
        repository.markRead(peer)
        onPauseOrDispose { if (repository.viewing == peer) repository.viewing = null }
    }
    LaunchedEffect(conversation.unread, state.phase) {
        if (conversation.unread > 0 && repository.viewing == peer) repository.markRead(peer)
    }
    LaunchedEffect(conversation.items.size) {
        if (conversation.items.isNotEmpty()) list.animateScrollToItem(conversation.items.size - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text(conversation.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            },
            actions = {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    when (peer) {
                        is Peer.Group -> {
                            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; dialog = ChatDialog.RENAME })
                            DropdownMenuItem(text = { Text(stringResource(R.string.invite)) }, onClick = { menu = false; dialog = ChatDialog.INVITE })
                            DropdownMenuItem(text = { Text(stringResource(R.string.leave)) }, onClick = {
                                menu = false
                                repository.submit(Body.LeaveGroup(peer.group)) { o -> report(o); if (o is Outcome.Answered) back() }
                            })
                        }
                        is Peer.Contact -> {
                            val saved = state.records.contacts[peer.address]
                            DropdownMenuItem(
                                text = { Text(stringResource(if (saved == null) R.string.save_contact else R.string.rename)) },
                                onClick = { menu = false; dialog = ChatDialog.RENAME },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.end_session)) },
                                onClick = { menu = false; dialog = ChatDialog.END_SESSION },
                            )
                        }
                    }
                }
            },
        )
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(conversation.items, key = { it.id }) { item -> Bubble(repository, state, item) }
            items(state.unanswered.filter { it.peer == peer }, key = { "u${it.ref}" }) { u ->
                Card(Modifier.padding(horizontal = 12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(u.text)
                        Text(stringResource(R.string.not_answered), style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { repository.resend(u, report) }, enabled = state.phase == Phase.READY) { Text(stringResource(R.string.send_again)) }
                            TextButton(onClick = { repository.dismissUnanswered(u) }) { Text(stringResource(R.string.dismiss)) }
                        }
                    }
                }
            }
        }
        val say = LocalSay.current
        val still = stringResource(R.string.still_sending)
        Composer(enabled = state.phase == Phase.READY) { text ->
            repository.write(peer, text, report).also { taken -> if (!taken) say(still) }
        }
    }

    when (dialog) {
        ChatDialog.RENAME -> {
            val current = when (peer) {
                is Peer.Group -> state.records.groups[peer.group]?.name
                is Peer.Contact -> state.records.contacts[peer.address]?.name
            }.orEmpty()
            NameDialog(stringResource(R.string.rename), current, Companion.NAME_MAX, stringResource(R.string.save), onDismiss = { dialog = null }) { name ->
                dialog = null
                repository.submit(
                    when (peer) {
                        is Peer.Group -> Body.NameGroup(peer.group, name)
                        is Peer.Contact -> Body.SaveContact(peer.address, name)
                    },
                    report,
                )
            }
        }
        ChatDialog.INVITE -> ContactPicker(state, onDismiss = { dialog = null }) { address ->
            dialog = null
            if (peer is Peer.Group) repository.submit(Body.SendInvite(peer.group, address), report)
        }
        ChatDialog.END_SESSION -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.end_session)) },
            text = { Text(stringResource(R.string.end_session_explained)) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    if (peer is Peer.Contact) repository.submit(Body.EndSession(peer.address), report)
                }) { Text(stringResource(R.string.end_session)) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
        )
        null -> {}
    }
}

private enum class ChatDialog { RENAME, INVITE, END_SESSION }

@Composable
private fun Bubble(repository: NodeRepository, state: NodeState, item: Item) {
    val context = LocalContext.current
    val mine = item.state != MessageState.RECEIVED
    val report = rememberReport()
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Card(
            colors = if (mine) {
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            } else {
                CardDefaults.cardColors()
            },
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(10.dp)) {
                if (item is Body.GroupMessage && !mine) {
                    // The writer's routing id is what it claimed, not a proof.
                    Text("%08x".format(item.from), style = MaterialTheme.typography.labelSmall)
                }
                when (item) {
                    is Body.Message -> Text(item.text)
                    is Body.GroupMessage -> Text(item.text)
                    is Body.Invite -> {
                        Text(stringResource(R.string.invite_to, item.name), style = MaterialTheme.typography.titleSmall)
                        if (!mine && item.group !in state.records.groups) {
                            TextButton(onClick = { repository.submit(Body.Join(item.id), report) }) { Text(stringResource(R.string.join)) }
                        }
                    }
                }
                val status = listOfNotNull(timeText(context, timeOf(item)).ifEmpty { null }, stateText(context, item))
                if (status.isNotEmpty()) Text(status.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun Composer(enabled: Boolean, send: (String) -> Boolean) {
    var text by rememberSaveable { mutableStateOf("") }
    val left = Companion.TEXT_MAX - utf8Length(text)
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { if (utf8Length(it) <= Companion.TEXT_MAX) text = it },
            placeholder = { Text(stringResource(R.string.message_hint)) },
            supportingText = if (left < 32) ({ Text(pluralStringResource(R.plurals.bytes_left, left, left)) }) else null,
            modifier = Modifier.weight(1f),
            maxLines = 5,
        )
        IconButton(
            onClick = {
                if (send(text.trim())) text = ""
            },
            enabled = enabled && text.isNotBlank(),
        ) { Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send)) }
    }
}
