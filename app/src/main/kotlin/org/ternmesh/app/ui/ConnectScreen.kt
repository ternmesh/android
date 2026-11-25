// Choosing a node: a head card saying what stands in the way, permission to use Bluetooth,
// Bluetooth on, or the location setting, or else how the scan goes; then the nodes used before, and
// the nodes advertising Tern's service nearby. Picking one connects, and Android asks for its passkey
// the first time.
package org.ternmesh.app.ui

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ternmesh.app.R
import org.ternmesh.app.link.BleLink
import org.ternmesh.app.link.Scanner
import org.ternmesh.app.node.ChosenNode
import org.ternmesh.app.node.NodeRepository
import org.ternmesh.app.node.NodeState

@Composable
fun ConnectScreen(repository: NodeRepository, state: NodeState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var permitted by remember { mutableStateOf(MainActivity.hasBluetoothPermissions(context)) }
    var on by remember { mutableStateOf(BleLink.isOn(context)) }
    val scanner = remember { Scanner(context) }
    val found by scanner.found.collectAsStateWithLifecycle()
    val scanning by scanner.scanning.collectAsStateWithLifecycle()
    var forgetting by remember { mutableStateOf<ChosenNode?>(null) }

    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        on = BleLink.isOn(context)
        if (on) scanner.start()
    }
    // Android 11 and earlier report no scan results while the phone's location setting is off.
    var located by remember { mutableStateOf(locationReady(context)) }
    // Scans only while the screen is in front: a low-latency scan left running costs battery.
    LifecycleResumeEffect(permitted, on, located) {
        permitted = MainActivity.hasBluetoothPermissions(context)
        located = locationReady(context)
        on = BleLink.isOn(context)
        if (permitted && on && located) scanner.start()
        onPauseOrDispose { scanner.stop() }
    }

    val known = state.known.map { it.address }.toSet()
    // The nodes used before need no scan, so they are offered whatever stops one.
    val canScan = permitted && on && located
    val nearby = if (canScan) found.filter { it.address !in known } else emptyList()
    val extra = LocalTernColors.current

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        TabTitle(stringResource(R.string.connect_title), state, toNode = null)
        HeroCard {
            when {
                !permitted -> PermissionAsk {
                    permitted = true
                    if (BleLink.isOn(context)) scanner.start()
                }
                !on -> {
                    HeroHeading(TernIcons.Radio, stringResource(R.string.connect_bluetooth_off), stringResource(R.string.connect_bluetooth_off_explained))
                    HeroButton(TernIcons.Power, stringResource(R.string.connect_turn_on), Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        enable.launch(BleLink.enableIntent())
                    }
                }
                !located -> {
                    HeroHeading(TernIcons.Pin, stringResource(R.string.connect_location_off), stringResource(R.string.connect_location))
                    HeroButton(TernIcons.Pin, stringResource(R.string.connect_turn_on_location), Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
                }
                else -> {
                    HeroHeading(
                        TernIcons.Wave,
                        stringResource(if (scanning) R.string.connect_looking else R.string.connect_not_looking),
                        if (found.isEmpty()) stringResource(R.string.connect_none)
                        else pluralStringResource(R.plurals.connect_found, found.size, found.size),
                    )
                    if (scanning) {
                        LinearProgressIndicator(
                            Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(2.dp)),
                            color = extra.onHero,
                            trackColor = extra.onHero.copy(alpha = 0.25f),
                        )
                    }
                    if (scanning) {
                        HeroButton(null, stringResource(R.string.connect_stop), Modifier.fillMaxWidth().padding(top = 16.dp), onClick = scanner::stop)
                    } else {
                        HeroButton(TernIcons.Search, stringResource(R.string.connect_scan), Modifier.fillMaxWidth().padding(top = 16.dp), onClick = scanner::start)
                    }
                }
            }
        }
        state.problem?.let {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(problemText(context, it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
            }
        }

        if (state.known.isNotEmpty()) {
            SectionHeader(stringResource(R.string.known_nodes))
            SettingsGroup {
                state.known.forEachIndexed { i, node ->
                    if (i > 0) GroupDivider()
                    val here = canScan && found.any { it.address == node.address }
                    SettingsRow(
                        node.shown ?: stringResource(R.string.unnamed_device),
                        icon = TernIcons.Node, tint = extra.heroTop,
                        subtitle = if (here) stringResource(R.string.known_nearby) else node.address,
                        chevron = false,
                        onClick = {
                            scanner.stop()
                            repository.choose(node)
                        },
                        trailing = {
                            IconButton(onClick = { forgetting = node }) {
                                Icon(TernIcons.Trash, stringResource(R.string.forget_node), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                    )
                }
            }
        }
        if (nearby.isNotEmpty()) {
            SectionHeader(stringResource(if (state.known.isEmpty()) R.string.connect_found_title else R.string.nearby_nodes))
            SettingsGroup {
                nearby.forEachIndexed { i, f ->
                    if (i > 0) GroupDivider()
                    SettingsRow(
                        f.name ?: stringResource(R.string.unnamed_device),
                        icon = TernIcons.Wave, tint = NearbyTint,
                        subtitle = f.address,
                        value = stringResource(R.string.signal_dbm, f.rssi),
                        onClick = {
                            scanner.stop()
                            repository.choose(ChosenNode(f.address, f.name))
                        },
                    )
                }
            }
        }
        // Android asks for the PIN on first connecting; a node without a screen has nowhere to show it.
        if (permitted) Footnote(stringResource(R.string.connect_pin_hint), Modifier.padding(top = 10.dp))
    }
    forgetting?.let { node -> ForgetDialog(node, onDismiss = { forgetting = null }) { repository.forget(node.address) } }
    state.wiped?.let { WipedDialog(it, repository::dismissWiped) }
}

/** The colour of a node heard nearby, as the dashboard's nearby nodes have. */
private val NearbyTint = Color(0xFF0F766E)

/** The head of a [HeroCard] that asks for something: an icon on a tile, what is wanted, and why. */
@Composable
private fun HeroHeading(icon: ImageVector, title: String, text: String) {
    val on = LocalTernColors.current.onHero
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(on.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = on, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = on)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = on.copy(alpha = 0.85f), modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Whether a scan can find anything: on Android 11 and earlier, only with the location setting on. */
private fun locationReady(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ||
        context.getSystemService(LocationManager::class.java)?.let(LocationManagerCompat::isLocationEnabled) == true

/** Asks for Bluetooth permission alone, for a node already chosen; [granted] once it is given. */
@Composable
fun PermissionPrompt(modifier: Modifier = Modifier, granted: () -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp)) {
        HeroCard { PermissionAsk(granted) }
    }
}

/**
 * Why the app needs Bluetooth permission, and a button to give it, on a [HeroCard]. Once Android
 * has been asked and the permission is still missing, it may not ask again (after two refusals it
 * answers for the user), so the app's own settings page is offered beside it.
 */
@Composable
private fun PermissionAsk(granted: () -> Unit) {
    val context = LocalContext.current
    val on = LocalTernColors.current.onHero
    var refused by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (MainActivity.hasBluetoothPermissions(context)) granted() else refused = true
    }
    HeroHeading(TernIcons.Lock, stringResource(R.string.connect_permission_title), stringResource(R.string.connect_permissions))
    HeroButton(null, stringResource(R.string.connect_grant), Modifier.fillMaxWidth().padding(top = 16.dp)) {
        ask.launch(MainActivity.bluetoothPermissions + MainActivity.notificationPermissions)
    }
    if (refused) {
        Text(
            stringResource(R.string.connect_permissions_settings),
            style = MaterialTheme.typography.bodySmall,
            color = on.copy(alpha = 0.85f),
            modifier = Modifier.padding(top = 14.dp),
        )
        HeroButton(null, stringResource(R.string.connect_open_settings), Modifier.fillMaxWidth().padding(top = 8.dp)) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        }
    }
}
