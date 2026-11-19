// One conversation: its messages and invites oldest first, where each sent one is and, while it
// waits, what for; and a box to write in that counts what the protocol counts, UTF-8 bytes.
package org.ternmesh.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
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
    val canShare = hasPositions(state) && when (peer) {
        is Peer.Contact -> state.records.contacts[peer.address] != null
        is Peer.Group -> state.records.groups[peer.group] != null
    }

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
            title = { ChatTitle(state, peer, conversation.name) },
            navigationIcon = {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            },
            actions = {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (canShare) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.share_location)) }, onClick = { menu = false; dialog = ChatDialog.SHARE })
                    }
                    when (peer) {
                        is Peer.Group -> {
                            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; dialog = ChatDialog.RENAME })
                            DropdownMenuItem(text = { Text(stringResource(R.string.invite)) }, onClick = { menu = false; dialog = ChatDialog.INVITE })
                            if (hasJoinCodes(state) && state.records.groups[peer.group] != null) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.join_code)) }, onClick = { menu = false; dialog = ChatDialog.JOIN_CODE })
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.leave)) }, onClick = { menu = false; dialog = ChatDialog.LEAVE })
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
        SharingLine(state, peer) { dialog = ChatDialog.SHARE }
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
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
        Composer(enabled = state.phase == Phase.READY, share = if (canShare) ({ dialog = ChatDialog.SHARE }) else null) { text ->
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
        ChatDialog.LEAVE -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.leave_title, conversation.name)) },
            text = { Text(stringResource(R.string.leave_explained)) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    if (peer is Peer.Group) {
                        repository.submit(Body.LeaveGroup(peer.group)) { o -> report(o); if (o is Outcome.Answered) back() }
                    }
                }) { Text(stringResource(R.string.leave)) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
        )
        ChatDialog.SHARE -> ShareDialog(repository, state, peer) { dialog = null }
        ChatDialog.JOIN_CODE -> if (peer is Peer.Group) JoinCodeDialog(repository, peer.group, conversation.name) { dialog = null }
        null -> {}
    }
}

private enum class ChatDialog { RENAME, INVITE, END_SESSION, LEAVE, SHARE, JOIN_CODE }

/** Who the conversation is with, and in a word how: secure, not yet talked to, or a group. */
@Composable
private fun ChatTitle(state: NodeState, peer: Peer, name: String) {
    val extra = LocalTernColors.current
    val (line, color) = when (peer) {
        is Peer.Group -> stringResource(R.string.group_label) to MaterialTheme.colorScheme.onSurfaceVariant
        is Peer.Contact -> when (state.records.contacts[peer.address]?.session) {
            1 -> stringResource(R.string.session_yes) to extra.good
            0 -> stringResource(R.string.session_no) to MaterialTheme.colorScheme.onSurfaceVariant
            else -> stringResource(R.string.chat_not_contact) to MaterialTheme.colorScheme.onSurfaceVariant
        }.let { (text, color) ->
            // A contact that said it sleeps (version 15): messages to it wait for it to wake.
            val wake = state.records.contacts[peer.address]?.wake ?: 0
            if (wake == 0) text to color else text + " · " + stringResource(R.string.contact_sleeps, spanText(wake.toLong())) to color
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(name, group = peer is Peer.Group, size = 36.dp, key = avatarKey(peer))
        Column(Modifier.padding(start = 12.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
        }
    }
}

@Composable
private fun Bubble(repository: NodeRepository, state: NodeState, item: Item) {
    val context = LocalContext.current
    val mine = item.state != MessageState.RECEIVED
    val report = rememberReport()
    // The corner nearest the sender is tighter, so that a bubble points at who said it.
    val shape = if (mine) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    val container = if (mine) LocalTernColors.current.mine else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (mine) LocalTernColors.current.onMine else MaterialTheme.colorScheme.onSurface
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(shape = shape, color = container, contentColor = content, modifier = Modifier.widthIn(max = 300.dp)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                if (item is Body.GroupMessage && !mine) {
                    // The writer's routing id is what it claimed, not a proof: a contact's name it
                    // matches is said to be claimed.
                    val name = Conversations.nameOf(state.records, item.from)
                    Text(
                        name?.let { stringResource(R.string.writer_claimed, it) } ?: "%08x".format(item.from),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                when (item) {
                    is Body.Message -> Text(item.text, style = MaterialTheme.typography.bodyLarge)
                    is Body.GroupMessage -> Text(item.text, style = MaterialTheme.typography.bodyLarge)
                    is Body.Invite -> {
                        Text(stringResource(R.string.invite_to, item.name), style = MaterialTheme.typography.titleSmall)
                        if (!mine && item.group !in state.records.groups) {
                            TextButton(onClick = { repository.submit(Body.Join(item.id), report) }) { Text(stringResource(R.string.join)) }
                        }
                    }
                }
                // Sent to a contact that sleeps: its keeper holds it until the contact wakes.
                val held = item is Body.Message && item.state == MessageState.SENT &&
                    (state.records.contacts[item.contact]?.wake ?: 0) != 0
                val status = listOfNotNull(
                    timeText(context, timeOf(item)).ifEmpty { null },
                    if (held) stringResource(R.string.state_held) else stateText(context, item),
                )
                if (status.isNotEmpty()) {
                    Text(
                        status.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalContentColor.current.copy(alpha = 0.72f),
                        modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Composer(enabled: Boolean, share: (() -> Unit)?, send: (String) -> Boolean) {
    var text by rememberSaveable { mutableStateOf("") }
    val left = Companion.TEXT_MAX - utf8Length(text)
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (share != null) {
                    IconButton(onClick = share) {
                        Icon(TernIcons.Pin, stringResource(R.string.share_location), tint = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    Spacer(Modifier.width(8.dp))
                }
                TextField(
                    value = text,
                    onValueChange = { if (utf8Length(it) <= Companion.TEXT_MAX) text = it },
                    placeholder = { Text(stringResource(R.string.message_hint)) },
                    supportingText = if (left < 32) ({ Text(pluralStringResource(R.plurals.bytes_left, left, left)) }) else null,
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier.weight(1f),
                    maxLines = 5,
                )
                val can = enabled && text.isNotBlank()
                FilledIconButton(
                    onClick = { if (send(text.trim())) text = "" },
                    enabled = can,
                    modifier = Modifier.padding(start = 8.dp).size(44.dp),
                ) { Icon(TernIcons.Send, stringResource(R.string.send)) }
            }
        }
    }
}
