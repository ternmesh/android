package org.ternmesh.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import org.ternmesh.app.TernApplication
import org.ternmesh.app.node.Notifier
import org.ternmesh.companion.Address
import org.ternmesh.companion.JoinCode
import org.ternmesh.companion.Sharing

class MainActivity : ComponentActivity() {
    /** A conversation a notification asked to open, which the navigation takes and clears. */
    private val opening = mutableStateOf<String?>(null)

    /** An address from a link opened in the app, which the contacts take and clear. */
    private val incoming = mutableStateOf<Address?>(null)

    /** A join code's link opened in the app, held only until its dialog closes. */
    private val joining = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        opening.value = intent.getStringExtra(Notifier.EXTRA_PEER)
        incoming.value = linked(intent)
        joining.value = joinLink(intent)
        val repository = (application as TernApplication).repository
        setContent {
            TernTheme {
                TernApp(
                    repository, opening.value, incoming.value, joining.value,
                    opened = {
                        // Consumed: an activity made again, on rotation, must not open it a second time.
                        opening.value = null
                        intent.removeExtra(Notifier.EXTRA_PEER)
                    },
                    taken = {
                        incoming.value = null
                        intent.data = null
                    },
                    joined = {
                        joining.value = null
                        intent.data = null
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Notifier.EXTRA_PEER)?.let {
            // Kept as the activity's intent, so a conversation still waiting (on permission, say)
            // survives the activity being made again, and is cleared from the intent that has it.
            setIntent(intent)
            opening.value = it
        }
        linked(intent)?.let {
            setIntent(intent)
            incoming.value = it
        }
        joinLink(intent)?.let {
            setIntent(intent)
            joining.value = it
        }
    }

    /** The address in a ternmesh.org link the app was opened with (draft/sharing.md), if any. */
    private fun linked(intent: Intent): Address? =
        intent.takeIf { it.action == Intent.ACTION_VIEW }?.dataString?.let(Sharing::read)

    /** A join code's link the app was opened with (draft/groups.md), if any: the link as it came, for the node to read. */
    private fun joinLink(intent: Intent): String? =
        intent.takeIf { it.action == Intent.ACTION_VIEW }?.dataString?.takeIf { JoinCode.read(it) != null }

    override fun onStart() {
        super.onStart()
        if (hasBluetoothPermissions(this)) (application as TernApplication).repository.resume()
    }

    companion object {
        /** What the app needs to find and talk to a node. */
        val bluetoothPermissions: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        /** Asked for alongside, so messages can be shown while the app is closed. */
        val notificationPermissions: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }

        fun hasBluetoothPermissions(context: android.content.Context) = bluetoothPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}

@Composable
fun TernTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
