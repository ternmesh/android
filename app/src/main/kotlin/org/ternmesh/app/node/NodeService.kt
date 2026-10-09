// A foreground service, so Android keeps the app's process, and with it the link to the node, while
// the app is not on screen. It does nothing itself: the repository holds the link. Its notification
// says where the link is, or how far an update of the node's firmware has gone.
package org.ternmesh.app.node

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.ternmesh.app.R
import org.ternmesh.app.TernApplication
import org.ternmesh.app.ui.phaseText
import org.ternmesh.app.ui.updateNotificationText

class NodeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = (application as TernApplication).repository.state.value
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        runCatching { ServiceCompat.startForeground(this, ID, notification(this, state), type) }
            .onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        private const val ID = 1

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, NodeService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NodeService::class.java))
            // A refresh may have posted the notification again on its own.
            NotificationManagerCompat.from(context).cancel(ID)
        }

        /** Says where the link is now, if the service is showing. */
        fun refresh(context: Context, state: NodeState) {
            if (state.node == null || state.phase == Phase.DISCONNECTED) return
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            runCatching { NotificationManagerCompat.from(context).notify(ID, notification(context, state)) }
        }

        private fun notification(context: Context, state: NodeState) =
            NotificationCompat.Builder(context, Notifier.LINK)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(state.node?.name ?: context.getString(R.string.app_name))
                .setContentText(updateNotificationText(context, state) ?: phaseText(context, state))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(Notifier.open(context, null))
                .build()
    }
}
