// The first time the app meets a node: the region it is in, without which it does not transmit at
// all; whether it relays; and the code to give others, with a first contact to add. A node the
// setup has been done for, but whose region has since been cleared, is asked for the region alone.
//
// The region and the role are each a setting the node restarts to apply, which drops the
// connection: so they are held here as the user chooses, and sent together when the setup ends,
// for the node to apply with one restart rather than one in the middle of each step.
package org.ternmesh.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ternmesh.app.R
import org.ternmesh.app.node.NodeRepository
import org.ternmesh.app.node.NodeState
import org.ternmesh.app.node.Phase
import org.ternmesh.companion.Body
import org.ternmesh.companion.Setting

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
    // What the user chose, not yet sent: null for what the node has already.
    var region by remember { mutableStateOf<String?>(null) }
    var role by remember { mutableStateOf<Int?>(null) }
    val chosenRegion = region ?: self.region
    val chosenRole = role ?: self.role
    // Sends what was chosen, the region first, each only if it is not what the node has already,
    // so a setup that changes nothing restarts nothing; and ends the setup.
    val finish: () -> Unit = {
        region?.takeIf { it != self.region }?.let { repository.submit(Body.Set(Setting.Region(it)), report) }
        role?.takeIf { it != self.role }?.let { repository.submit(Body.Set(Setting.Role(it)), report) }
        repository.finishSetup()
    }
    val step = steps[at.coerceAtMost(steps.lastIndex)]
    val next: () -> Unit = { if (at < steps.lastIndex) at++ else finish() }

    val context = LocalContext.current
    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp)) {
                if (steps.size > 1) {
                    Text(
                        stringResource(R.string.setup_step, at + 1, steps.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    LinearProgressIndicator(
                        progress = { (at + 1f) / steps.size },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(2.dp)),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        drawStopIndicator = {},
                    )
                }
                Text(
                    stringResource(
                        when (step) {
                            Step.REGION -> R.string.setup_region_title
                            Step.ROLE -> R.string.setup_role_title
                            Step.CONTACT -> R.string.setup_contact_title
                        },
                    ),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    stringResource(
                        when (step) {
                            Step.REGION -> R.string.setup_region_explained
                            Step.ROLE -> R.string.setup_role_explained
                            Step.CONTACT -> R.string.setup_contact_explained
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                )
            }
            when (step) {
                Step.REGION -> {
                    SettingsGroup {
                        REGIONS.forEachIndexed { i, (name, detail) ->
                            if (i > 0) GroupDivider()
                            ChoiceRow(name, stringResource(detail), selected = chosenRegion == name) { region = name }
                        }
                    }
                    Footnote(stringResource(R.string.setup_region_law), Modifier.padding(top = 4.dp))
                }
                Step.ROLE -> SettingsGroup {
                    RoleChoice(stringResource(R.string.role_leaf_title), stringResource(R.string.role_leaf_short), stringResource(R.string.setup_role_leaf), chosenRole == 0) {
                        role = 0
                    }
                    GroupDivider()
                    RoleChoice(stringResource(R.string.role_relay_title), stringResource(R.string.role_relay_short), stringResource(R.string.setup_role_relay), chosenRole == 1) {
                        role = 1
                    }
                }
                Step.CONTACT -> {
                    AddressHero(state.node?.shown ?: stringResource(R.string.unnamed_device), self.address) {
                        HeroButton(TernIcons.Share, stringResource(R.string.share_link), Modifier.weight(1f)) { shareLink(context, self.address) }
                        HeroButton(TernIcons.People, stringResource(R.string.add_contact), Modifier.weight(1f)) { adding = true }
                    }
                    if (state.records.contacts.isNotEmpty()) {
                        Footnote(
                            pluralStringResource(R.plurals.setup_contacts_count, state.records.contacts.size, state.records.contacts.size),
                            Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        // Where to go next, kept in reach under the list rather than at the end of it.
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The region cannot be skipped: until it is set the node sends nothing at all.
            if (step != Step.REGION) TextButton(onClick = finish) { Text(stringResource(R.string.setup_skip)) }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = next,
                enabled = step != Step.REGION || chosenRegion.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp),
            ) {
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
