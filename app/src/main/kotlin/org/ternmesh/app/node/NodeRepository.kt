// The one node the app drives: the Bluetooth link to it, the protocol's connection over that, the
// records on disk, and what the screens are shown of all three.
//
// Everything here runs on the main thread. The link posts its callbacks there, the connection's
// timer runs there, and the screens call in from there.
package org.ternmesh.app.node

import android.content.Context
import android.os.Handler
import android.util.Base64
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.ternmesh.app.link.BleLink
import org.ternmesh.app.link.LinkFailure
import org.ternmesh.app.link.LinkState
import org.ternmesh.companion.Body
import org.ternmesh.companion.Connection
import org.ternmesh.companion.ConnectionEvent
import org.ternmesh.companion.Conversations
import org.ternmesh.companion.Item
import org.ternmesh.companion.MessageState
import org.ternmesh.companion.Outcome
import org.ternmesh.companion.Peer
import org.ternmesh.companion.Records
import org.ternmesh.companion.RecordsFile
import java.io.File
import kotlin.random.Random

/** The node the user chose, by its Bluetooth address and the name Android showed for it. */
data class ChosenNode(val address: String, val name: String?)

/** Where the app is with its node. */
enum class Phase {
    /** No node chosen. */
    NONE,

    /** Waiting for the node to be in range and answer. */
    CONNECTING,

    /** Android is asking for the node's passkey. */
    PAIRING,

    /** Linked; the node has not yet answered HELLO. */
    GREETING,

    /** Asking the node for what it holds. */
    SYNCING,
    READY,

    /** Stopped, for [NodeState.problem]; the user decides whether to try again. */
    STOPPED,
}

/** What went wrong, for the screens to put in words. */
sealed interface Problem {
    data class Link(val why: LinkFailure) : Problem

    /** The node refused HELLO with this code. */
    data class Refused(val code: Int) : Problem

    /** The node stopped answering. */
    data object Gone : Problem
}

/** A message the node never answered for, which the user may send again under the same `ref`. */
data class Unanswered(val ref: Long, val peer: Peer, val text: String)

data class NodeState(
    val node: ChosenNode? = null,
    val phase: Phase = Phase.NONE,
    val problem: Problem? = null,
    val records: Records = Records(),
    val version: Int? = null,
    val firmware: String? = null,
    /** First contacts the node refused, newest last, until the user deals with them. */
    val asked: List<Body.Asked> = emptyList(),
    val unanswered: List<Unanswered> = emptyList(),
)

