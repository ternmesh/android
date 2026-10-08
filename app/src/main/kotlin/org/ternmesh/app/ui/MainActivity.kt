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

class MainActivity : ComponentActivity() {
    /** A conversation a notification asked to open, which the navigation takes and clears. */
    private val opening = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        opening.value = intent.getStringExtra(Notifier.EXTRA_PEER)
        val repository = (application as TernApplication).repository
        setContent {
            TernTheme {
                TernApp(repository, opening.value) {
                    // Consumed: an activity made again, on rotation, must not open it a second time.
                    opening.value = null
                    intent.removeExtra(Notifier.EXTRA_PEER)
                }
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
    }

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
