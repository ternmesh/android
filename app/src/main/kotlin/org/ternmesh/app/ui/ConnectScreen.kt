// Choosing a node: permission to use Bluetooth, Bluetooth on, then the nodes advertising Tern's
// service nearby. Picking one connects, and Android asks for its passkey the first time.
package org.ternmesh.app.ui

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.connect_title), style = MaterialTheme.typography.headlineSmall)
        when {
            !permitted -> PermissionAsk {
                permitted = true
                if (BleLink.isOn(context)) scanner.start()
            }
            !on -> Button(onClick = { enable.launch(BleLink.enableIntent()) }) { Text(stringResource(R.string.connect_turn_on)) }
            !located -> {
                Text(stringResource(R.string.connect_location))
                Button(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) {
                    Text(stringResource(R.string.connect_turn_on_location))
                }
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (scanning) {
                        LinearProgressIndicator(Modifier.weight(1f))
                        OutlinedButton(onClick = scanner::stop, Modifier.padding(start = 12.dp)) { Text(stringResource(R.string.connect_stop)) }
                    } else {
                        Button(onClick = scanner::start) { Text(stringResource(R.string.connect_scan)) }
                    }
                }
                state.problem?.let { Text(problemText(context, it), color = MaterialTheme.colorScheme.error) }
                if (found.isEmpty()) {
                    Text(stringResource(R.string.connect_none), style = MaterialTheme.typography.bodyMedium)
                }
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(found, key = { it.address }) { f ->
                        ListItem(
                            headlineContent = { Text(f.name ?: stringResource(R.string.unnamed_device)) },
                            supportingContent = { Text(f.address) },
                            trailingContent = { Text(stringResource(R.string.signal_dbm, f.rssi)) },
                            modifier = Modifier.clickable {
                                scanner.stop()
                                repository.choose(ChosenNode(f.address, f.name))
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
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
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PermissionAsk(granted)
    }
}

/**
 * Why the app needs Bluetooth permission, and a button to give it. Once Android has been asked and
 * the permission is still missing, it may not ask again (after two refusals it answers for the
 * user), so the app's own settings page is offered beside it.
 */
@Composable
private fun PermissionAsk(granted: () -> Unit) {
    val context = LocalContext.current
    var refused by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (MainActivity.hasBluetoothPermissions(context)) granted() else refused = true
    }
    Text(stringResource(R.string.connect_permissions))
    Button(onClick = { ask.launch(MainActivity.bluetoothPermissions + MainActivity.notificationPermissions) }) {
        Text(stringResource(R.string.connect_grant))
    }
    if (refused) {
        Text(stringResource(R.string.connect_permissions_settings))
        OutlinedButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        }) { Text(stringResource(R.string.connect_open_settings)) }
    }
}
