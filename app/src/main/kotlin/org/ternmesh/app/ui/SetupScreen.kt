// The first time the app meets a node: the region it is in, without which it does not transmit at
// all; whether it relays; and the code to give others, with a first contact to add. A node the
// setup has been done for, but whose region has since been cleared, is asked for the region alone.
package org.ternmesh.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.ternmesh.app.R
import org.ternmesh.app.node.NodeRepository
import org.ternmesh.app.node.NodeState
import org.ternmesh.app.node.Phase
import org.ternmesh.companion.Body
import org.ternmesh.companion.Setting
import org.ternmesh.companion.Sharing

private enum class Step { REGION, ROLE, CONTACT }

/** Whether the setup is to be shown for the node as it stands. */
fun needsSetup(state: NodeState): Boolean {
    if (state.phase != Phase.READY) return false
    val self = state.records.self ?: return false
    return !state.setUp || self.region.isEmpty()
}

@Composable
fun SetupScreen(repository: NodeRepository, state: NodeState, modifier: Modifier = Modifier) {
    val self = state.records.self ?: return
    val report = rememberReport()
    // Only the region, for a node set up before: the rest was its owner's choice already.
    val steps = remember(state.setUp) { if (state.setUp) listOf(Step.REGION) else Step.entries.toList() }
    var at by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    val step = steps[at.coerceAtMost(steps.lastIndex)]
    val next: () -> Unit = { if (at < steps.lastIndex) at++ else repository.finishSetup() }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (steps.size > 1) LinearProgressIndicator(progress = { (at + 1f) / steps.size }, modifier = Modifier.fillMaxWidth())
        when (step) {
            Step.REGION -> {
                Text(stringResource(R.string.setup_region_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.setup_region_explained))
                for (region in REGIONS) {
                    Option(
                        selected = self.region == region,
                        title = region,
                        detail = stringResource(if (region == "EU868") R.string.region_eu868 else R.string.region_us915),
                    ) { repository.submit(Body.Set(Setting.Region(region)), report) }
                }
                Text(stringResource(R.string.setup_region_law), style = MaterialTheme.typography.bodySmall)
            }
            Step.ROLE -> {
                Text(stringResource(R.string.setup_role_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.setup_role_explained))
                Option(self.role == 0, stringResource(R.string.role_leaf_short), stringResource(R.string.setup_role_leaf)) {
                    repository.submit(Body.Set(Setting.Role(0)), report)
                }
                Option(self.role == 1, stringResource(R.string.role_relay_short), stringResource(R.string.setup_role_relay)) {
                    repository.submit(Body.Set(Setting.Role(1)), report)
                }
            }
            Step.CONTACT -> {
                Text(stringResource(R.string.setup_contact_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.setup_contact_explained))
                QrCode(self.address, Modifier.fillMaxWidth().align(Alignment.CenterHorizontally))
                Text(
                    Sharing.shortCode(self.address),
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.add_contact))
                }
                if (state.records.contacts.isNotEmpty()) {
                    Text(pluralStringResource(R.plurals.setup_contacts_count, state.records.contacts.size, state.records.contacts.size))
                }
            }
        }
        Spacer(Modifier.weight(1f, fill = false))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            // The region cannot be skipped: until it is set the node sends nothing at all.
            if (step != Step.REGION) TextButton(onClick = repository::finishSetup) { Text(stringResource(R.string.setup_skip)) } else Spacer(Modifier)
            Button(onClick = next, enabled = step != Step.REGION || self.region.isNotEmpty()) {
                Text(stringResource(if (at < steps.lastIndex) R.string.setup_next else R.string.setup_done))
            }
        }
    }

    if (adding) {
        AddContactDialog("", onDismiss = { adding = false }) { address, name ->
            adding = false
            repository.submit(Body.SaveContact(address, name), report)
        }
    }
}

/** One choice of several, with what it means under it. */
@Composable
private fun Option(selected: Boolean, title: String, detail: String, choose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = choose).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
