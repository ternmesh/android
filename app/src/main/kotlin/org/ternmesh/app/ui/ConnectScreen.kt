// Choosing a node: permission to use Bluetooth, Bluetooth on, then the nodes advertising Tern's
// service nearby. Picking one connects, and Android asks for its passkey the first time.
package org.ternmesh.app.ui

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permitted = MainActivity.hasBluetoothPermissions(context)
        if (permitted && BleLink.isOn(context)) scanner.start()
    }
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        on = BleLink.isOn(context)
        if (on) scanner.start()
    }
    DisposableEffect(permitted, on) {
        if (permitted && on) scanner.start()
        onDispose { scanner.stop() }
    }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.connect_title), style = MaterialTheme.typography.headlineSmall)
        when {
            !permitted -> {
                Text(stringResource(R.string.connect_permissions))
                Button(onClick = { ask.launch(MainActivity.bluetoothPermissions + MainActivity.notificationPermissions) }) {
                    Text(stringResource(R.string.connect_grant))
                }
            }
            !on -> Button(onClick = { enable.launch(BleLink.enableIntent()) }) { Text(stringResource(R.string.connect_turn_on)) }
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
