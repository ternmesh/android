// A notification for each message, group message and invite the node receives while the user is
// not looking at its conversation: one per conversation, replaced as more arrive, with a reply that
// goes to the conversation without opening the app. And one when a node says who its owners are
// changed (version 17), since the user may not have changed them.
package org.ternmesh.app.node

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import org.ternmesh.app.R
import org.ternmesh.app.ui.MainActivity
import org.ternmesh.app.ui.ownerChangedText
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
        manager.createNotificationChannel(
            NotificationChannel(OWNERS, context.getString(R.string.channel_owners), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** The node at the notice's `from` said who may manage it changed: one notification for each such node, replaced by the next. */
    fun ownerChanged(records: Records, notice: Body.OwnerChanged) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val text = ownerChangedText(context, records, notice)
        val n = NotificationCompat.Builder(context, OWNERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.owners_changed_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open(context, null))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        NotificationManagerCompat.from(context).notify(2000 + (notice.from.hashCode() and 0x3FFF_FFFF) % 100_000, n)
    }

    fun notify(records: Records, item: Item) {
        val peer = Conversations.peerOf(item)
        val text = when (item) {
            is Body.Message -> item.text
            is Body.GroupMessage -> item.text
            is Body.Invite -> context.getString(R.string.invite_received, item.name)
            else -> return
        }
        post(records, peer, text, quietly = false)
    }

    /**
     * Puts [text], what came of a reply written in [peer]'s notification, in its place. Android shows
     * the reply as sending until the notification is posted again.
     */
    fun replied(records: Records, peer: Peer, text: String) = post(records, peer, text, quietly = true)

    private fun post(records: Records, peer: Peer, text: String, quietly: Boolean) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val n = NotificationCompat.Builder(context, MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(Conversations.name(records, peer))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setSilent(quietly)
            .setContentIntent(open(context, peer))
            .addAction(replyAction(peer))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        NotificationManagerCompat.from(context).notify(idOf(peer), n)
    }

    /** A reply written in the notification, which [ReplyReceiver] sends to [peer]. */
    private fun replyAction(peer: Peer): NotificationCompat.Action {
        val input = RemoteInput.Builder(KEY_REPLY).setLabel(context.getString(R.string.reply)).build()
        val intent = Intent(context, ReplyReceiver::class.java).putExtra(EXTRA_PEER, key(peer))
        // Mutable, as a reply needs: Android adds what was written to the intent. The intent names
        // the receiver, so nothing else can be given it.
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(
            context, key(peer).hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable,
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_notification, context.getString(R.string.reply), pending)
            .addRemoteInput(input)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    fun cancel(peer: Peer) = NotificationManagerCompat.from(context).cancel(idOf(peer))

    fun cancelAll() = NotificationManagerCompat.from(context).cancelAll()

    private fun idOf(peer: Peer) = 1000 + (key(peer).hashCode() and 0x3FFF_FFFF)

    companion object {
        const val MESSAGES = "messages"
        const val LINK = "link"
        const val OWNERS = "owners"
        const val EXTRA_PEER = "peer"
        const val KEY_REPLY = "reply"

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
