// A group's join code (draft/groups.md in ternmesh/spec): shown, when the user asks, as a QR code
// and a link to send; and joined from, scanned, pasted or opened from a ternmesh.org/G link. The
// node writes the link and reads it. The app holds it only while the dialog that shows it, or the
// one it was given to, is open, and never where it would outlive them.
package org.ternmesh.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.ternmesh.app.R
import org.ternmesh.app.node.NodeRepository
import org.ternmesh.app.node.NodeState
import org.ternmesh.app.node.Phase
import org.ternmesh.companion.Body
import org.ternmesh.companion.GroupId
import org.ternmesh.companion.JoinCode
import org.ternmesh.companion.Outcome
import org.ternmesh.companion.Peer

/** Whether the node speaks join codes: version 7 or later. */
fun hasJoinCodes(state: NodeState) = (state.version ?: 0) >= 7

/**
 * A group's join code, once the user has read what showing it means: the node is asked for it only
 * then. The link is dropped when the dialog closes.
 */
@Composable
fun JoinCodeDialog(repository: NodeRepository, group: GroupId, name: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val say = LocalSay.current
    val report = rememberReport()
    var link by remember { mutableStateOf<String?>(null) }
    val shown = link
    if (shown == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.join_code_warning_title, name)) },
            text = { Text(stringResource(R.string.join_code_warning, name)) },
            confirmButton = {
                TextButton(onClick = {
                    repository.submit(Body.GroupLink(group)) { o ->
                        val answer = (o as? Outcome.Answered)?.body as? Body.Link
                        if (answer != null) link = answer.link else { report(o); onDismiss() }
                    }
                }) { Text(stringResource(R.string.join_code_show)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.join_code)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                QrCode(shown, Modifier.fillMaxWidth())
                Text(stringResource(R.string.join_code_explained, name), style = MaterialTheme.typography.bodySmall)
                SelectionContainer { Text(shown, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                Row {
                    TextButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.join_code_link, name), shown))
                        say(context.getString(R.string.copied))
                    }) { Text(stringResource(R.string.copy)) }
                    TextButton(onClick = { shareText(context, shown) }) { Text(stringResource(R.string.share)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}

/**
 * Joining a group from its join code, scanned, pasted or opened from a link ([initial]). The code
 * is read here only to name the group before the user says to join; the node is handed the link.
 */
@Composable
fun JoinDialog(repository: NodeRepository, state: NodeState, initial: String, onDismiss: () -> Unit, open: (Peer) -> Unit) {
    val context = LocalContext.current
    val say = LocalSay.current
    val report = rememberReport()
    var text by remember { mutableStateOf(initial) }
    // Pasted text comes with a line break or a space at either end, which is no part of it.
    val link = text.trim()
    val code = remember(link) { JoinCode.read(link) }
    val held = code?.let { state.records.groups[it.group] }
    val scan = rememberScanner { found ->
        if (JoinCode.read(found.trim()) != null) text = found.trim() else say(context.getString(R.string.scan_not_join))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.join_group)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.join_group_explained), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = scan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.scan)) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.join_code_field)) },
                    isError = link.isNotEmpty() && code == null,
                    supportingText = when {
                        held != null -> ({ Text(stringResource(R.string.join_code_held, held.name)) })
                        code != null && code.name.isEmpty() -> ({ Text(stringResource(R.string.join_code_unnamed)) })
                        code != null -> ({ Text(stringResource(R.string.join_code_for, code.name)) })
                        link.isNotEmpty() -> ({ Text(stringResource(R.string.join_code_invalid)) })
                        else -> null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            if (held != null) {
                TextButton(onClick = { onDismiss(); open(Peer.Group(held.group)) }) { Text(stringResource(R.string.open_group)) }
            } else {
                TextButton(
                    onClick = {
                        repository.submit(Body.JoinLink(link)) { o ->
                            val made = (o as? Outcome.Answered)?.body as? Body.Made
                            if (made != null) {
                                onDismiss()
                                open(Peer.Group(made.group))
                            } else {
                                report(o)
                            }
                        }
                    },
                    enabled = code != null && state.phase == Phase.READY,
                ) { Text(stringResource(R.string.join)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Offers [text] to whatever the person picks to send it with. */
fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null))
}
