// A notification for each message, group message and invite the node receives while the user is
// not looking at its conversation: one per conversation, replaced as more arrive.
package org.ternmesh.app.node

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.ternmesh.app.R
import org.ternmesh.app.ui.MainActivity
import org.ternmesh.companion.Body
import org.ternmesh.companion.Conversations
import org.ternmesh.companion.Item
import org.ternmesh.companion.Peer
import org.ternmesh.companion.Records

class Notifier(private val context: Context) {
    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH),
        )
        manager.createNotificationChannel(
            NotificationChannel(LINK, context.getString(R.string.channel_link), NotificationManager.IMPORTANCE_MIN),
        )
    }

    fun notify(records: Records, item: Item) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val peer = Conversations.peerOf(item)
        val title = Conversations.name(records, peer)
        val text = when (item) {
            is Body.Message -> item.text
            is Body.GroupMessage -> item.text
            is Body.Invite -> context.getString(R.string.invite_received, item.name)
            else -> return
        }
        val n = NotificationCompat.Builder(context, MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open(context, peer))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        NotificationManagerCompat.from(context).notify(idOf(peer), n)
    }

    fun cancel(peer: Peer) = NotificationManagerCompat.from(context).cancel(idOf(peer))

    private fun idOf(peer: Peer) = 1000 + (key(peer).hashCode() and 0x3FFF_FFFF)

    companion object {
        const val MESSAGES = "messages"
        const val LINK = "link"
        const val EXTRA_PEER = "peer"

        /** A peer as text, for a route or an intent: `c:` and an address, or `g:` and a group id. */
        fun key(peer: Peer) = when (peer) {
            is Peer.Contact -> "c:${peer.address}"
            is Peer.Group -> "g:${peer.group}"
        }

        fun peer(key: String): Peer? = when {
            key.startsWith("c:") -> org.ternmesh.companion.Address.fromHex(key.drop(2))?.let { Peer.Contact(it) }
            key.startsWith("g:") -> org.ternmesh.companion.GroupId.fromHex(key.drop(2))?.let { Peer.Group(it) }
            else -> null
        }

        /** Opens the app, at [peer]'s conversation if there is one. */
        fun open(context: Context, peer: Peer?): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            peer?.let { intent.putExtra(EXTRA_PEER, key(it)) }
            return PendingIntent.getActivity(
                context, peer?.let { key(it).hashCode() } ?: 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
