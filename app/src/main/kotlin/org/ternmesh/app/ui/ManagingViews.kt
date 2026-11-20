// The check of whether this node owns another, and the banner that says a node managed over the
// mesh is still being asked: both drawn from the repository's state, which outlives the activity
// and the screen that asked, so that a check, or a request, that takes a sleeping node's wake to
// answer is still shown, and its outcome, when the user comes back.
package org.ternmesh.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ternmesh.app.R
import org.ternmesh.app.node.Managed
import org.ternmesh.app.node.NodeState
import org.ternmesh.companion.Address

/**
 * Whether the app's node is an owner of the node at [address]: one `HELLO` over the mesh, which only
 * an owner answers. An answer means it is, and [manage] opens Manage from here; `ERROR` 15 that it
 * is not; and no answer says nothing, since a node may be asleep or out of reach, or say nothing to
 * anyone else. Closed any other way than [cancel], with [hide], the check goes on.
 */
@Composable
internal fun CheckOwnerDialog(
    state: NodeState,
    address: Address,
    hide: () -> Unit,
    cancel: () -> Unit,
    retry: () -> Unit,
    manage: () -> Unit,
) {
    val context = LocalContext.current
    val name = nodeName(context, state.records, address)
    val m = state.managed?.takeIf { it.address == address }
    val waiting = m != null && (m.asking != null || m.settleUntil != null)
    AlertDialog(
        onDismissRequest = hide,
        title = { Text(stringResource(R.string.check_owner_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    m?.owner == true -> Text(stringResource(R.string.check_owner_yes, name))
                    m?.failed != null && !waiting -> Text(manageOutcomeText(context, name, m.failed), color = MaterialTheme.colorScheme.error)
                    else -> {
                        Text(stringResource(R.string.check_owner_explained, name))
                        // The dialog's own Cancel stops it.
                        if (m != null) ManageProgress(m, name, stop = null)
                    }
                }
            }
        },
        confirmButton = {
            when {
                m?.owner == true -> TextButton(onClick = manage) { Text(stringResource(R.string.manage)) }
                // Told it is not an owner, asking again comes to the same.
                m?.failed != null && !waiting && m.owner != false -> TextButton(onClick = retry) { Text(stringResource(R.string.retry)) }
                else -> {}
            }
        },
        dismissButton = { TextButton(onClick = cancel) { Text(stringResource(if (waiting) R.string.cancel else R.string.done)) } },
    )
}

/**
 * A node managed over the mesh whose dialog or screen is not open: a check still going on, or ended
 * with an outcome the user has not seen, or a request still on its way after the Manage screen was
 * left. [open] opens it again. Nothing when there is none.
 */
@Composable
internal fun ManagingBanner(state: NodeState, open: (Managed) -> Unit) {
    val m = state.managed ?: return
    val context = LocalContext.current
    val running = managingText(context, state)
    val text = when {
        m.checking && m.dialog -> null
        running != null -> running
        m.checking -> checkResultText(context, nodeName(context, state.records, m.address), m.owner, m.failed)
        else -> null
    } ?: return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().clickable { open(m) }) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (running != null) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}
