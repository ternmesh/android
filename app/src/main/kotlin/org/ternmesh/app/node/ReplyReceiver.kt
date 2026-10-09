// A reply written in a message notification: sent to its conversation, as one written in the app.
package org.ternmesh.app.node

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import org.ternmesh.app.TernApplication

class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val peer = intent.getStringExtra(Notifier.EXTRA_PEER)?.let(Notifier::peer) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString() ?: return
        (context.applicationContext as TernApplication).repository.reply(peer, text)
    }
}