class NodeRepository(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val link = BleLink(context, handler)
    private val prefs = context.getSharedPreferences("node", Context.MODE_PRIVATE)
    private val unansweredPrefs = context.getSharedPreferences("unanswered", Context.MODE_PRIVATE)
    private val notifier = Notifier(context)
    private var connection = newConnection(Records())

    /** Ids already notified or already held, so one item makes one notification. */
    private val notified = mutableSetOf<Long>()

    private val _state = MutableStateFlow(NodeState())
    val state: StateFlow<NodeState> = _state

    /** The conversation on screen, whose new messages need no notification. */
    var viewing: Peer? = null

    /** The `through` of the READ last sent, so the same one is not sent again and again. */
    private var readSent = 0L

    init {
        link.onState = ::linkState
        link.onFrame = { frame ->
            connection.receive(frame)
            scheduleTick()
        }
        val address = prefs.getString(KEY_ADDRESS, null)
        if (address != null) {
            val node = ChosenNode(address, prefs.getString(KEY_NAME, null))
            adopt(load(address))
            update { it.copy(node = node, records = connection.records.copy(), unanswered = loadUnanswered(address)) }
        }
    }

    /** Connects to the node chosen before, if there is one and the app is not already at it. */
    fun resume() {
        val s = _state.value
        val node = s.node ?: return
        if (s.phase == Phase.NONE || s.phase == Phase.STOPPED && s.problem == Problem.Link(LinkFailure.LOST)) {
            connectTo(node, waitForIt = true)
        }
    }

    /** Makes [node] the app's node and connects to it. */
    fun choose(node: ChosenNode) {
        if (_state.value.node?.address != node.address) {
            forget()
            prefs.edit().putString(KEY_ADDRESS, node.address).putString(KEY_NAME, node.name).apply()
            adopt(load(node.address))
            update { NodeState(node = node, records = connection.records.copy(), unanswered = loadUnanswered(node.address)) }
        }
        connectTo(node, waitForIt = false)
    }

    fun retry() {
        _state.value.node?.let { connectTo(it, waitForIt = false) }
    }

    /** Drops the link and the node, keeping its records on disk in case it is chosen again. */
    fun forget() {
        handler.removeCallbacksAndMessages(null)
        link.disconnect()
        connection.close()
        save()
        prefs.edit().clear().apply()
        NodeService.stop(context)
        update { NodeState() }
    }

    /** Makes a request; [then] runs on the main thread with what came of it. */
    fun submit(body: Body, then: (Outcome) -> Unit = {}) {
        connection.submit(body) { outcome ->
            then(outcome)
            publish()
        }
        scheduleTick()
    }

    /**
     * Sends [text] to [peer]; with the [ref] of one unanswered, sends that again, which the node
     * sends once. One the node may not have, for want of an answer or a link, is kept for the user
     * to send again; [then] hears of the rest that did not go.
     */
    fun send(peer: Peer, text: String, ref: Long = Random.nextLong(1, 0x1_0000_0000L), then: (Outcome) -> Unit = {}) {
        val done = { outcome: Outcome ->
            val keep = outcome == Outcome.NoAnswer || outcome == Outcome.Closed
            setUnanswered { list ->
                val rest = list.filter { it.ref != ref }
                if (keep) rest + Unanswered(ref, peer, text) else rest
            }
            if (!keep) then(outcome)
        }
        when (peer) {
            is Peer.Contact -> connection.sendMessage(text, peer.address, ref, done)
            is Peer.Group -> connection.sendToGroup(text, peer.group, ref, done)
        }
        scheduleTick()
    }

    fun dismissUnanswered(u: Unanswered) = setUnanswered { it - u }

    /**
     * Messages the node may or may not have are kept on disk too: sent again after a restart under
     * a new `ref`, one the node did take would go twice.
     */
    private fun setUnanswered(change: (List<Unanswered>) -> List<Unanswered>) {
        update { it.copy(unanswered = change(it.unanswered)) }
        val address = _state.value.node?.address ?: return
        val lines = _state.value.unanswered.map { u ->
            "${u.ref}:${Notifier.key(u.peer)}:" + Base64.encodeToString(u.text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
        unansweredPrefs.edit().putStringSet(address, lines.toSet()).apply()
    }

    private fun loadUnanswered(address: String): List<Unanswered> =
        unansweredPrefs.getStringSet(address, emptySet()).orEmpty().mapNotNull { line ->
            val parts = line.split(':')
            if (parts.size != 4) return@mapNotNull null
            val ref = parts[0].toLongOrNull() ?: return@mapNotNull null
            val peer = Notifier.peer("${parts[1]}:${parts[2]}") ?: return@mapNotNull null
            val text = runCatching { String(Base64.decode(parts[3], Base64.NO_WRAP), Charsets.UTF_8) }.getOrNull()
                ?: return@mapNotNull null
            Unanswered(ref, peer, text)
        }.sortedBy { it.ref }

    fun dismissAsked(a: Body.Asked) = update { s -> s.copy(asked = s.asked.filter { it.address != a.address }) }

    /** The user has seen [peer]'s conversation: tell the node, as far as a READ may reach. */
    fun markRead(peer: Peer) {
        if (_state.value.phase != Phase.READY) return
        val through = Conversations.readThrough(connection.records, peer) ?: return
        if (through == readSent) return
        readSent = through
        submit(Body.Read(through)) { if (it !is Outcome.Answered) readSent = 0 }
        notifier.cancel(peer)
    }

    // MARK: -

    private fun newConnection(records: Records) = Connection(
        records = records,
        now = SystemClock::elapsedRealtime,
        wallTime = { System.currentTimeMillis() / 1000 },
    ).also { c ->
        c.send = { frame -> link.write(frame) }
        c.onEvent = ::event
    }

    /** Takes up records kept from before: what they hold was notified then, or seen. */
    private fun adopt(records: Records) {
        connection = newConnection(records)
        notified.clear()
        notified += records.items.keys
    }

    private fun connectTo(node: ChosenNode, waitForIt: Boolean) {
        NodeService.start(context)
        connection.close()
        link.connect(node.address, waitForIt)
    }

    private fun linkState(s: LinkState) {
        when (s) {
            LinkState.Connecting -> update { it.copy(phase = Phase.CONNECTING, problem = null) }
            LinkState.Pairing -> update { it.copy(phase = Phase.PAIRING) }
            LinkState.Open -> {
                update { it.copy(phase = Phase.GREETING) }
                readSent = 0
                connection.open()
                scheduleTick()
            }
            LinkState.Closed -> {}
            is LinkState.Failed -> {
                connection.close()
                handler.removeCallbacks(tick)
                save()
                val node = _state.value.node
                if (s.why == LinkFailure.LOST && node != null) {
                    // Out of range, or the node restarted: Android reconnects when it is back.
                    update { it.copy(phase = Phase.CONNECTING, problem = Problem.Link(s.why)) }
                    handler.postDelayed({ if (_state.value.node == node) link.connect(node.address, waitForIt = true) }, 1_000)
                } else {
                    update { it.copy(phase = Phase.STOPPED, problem = Problem.Link(s.why)) }
                }
            }
        }
    }

    private fun event(e: ConnectionEvent) {
        when (e) {
            is ConnectionEvent.Ready -> update {
                it.copy(phase = Phase.SYNCING, version = e.version, firmware = e.firmware, problem = null)
            }
            is ConnectionEvent.News -> news(e.body)
            ConnectionEvent.Synced -> {
                update { it.copy(phase = Phase.READY) }
                save()
                viewing?.let(::markRead)
            }
            is ConnectionEvent.SyncRefused -> {}
            is ConnectionEvent.Refused -> {
                link.disconnect()
                update { it.copy(phase = Phase.STOPPED, problem = Problem.Refused(e.code)) }
            }
            ConnectionEvent.Gone -> {
                link.disconnect()
                val node = _state.value.node ?: return
                update { it.copy(phase = Phase.CONNECTING, problem = Problem.Gone) }
                handler.postDelayed({ if (_state.value.node == node) link.connect(node.address, waitForIt = true) }, 1_000)
            }
        }
        publish()
    }

    private fun news(body: Body) {
        if (body is Body.Asked) {
            update { s -> s.copy(asked = s.asked.filter { it.address != body.address } + body) }
            return
        }
        if (body is Item && body.isUnread && body.state == MessageState.RECEIVED && body.id !in notified &&
            connection.records.syncedVersion != null
        ) {
            // Items a first sync brings are history, not news. After that, an unread item whose id
            // has not been seen arrived while the app was away or is arriving now.
            notified += body.id
            val peer = Conversations.peerOf(body)
            val onScreen = viewing == peer &&
                ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (onScreen) markRead(peer) else notifier.notify(connection.records, body)
        }
        handler.removeCallbacks(saveSoon)
        handler.postDelayed(saveSoon, 2_000)
    }

    private fun publish() {
        notified += connection.records.items.keys
        update { it.copy(records = connection.records.copy()) }
        NodeService.refresh(context, _state.value)
    }

    private val tick = Runnable {
        connection.tick()
        scheduleTick()
    }

    private fun scheduleTick() {
        handler.removeCallbacks(tick)
        val at = connection.nextDeadline ?: return
        handler.postDelayed(tick, maxOf(0, at - SystemClock.elapsedRealtime()))
    }

    private val saveSoon = Runnable { save() }

    private fun file(address: String) = File(context.filesDir, "records/${address.replace(':', '-')}.trnr")

    private fun load(address: String): Records =
        file(address).takeIf { it.exists() }?.let { RecordsFile.decode(it.readBytes()) } ?: Records()

    private fun save() {
        val address = _state.value.node?.address ?: return
        val f = file(address)
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(RecordsFile.encode(connection.records))
        tmp.renameTo(f)
    }

    private inline fun update(change: (NodeState) -> NodeState) {
        _state.value = change(_state.value)
    }

    companion object {
        private const val KEY_ADDRESS = "address"
        private const val KEY_NAME = "name"
    }
}
