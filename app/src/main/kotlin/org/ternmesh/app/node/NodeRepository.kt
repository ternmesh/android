// The one node the app drives: the Bluetooth link to it, the protocol's connection over that, the
// records on disk, an update of its firmware, and what the screens are shown of all of them.
//
// Everything here runs on the main thread. The link posts its callbacks there, the connection's
// timer runs there, the screens call in from there, and downloads come back there.
package org.ternmesh.app.node

import android.content.Context
import android.os.Handler
import android.util.Base64
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.ternmesh.app.R
import org.ternmesh.app.link.BleLink
import org.ternmesh.app.link.BootloaderLink
import org.ternmesh.app.link.BootloaderScan
import org.ternmesh.app.link.LinkFailure
import org.ternmesh.app.link.LinkState
import org.ternmesh.app.ui.checkResultText
import org.ternmesh.app.ui.manageDoneText
import org.ternmesh.app.ui.nodeName
import org.ternmesh.app.ui.outcomeText
import org.ternmesh.companion.Address
import org.ternmesh.companion.Body
import org.ternmesh.companion.BootloaderState
import org.ternmesh.companion.BootloaderUpdater
// This class's own companion object would take the bare name.
import org.ternmesh.companion.Companion as CompanionProtocol
import org.ternmesh.companion.Connection
import org.ternmesh.companion.ConnectionEvent
import org.ternmesh.companion.Conversations
import org.ternmesh.companion.DfuPackage
import org.ternmesh.companion.DfuState
import org.ternmesh.companion.ErrorCode
import org.ternmesh.companion.Install
import org.ternmesh.companion.Item
import org.ternmesh.companion.ManageStep
import org.ternmesh.companion.Manifest
import org.ternmesh.companion.MessageState
import org.ternmesh.companion.Outcome
import org.ternmesh.companion.OwnedNodes
import org.ternmesh.companion.OwnerNotices
import org.ternmesh.companion.Peer
import org.ternmesh.companion.Records
import org.ternmesh.companion.RecordsFile
import org.ternmesh.companion.Remote
import org.ternmesh.companion.RemoteEvent
import org.ternmesh.companion.Signatures
import org.ternmesh.companion.Signed
import org.ternmesh.companion.UpdateBy
import org.ternmesh.companion.UpdateState
import org.ternmesh.companion.Updater
import org.ternmesh.companion.install
import org.ternmesh.companion.updateBy
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.random.Random

/**
 * The node the user chose, by its Bluetooth address and the name Android showed for it, and the
 * [label] the user gave it, if any. The label is the app's alone: it never goes to the node.
 */
data class ChosenNode(val address: String, val name: String?, val label: String? = null) {
    /** What the app calls the node: the user's label, else the Bluetooth name. */
    val shown: String? get() = label ?: name
}

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

    /** The user disconnected: the node stays chosen, and nothing connects until they say so. */
    DISCONNECTED,

    /**
     * The node restarted into its bootloader for an update: the app is off its link, and gives the
     * bootloader the image over a link of its own, until the bootloader starts it, or the user gives
     * up on it.
     */
    BOOTLOADER,

    /**
     * The user turned the node off, and it said it would: it is on again only once someone turns it
     * on at the node, so, as after disconnecting, nothing connects until the user says so.
     */
    OFF,
}

/** Whether the app stays off its node until the user connects again. */
val Phase.stayingOff: Boolean get() = this == Phase.DISCONNECTED || this == Phase.OFF

/** What went wrong, for the screens to put in words. */
sealed interface Problem {
    data class Link(val why: LinkFailure) : Problem

    /** The node refused HELLO with this code. */
    data class Refused(val code: Int) : Problem

    /** The node stopped answering. */
    data object Gone : Problem
}

/**
 * A message the node never answered for, which the user may send again under the same `ref`.
 * [after] is the greatest id held when it was first sent: a message the node holds past it, to the
 * same peer with the same text, is this one, and it went.
 */
data class Unanswered(val ref: Long, val peer: Peer, val text: String, val after: Long)

/**
 * A node the user manages over the mesh (version 17), as far as it has answered: its own state
 * from its last sync over the mesh, apart from the app's own node's.
 */
data class Managed(
    val address: Address,
    val records: Records = Records(),
    /** The managed node's version and firmware, once it has answered `HELLO`. */
    val version: Int? = null,
    val firmware: String? = null,
    /** The request on its way, or waiting for its answer; null when none is. */
    val asking: Body? = null,
    /** Which try this is, from 1 to [Remote.TRIES]. */
    val attempt: Int = 0,
    /** Where the message carrying it is, a [MessageState]; null before the node has said. */
    val carried: Int? = null,
    /** While it waits, what for and about how long, as a message's `reason` and `wait`. */
    val reason: Int = 0,
    val wait: Int = 0,
    /** Seconds between the node's wakes, when it is known to sleep; null when it is not, or the app does not know. */
    val wake: Int? = null,
    /** Only checking whether the app's node is an owner: one `HELLO`, and no sync. */
    val checking: Boolean = false,
    /** The check's dialog is open. Closed while the check goes on, or with an outcome the user has not seen, a banner says so and opens it again. */
    val dialog: Boolean = false,
    /** When the message carrying the request was delivered, on the elapsed clock; null before. */
    val deliveredAt: Long? = null,
    /** When the app's own node took the request (`QUEUED`), on the elapsed clock; null before. */
    val queuedAt: Long? = null,
    /** What the last answer said of the app's node: an owner, not one (`ERROR` 15), or null before either. */
    val owner: Boolean? = null,
    /** When a setting on trial goes back, on the elapsed clock, unless the node hears again; null when none is on trial. */
    val trialUntil: Long? = null,
    /** Until when the next request waits for the node to restart to apply a role or a power, on the elapsed clock; null when none waits. */
    val settleUntil: Long? = null,
    /** The last setting on trial was confirmed, and stays. */
    val confirmed: Boolean = false,
    /** What the last request came to, when it did not simply work: shown until the next. */
    val failed: Outcome? = null,
    /** Whether a sync over the mesh has finished since managing began. */
    val synced: Boolean = false,
)

/** A node erased with `WIPE`: [name] as the user knew it; [bondRemoved] if the phone forgot its bond too. */
data class Wiped(val name: String?, val bondRemoved: Boolean)

data class NodeState(
    val node: ChosenNode? = null,
    val phase: Phase = Phase.NONE,
    val problem: Problem? = null,
    val records: Records = Records(),
    val version: Int? = null,
    val firmware: String? = null,
    /** The hardware the node's firmware is built for, empty if it cannot be updated over the link; null from a node of version 3 or earlier. */
    val board: String? = null,
    /** The node's firmware release, empty if it has none; null from a node of version 3 or earlier. */
    val release: String? = null,
    /** How the node is updated, `INFO`'s `update` (an [UpdateBy]); null from a node of version 11 or earlier. */
    val updatedBy: Int? = null,
    val update: FirmwareUpdate = FirmwareUpdate.Idle,
    /**
     * The node just erased itself with `WIPE`, until the user has read what that means: its name,
     * and whether the phone's Bluetooth bond with it could be removed here.
     */
    val wiped: Wiped? = null,
    /** First contacts the node refused, newest last, until the user deals with them. */
    val asked: List<Body.Asked> = emptyList(),
    /** Nodes that said their owners changed (`OWNER_CHANGED`, version 17), newest last, until the user dismisses them; kept on disk, the last [OwnerNotices.KEPT]. */
    val ownerChanges: List<Body.OwnerChanged> = emptyList(),
    /** The nodes the app's node is an owner of, as far as the app has learned ([OwnedNodes]); kept on disk. */
    val owned: Set<Address> = emptySet(),
    /** The node refused its sync with `ERROR` 14: its Bluetooth is locked, and it takes nothing over it but `HELLO` and `PING`. */
    val lockedOut: Boolean = false,
    /** The node managed over the mesh from the Manage screen, while it is open. */
    val managed: Managed? = null,
    val unanswered: List<Unanswered> = emptyList(),
    /** Every node the app keeps records of, most recently chosen first. */
    val known: List<ChosenNode> = emptyList(),
    /** The first-run setup was finished, or skipped, for this node. */
    val setUp: Boolean = true,
    /**
     * When each position, sharing and card record held, and the node's own fix, arrived, on the
     * elapsed clock: the `age`, `minutes` and `heard` each gives are as of then.
     */
    val arrived: Map<Body, Long> = emptyMap(),
)

class NodeRepository(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val link = BleLink(context, handler)
    private val prefs = context.getSharedPreferences("node", Context.MODE_PRIVATE)
    private val unansweredPrefs = context.getSharedPreferences("unanswered", Context.MODE_PRIVATE)
    private val askedPrefs = context.getSharedPreferences("asked", Context.MODE_PRIVATE)
    private val knownPrefs = context.getSharedPreferences("known", Context.MODE_PRIVATE)
    private val labelPrefs = context.getSharedPreferences("labels", Context.MODE_PRIVATE)
    private val setupPrefs = context.getSharedPreferences("setup", Context.MODE_PRIVATE)
    private val locationPrefs = context.getSharedPreferences("location", Context.MODE_PRIVATE)
    private val notifier = Notifier(context)
    private val location = LocationFeed(context) { position ->
        connection.submit(position) {} // a fix the node did not take is followed by the next
        scheduleTick()
    }
    private var connection = newConnection(Records())

    /** Ids already notified or already held, so one item makes one notification. */
    private val notified = mutableSetOf<Long>()

    /**
     * Conversations the user has seen and the node has not yet been told of, each up to the greatest
     * id it held when seen. One seen behind another's unread item waits here until that one is read.
     */
    private val seen = mutableMapOf<Peer, Long>()

    /**
     * The greatest id the node has answered a send with. Its record may not have come yet, so a send
     * made now matches only records past it: one the node queues for it is given a greater id.
     */
    private var queuedFloor = 0L

    private val scope = MainScope()

    /** The update under way, and the release it gives the node, until the node is back from it. */
    private var updater: Updater? = null
    private var updatingTo: String? = null
    private var downloading: Job? = null
    private var checking: Job? = null

    /** The image the update under way sends, and whether it waits for the node to sync before it goes on. */
    private var updateImage: Manifest.Image? = null
    private var resumeWhenSynced = false

    /** An update by the node's bootloader, under way or stopped, and how the app finds and links to the bootloader. */
    private var bootUpdater: BootloaderUpdater? = null
    private val bootScan = BootloaderScan(context, handler)
    private val bootLink = BootloaderLink(context, handler)

    /** The last check's answer, which a cancelled update goes back to. */
    private var checked: FirmwareUpdate.Checked? = null

    /** The release the node ran when it was last checked for an update: what the offer was weighed against. */
    private var checkedAgainst: String? = null

    private val _state = MutableStateFlow(NodeState())
    val state: StateFlow<NodeState> = _state

    /** The conversation on screen, whose new messages need no notification. */
    var viewing: Peer? = null

    /** When each position, sharing and card record arrived; see [NodeState.arrived]. */
    private val arrived = mutableMapOf<Body, Long>()

    /** The `through` of the READ last sent, so the same one is not sent again and again. */
    private var readSent = 0L

    /** The node managed over the mesh, while the Manage screen is open. */
    private var remote: Remote? = null

    /**
     * Each node managed over the mesh since the app's node was chosen, kept when its screen closes:
     * a node that has answered a `HELLO` from this app is asked straight for its state next time,
     * one request, not two, which for a node that sleeps is a wake saved.
     */
    private val remotes = mutableMapOf<Address, Remote>()

    /** The last `SLEEP` each node managed over the mesh gave, while the app runs: how long it may take to answer next time. */
    private val sleepOf = mutableMapOf<Address, Body.Sleep>()

    /** Whether the Manage screen is on screen: once it is left, a request on its way goes on, and what it came to is a notification. */
    private var manageShown = false

    /** The last request the user made of the managed node, and what it came to: what a notification says once they have left. */
    private var lastDone: Pair<Body, Outcome>? = null

    /** The check's outcome has been told in a notification, or seen. */
    private var checkTold = false

    init {
        link.onState = ::linkState
        link.onFrame = { frame ->
            connection.receive(frame)
            scheduleTick()
        }
        val address = prefs.getString(KEY_ADDRESS, null)
        if (address != null) {
            val node = labelled(ChosenNode(address, prefs.getString(KEY_NAME, null)))
            // A node chosen before the app kept a list of them.
            if (!knownPrefs.contains(address)) remember(node)
            adopt(load(address))
            val phase = when {
                prefs.getBoolean(KEY_OFF, false) -> Phase.OFF
                prefs.getBoolean(KEY_DISCONNECTED, false) -> Phase.DISCONNECTED
                else -> Phase.NONE
            }
            update {
                it.copy(
                    node = node, phase = phase, records = connection.records.copy(),
                    unanswered = loadUnanswered(address), asked = loadAsked(address), ownerChanges = loadOwnerChanges(address),
                    owned = loadOwned(address),
                )
            }
        }
        update { it.copy(known = loadKnown()) }
    }

    /** Connects to the node chosen before, if there is one and the app is not already at it. */
    fun resume() {
        val s = _state.value
        val node = s.node ?: return
        if (s.phase == Phase.NONE || s.phase == Phase.STOPPED && s.problem == Problem.Link(LinkFailure.LOST)) {
            connectTo(node, waitForIt = true)
        }
    }

    /** Makes [chosen] the app's node and connects to it. */
    fun choose(chosen: ChosenNode) {
        // Chosen from a scan, it comes without the label the user gave it.
        val node = labelled(chosen)
        if (_state.value.node?.address != node.address) {
            leave()
            prefs.edit().putString(KEY_ADDRESS, node.address).putString(KEY_NAME, node.name).apply()
            adopt(load(node.address))
            update {
                NodeState(
                    node = node, records = connection.records.copy(), unanswered = loadUnanswered(node.address),
                    asked = loadAsked(node.address), ownerChanges = loadOwnerChanges(node.address),
                    owned = loadOwned(node.address),
                )
            }
        }
        remember(node)
        update { it.copy(known = loadKnown()) }
        connectTo(node, waitForIt = false)
    }

    /** Connects again, after a failure or after the user disconnected. */
    fun connect() {
        _state.value.node?.let { connectTo(it, waitForIt = false) }
    }

    /**
     * Drops the link and stays off it, across restarts too, until the user connects again. The node
     * stays chosen and its records stay: connecting again syncs only what is new.
     */
    fun disconnect() = stayOff(Phase.DISCONNECTED)

    /**
     * Turns the node off, which only its button, reset or power turns on again: asked only once the
     * user has been told so. Once the node answers, the app stays off it as after [disconnect], and
     * says the node is off rather than lost, until the user connects again. [then] hears of any
     * other outcome; an `OFF` the node did not answer may be sent again.
     */
    fun turnOff(then: (Outcome) -> Unit = {}) {
        submit(Body.Off) { outcome ->
            if (outcome == Outcome.Answered(Body.Ok)) stayOff(Phase.OFF) else then(outcome)
        }
    }

    /**
     * Erases the node (`WIPE`, version 13): its address, contacts, groups, messages and settings,
     * and its Bluetooth bonds. Asked only once the user has been told so. Once the node answers, the
     * phone forgets its bond with it, and the app everything it held for it, as after [forget]: the
     * node it finds next is a new one. [then] hears of any other outcome.
     */
    fun wipe(then: (Outcome) -> Unit = {}) {
        val node = _state.value.node ?: return
        submit(Body.Wipe) { outcome ->
            if (outcome != Outcome.Answered(Body.Ok)) return@submit then(outcome)
            forget(node.address)
            val removed = link.removeBond(node.address)
            update { it.copy(wiped = Wiped(node.shown, removed)) }
        }
    }

    /** The user has read that the node was erased. */
    fun dismissWiped() = update { it.copy(wiped = null) }

    /** Drops the link and stays off it in [phase], [Phase.DISCONNECTED] or [Phase.OFF], across restarts too. */
    private fun stayOff(phase: Phase) {
        if (_state.value.node == null) return
        // Before the link closes, so nothing that answers to its closing shows the service again.
        update { it.copy(phase = phase, problem = null) }
        prefs.edit().putBoolean(KEY_DISCONNECTED, true).putBoolean(KEY_OFF, phase == Phase.OFF).apply()
        handler.removeCallbacksAndMessages(null)
        link.disconnect()
        connection.close()
        save()
        NodeService.stop(context)
    }

    /** Drops the link and the node, keeping its records on disk in case it is chosen again. */
    fun leave() {
        stopManaging()
        // Another node's HELLOs, answered to this one's address, are not its.
        remotes.clear()
        stopUpdate()
        handler.removeCallbacksAndMessages(null)
        link.disconnect()
        connection.close()
        save()
        prefs.edit().clear().apply()
        NodeService.stop(context)
        // A notification names only an address or group: opened after another node is chosen, it
        // would open that conversation against the wrong node.
        notifier.cancelAll()
        update { NodeState(known = loadKnown()) }
    }

    /**
     * Forgets the node at [address]: leaves it if it is the app's node, and deletes what the app kept
     * of it. Its messages stay on the node, and come back with a sync if it is chosen again.
     */
    fun forget(address: String) {
        if (_state.value.node?.address == address) leave()
        file(address).delete()
        unansweredPrefs.edit().remove(address).apply()
        askedPrefs.edit().remove(address).apply()
        ownersFile(address).delete()
        ownedFile(address).delete()
        knownPrefs.edit().remove(address).apply()
        labelPrefs.edit().remove(address).apply()
        setupPrefs.edit().remove(address).apply()
        locationPrefs.edit().remove(address).apply()
        update { it.copy(known = loadKnown()) }
    }

    /** The first-run setup is over for the app's node: it is not offered for it again. */
    fun finishSetup() {
        val address = _state.value.node?.address ?: return
        setupPrefs.edit().putBoolean(address, true).apply()
        update { it.copy(setUp = true) }
    }

    /**
     * Whether the setup is over for [address]. A node met before the setup existed, already given a
     * region and a contact, counts as set up rather than being walked through it again.
     */
    private fun setUp(address: String, records: Records): Boolean {
        if (setupPrefs.getBoolean(address, false)) return true
        val done = records.self?.region?.isNotEmpty() == true && records.contacts.isNotEmpty()
        if (done) setupPrefs.edit().putBoolean(address, true).apply()
        return done
    }

    /**
     * Gives the node at [address] the [label] the app shows it by, on this phone alone; an empty
     * label goes back to the Bluetooth name. Nothing is sent to the node.
     */
    fun rename(address: String, label: String) {
        val trimmed = label.trim()
        labelPrefs.edit().apply { if (trimmed.isEmpty()) remove(address) else putString(address, trimmed) }.apply()
        update { s ->
            s.copy(node = s.node?.let { if (it.address == address) labelled(it) else it }, known = loadKnown())
        }
        // The notification is rebuilt only when the phase changes: its title is the node's name.
        if (_state.value.node?.address == address) NodeService.refresh(context, _state.value)
    }

    private fun labelled(node: ChosenNode) = node.copy(label = labelPrefs.getString(node.address, null))

    /** Adds [node] to the nodes the app keeps, as the one chosen last. */
    private fun remember(node: ChosenNode) {
        knownPrefs.edit().putString(node.address, "${System.currentTimeMillis()}:${node.name.orEmpty()}").apply()
    }

    private fun loadKnown(): List<ChosenNode> = knownPrefs.all.mapNotNull { (address, value) ->
        val (at, name) = (value as? String)?.split(':', limit = 2)?.takeIf { it.size == 2 } ?: return@mapNotNull null
        (at.toLongOrNull() ?: 0L) to labelled(ChosenNode(address, name.ifEmpty { null }))
    }.sortedByDescending { it.first }.map { it.second }

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
     * to send again, and [kept] hears of it; [then] hears of the rest.
     */
    fun send(
        peer: Peer,
        text: String,
        ref: Long = Random.nextLong(1, 0x1_0000_0000L),
        after: Long = maxOf(connection.records.greatest, queuedFloor),
        then: (Outcome) -> Unit = {},
        kept: () -> Unit = {},
    ) {
        sending[ref] = peer to text
        val done = { outcome: Outcome ->
            sending -= ref
            ((outcome as? Outcome.Answered)?.body as? Body.Queued)?.let { queuedFloor = maxOf(queuedFloor, it.id) }
            val keep = outcome == Outcome.NoAnswer || outcome == Outcome.Closed
            setUnanswered { list ->
                val rest = list.filter { it.ref != ref }
                if (keep) rest + Unanswered(ref, peer, text, after) else rest
            }
            if (keep) kept() else then(outcome)
        }
        when (peer) {
            is Peer.Contact -> connection.sendMessage(text, peer.address, ref, done)
            is Peer.Group -> connection.sendToGroup(text, peer.group, ref, done)
        }
        scheduleTick()
    }

    /**
     * Whether a message may be written now. Not until the first sync has finished: before it the
     * records are the ones on disk, and the `after` a send is matched from must be the node's.
     */
    val canWrite: Boolean get() = _state.value.phase == Phase.READY

    /** Sends in flight, by ref: what was sent to whom, until the node answers. */
    private val sending = mutableMapOf<Long, Pair<Peer, String>>()

    /**
     * Sends [text] the user just wrote. The same text to the same peer while one is unresolved is
     * that one again, under its ref: two the node could not tell apart would leave a sync unable to
     * say which of them went. Returns whether it was taken; one the same as a send still in flight
     * is not, and waits with the writer.
     */
    fun write(peer: Peer, text: String, then: (Outcome) -> Unit, kept: () -> Unit = {}): Boolean {
        if (!canWrite) return false
        // The same again while the first is unanswered is refused, not dropped: the writer keeps it.
        if (sending.values.any { it == peer to text }) return false
        _state.value.unanswered.firstOrNull { it.peer == peer && it.text == text }?.let {
            resend(it, then, kept)
            return true
        }
        send(peer, text, then = then, kept = kept)
        return true
    }

    /**
     * Sends [text], written as a reply in [peer]'s notification, and says there what came of it. To
     * reply is to have seen the conversation, so it is read. One that cannot go now is not kept: the
     * notification says so, and the user writes it again in the app. One the node did not answer is
     * kept as any is, and the notification says that too, not that it was sent.
     */
    fun reply(peer: Peer, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return notifier.cancel(peer)
        if (trimmed.toByteArray(Charsets.UTF_8).size > CompanionProtocol.TEXT_MAX) {
            return notifier.replied(connection.records, peer, context.getString(R.string.reply_too_long, CompanionProtocol.TEXT_MAX))
        }
        // An outcome can come before write() returns; the notification it posts is the last word.
        var settled = false
        val taken = write(
            peer,
            trimmed,
            then = { outcome ->
                settled = true
                if (outcome !is Outcome.Answered) notifier.replied(connection.records, peer, outcomeText(context, outcome))
            },
            kept = {
                settled = true
                notifier.replied(connection.records, peer, context.getString(R.string.reply_unanswered, trimmed))
            },
        )
        if (taken) {
            markRead(peer)
            if (!settled) notifier.replied(connection.records, peer, context.getString(R.string.reply_sent, trimmed))
        } else {
            notifier.replied(connection.records, peer, context.getString(if (canWrite) R.string.still_sending else R.string.reply_not_connected))
        }
    }

    /** Sends [u] again, unless the node turns out to hold it already: then it went, and is dropped. */
    fun resend(u: Unanswered, then: (Outcome) -> Unit, kept: () -> Unit = {}) {
        if (!canWrite) return
        if (Conversations.holdsSent(connection.records, u.peer, u.text, u.after)) return dismissUnanswered(u)
        send(u.peer, u.text, u.ref, u.after, then, kept)
    }

    fun dismissUnanswered(u: Unanswered) = setUnanswered { it - u }

    /**
     * Forgets [peer]'s conversation on the node and on every client of it, for good, as far as
     * [through]: the greatest id the user was shown in it, so that a message that arrives while they
     * decide is not forgotten unseen (version 16). The node's `FORGOTTEN` takes it from the records.
     */
    fun forget(peer: Peer, through: Long, then: (Outcome) -> Unit = {}) = submit(
        when (peer) {
            is Peer.Contact -> Body.Forget(peer.address, through)
            is Peer.Group -> Body.ForgetGroup(peer.group, through)
        },
        then,
    )

    /**
     * What a conversation forgot, here or on another client, goes from what the app keeps of it
     * besides the records too: its notification, and sends not answered from before it.
     */
    private fun forgotten(peer: Peer, through: Long) {
        notifier.cancel(peer)
        if (_state.value.unanswered.any { it.peer == peer && it.after < through }) {
            setUnanswered { list -> list.filterNot { it.peer == peer && it.after < through } }
        }
    }

    /**
     * Messages the node may or may not have are kept on disk too: sent again after a restart under
     * a new `ref`, one the node did take would go twice.
     */
    private fun setUnanswered(change: (List<Unanswered>) -> List<Unanswered>) {
        update { it.copy(unanswered = change(it.unanswered)) }
        val address = _state.value.node?.address ?: return
        val lines = _state.value.unanswered.map { u ->
            "${u.ref}:${Notifier.key(u.peer)}:" + Base64.encodeToString(u.text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) + ":${u.after}"
        }
        unansweredPrefs.edit().putStringSet(address, lines.toSet()).apply()
    }

    private fun loadUnanswered(address: String): List<Unanswered> =
        unansweredPrefs.getStringSet(address, emptySet()).orEmpty().mapNotNull { line ->
            val parts = line.split(':')
            if (parts.size != 5) return@mapNotNull null
            val ref = parts[0].toLongOrNull() ?: return@mapNotNull null
            val peer = Notifier.peer("${parts[1]}:${parts[2]}") ?: return@mapNotNull null
            val text = runCatching { String(Base64.decode(parts[3], Base64.NO_WRAP), Charsets.UTF_8) }.getOrNull()
                ?: return@mapNotNull null
            Unanswered(ref, peer, text, parts[4].toLongOrNull() ?: 0)
        }.sortedBy { it.ref }

    /**
     * Starts managing the node at [address] over the mesh (version 17): says `HELLO` to it, then asks
     * for its own state. It answers only if it holds this app's node as one of its owners. With
     * [check], only says `HELLO`, to learn whether it does: an answer means it does, `ERROR` 15
     * that it does not, and silence nothing.
     */
    fun manage(address: Address, check: Boolean = false) {
        remote?.takeIf { it.to == address }?.let { r ->
            // Checked first, and now managed: go on from the HELLO already answered.
            val m = _state.value.managed
            if (check) {
                update { it.copy(managed = it.managed?.copy(dialog = true)) }
            } else if (m?.checking == true) {
                update { it.copy(managed = it.managed?.copy(checking = false, dialog = false)) }
                if (r.agreed != null) r.sync { outcome -> remoteDone(r, outcome) } else r.open { outcome -> remoteDone(r, outcome) }
                publishRemote(r)
                scheduleTick()
            }
            return
        }
        remote?.close()
        val r = remotes.getOrPut(address) { Remote(address, manage = { body, then -> submit(body, then) }, now = SystemClock::elapsedRealtime) }
        // A node known to sleep may answer only once it wakes: wait for it, not the bare two minutes.
        val sleep = sleepOf[address]
        r.wake = ManageStep.sleeps(sleep, connection.records.contacts[address]?.wake)
        r.listen = sleep?.listen?.takeIf { r.wake != 0 } ?: 0
        remote = r
        lastDone = null
        checkTold = false
        r.onEvent = { e -> remoteEvent(r, e) }
        update { it.copy(managed = Managed(address, checking = check, dialog = check)) }
        if (check) r.submit(Body.Hello(r.version)) { outcome -> remoteDone(r, outcome) } else r.open { outcome -> remoteDone(r, outcome) }
        publishRemote(r)
        scheduleTick()
    }

    /** Opens the check of [address] again, from a banner or a notification; or, gone, starts it again. */
    fun showCheck(address: Address) {
        val m = _state.value.managed
        if (m?.address == address && m.checking) {
            checkTold = true
            update { it.copy(managed = it.managed?.copy(dialog = true)) }
        } else {
            manage(address, check = true)
        }
    }

    /** The check's dialog closed without Cancel: the check goes on, and a banner says so. */
    fun hideCheck() {
        update { s -> s.copy(managed = s.managed?.takeIf { it.checking }?.copy(dialog = false) ?: s.managed) }
        remote?.let { r -> handler.post { afterRemote(r) } }
    }

    /** The Manage screen is on screen. */
    fun showingManage() {
        manageShown = true
    }

    /**
     * The Manage screen was left. A request already on its way goes on until it is answered or fails,
     * and what it came to is a notification; with none, the node is no longer managed.
     */
    fun leftManage() {
        manageShown = false
        val r = remote ?: return
        if (_state.value.managed?.checking == true) return
        if (!r.busy && r.settleUntil == null) stopManaging() else handler.post { afterRemote(r) }
    }

    /** Asks the managed node for its own state again: after `HELLO`, if it has not answered one yet. */
    fun syncManaged() {
        val r = remote ?: return
        // A change answered after the user left is not asked about again: no one is looking.
        if (!manageShown && _state.value.managed?.checking != true) return
        if (r.agreed != null) r.sync { outcome -> remoteDone(r, outcome) } else r.open { outcome -> remoteDone(r, outcome) }
        publishRemote(r)
        scheduleTick()
    }

    /**
     * Asks [body] of the managed node, over the mesh. A `SET` that is answered is on trial, and the
     * `PING` that confirms it follows by itself. [then] hears what came of it.
     */
    fun askManaged(body: Body, then: (Outcome) -> Unit = {}) {
        val r = remote ?: return then(Outcome.Closed)
        r.submit(body) { outcome ->
            // The app's node gave up its own ownership: the node tells every owner but the one who asked.
            val self = connection.records.self?.address
            if (body is Body.Owner && body.owner == 0 && body.address == self && outcome == Outcome.Answered(Body.Ok)) {
                setOwned { it - r.to }
            }
            if (remote === r) lastDone = body to outcome
            remoteDone(r, outcome)
            then(outcome)
        }
        publishRemote(r)
        scheduleTick()
    }

    /** The user stopped waiting: what was asked and not answered is given up on, and the node stays open to ask again. */
    fun stopAsking() {
        val r = remote ?: return
        r.close()
        publishRemote(r)
        scheduleTick()
    }

    /** The Manage screen closed: whatever was asked and not answered is given up on. */
    fun stopManaging() {
        val r = remote ?: return
        remote = null
        r.close()
        update { it.copy(managed = null) }
        scheduleTick()
    }

    /**
     * After anything the managed node's requests came to: a check that ended while its dialog was not
     * open, or the app not in front, is told in a notification; and a request that ended after the
     * user left the Manage screen is told so, and the node is no longer managed.
     */
    private fun afterRemote(r: Remote) {
        if (remote !== r) return
        val m = _state.value.managed?.takeIf { it.address == r.to } ?: return
        val name = nodeName(context, connection.records, r.to)
        if (m.checking) {
            val ended = m.asking == null && (m.owner != null || m.failed != null)
            if (ended && !checkTold) {
                checkTold = true
                if (!(inFront() && m.dialog)) {
                    checkResultText(context, name, m.owner, m.failed)?.let { notifier.managed(r.to, it, manage = m.owner == true) }
                }
            }
            return
        }
        if (manageShown || r.busy || r.settleUntil != null) return
        lastDone?.let { (body, outcome) -> manageDoneText(context, name, body, outcome) }?.let { notifier.managed(r.to, it, manage = true) }
        stopManaging()
    }

    private fun inFront() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    private fun remoteEvent(r: Remote, e: RemoteEvent) {
        if (remote !== r) return
        if (e is RemoteEvent.Owner) setOwned { OwnedNodes.afterAnswer(it, r.to, e.owner) }
        update { s ->
            val m = s.managed?.takeIf { it.address == r.to } ?: return@update s
            s.copy(
                managed = when (e) {
                    is RemoteEvent.Trying -> m.copy(attempt = e.attempt, carried = null, reason = 0, wait = 0, failed = null, deliveredAt = null, queuedAt = null)
                    is RemoteEvent.Carried -> m.copy(
                        carried = e.state, reason = e.reason, wait = e.wait,
                        deliveredAt = if (e.state == MessageState.DELIVERED) m.deliveredAt ?: SystemClock.elapsedRealtime() else null,
                    )
                    is RemoteEvent.Trial -> m.copy(trialUntil = e.until, confirmed = false)
                    RemoteEvent.Confirmed -> m.copy(trialUntil = null, confirmed = true)
                    RemoteEvent.Synced -> m.copy(synced = true)
                    is RemoteEvent.Owner -> m.copy(owner = e.owner)
                    RemoteEvent.Queued -> m.copy(queuedAt = SystemClock.elapsedRealtime())
                    is RemoteEvent.Ready -> m
                },
            )
        }
        publishRemote(r)
    }

    private fun remoteDone(r: Remote, outcome: Outcome) {
        if (remote !== r) return
        update { s ->
            val m = s.managed?.takeIf { it.address == r.to } ?: return@update s
            // Stopped by the user, or the screen closing: nothing went wrong to say.
            s.copy(managed = m.copy(failed = outcome.takeIf { it !is Outcome.Answered && it != Outcome.Closed }))
        }
        publishRemote(r)
        scheduleTick()
    }

    /** What the screens are shown of the managed node: its records and what it is asked, as of now. */
    private fun publishRemote(r: Remote) {
        if (remote !== r) return
        r.records.sleep?.let { sleepOf[r.to] = it }
        update { s ->
            val m = s.managed?.takeIf { it.address == r.to } ?: return@update s
            s.copy(
                managed = m.copy(
                    records = r.records.copy(), version = r.nodeVersion, firmware = r.firmware, asking = r.asking,
                    trialUntil = r.trialUntil, settleUntil = r.settleUntil, attempt = if (r.asking == null) 0 else m.attempt,
                    wake = r.wake?.takeIf { it > 0 },
                ),
            )
        }
        handler.post { afterRemote(r) }
    }

    /** The nodes the app's node is an owner of, changed, and kept on disk beside the records. */
    private fun setOwned(change: (Set<Address>) -> Set<Address>) {
        val before = _state.value.owned
        val after = change(before)
        if (after == before) return
        update { it.copy(owned = after) }
        val address = _state.value.node?.address ?: return
        val f = ownedFile(address)
        if (after.isEmpty()) {
            f.delete()
            return
        }
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(OwnedNodes.encode(after))
        tmp.renameTo(f)
    }

    private fun loadOwned(address: String): Set<Address> =
        ownedFile(address).takeIf { it.exists() }?.let { OwnedNodes.decode(it.readBytes()) } ?: emptySet()

    private fun ownedFile(address: String) = File(context.filesDir, "records/${address.replace(':', '-')}.owned")

    fun dismissOwnerChange(c: Body.OwnerChanged) = setOwnerChanges { it - c }

    /**
     * The node does not keep an `OWNER_CHANGED`, and a sync does not send it again, so the app keeps
     * each on disk, the last [OwnerNotices.KEPT], until the user dismisses it.
     */
    private fun setOwnerChanges(change: (List<Body.OwnerChanged>) -> List<Body.OwnerChanged>) {
        update { it.copy(ownerChanges = change(it.ownerChanges).takeLast(OwnerNotices.KEPT)) }
        val address = _state.value.node?.address ?: return
        val f = ownersFile(address)
        if (_state.value.ownerChanges.isEmpty()) {
            f.delete()
            return
        }
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(OwnerNotices.encode(_state.value.ownerChanges))
        tmp.renameTo(f)
    }

    private fun loadOwnerChanges(address: String): List<Body.OwnerChanged> =
        ownersFile(address).takeIf { it.exists() }?.let { OwnerNotices.decode(it.readBytes()) } ?: emptyList()

    private fun ownersFile(address: String) = File(context.filesDir, "records/${address.replace(':', '-')}.owners")

    fun dismissAsked(a: Body.Asked) = setAsked { list -> list.filter { it.address != a.address } }

    /**
     * The node does not keep an ASKED, and a sync does not send it again, so the app keeps each on
     * disk until the user saves the address or dismisses it.
     */
    private fun setAsked(change: (List<Body.Asked>) -> List<Body.Asked>) {
        update { it.copy(asked = change(it.asked)) }
        val address = _state.value.node?.address ?: return
        askedPrefs.edit().putString(address, _state.value.asked.joinToString(",") { "${it.address}:${it.why}" }).apply()
    }

    private fun loadAsked(address: String): List<Body.Asked> =
        askedPrefs.getString(address, null).orEmpty().split(',').mapNotNull { entry ->
            val (hex, why) = entry.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val a = org.ternmesh.companion.Address.fromHex(hex) ?: return@mapNotNull null
            Body.Asked(a, why.toIntOrNull() ?: return@mapNotNull null)
        }

    /** The user has seen [peer]'s conversation: tell the node, as far as a READ may reach. */
    fun markRead(peer: Peer) {
        val upTo = Conversations.of(connection.records, peer).last?.id ?: return
        seen[peer] = maxOf(seen[peer] ?: 0, upTo)
        notifier.cancel(peer)
        flushRead()
    }

    /** Sends the READ the conversations seen allow, if it reaches further than the last. */
    private fun flushRead() {
        if (_state.value.phase != Phase.READY) return
        val records = connection.records
        seen.entries.removeAll { (peer, upTo) ->
            records.ordered.none { it.isUnread && it.id <= upTo && Conversations.peerOf(it) == peer }
        }
        val through = Conversations.readThrough(records, seen) ?: return
        if (through == readSent) return
        readSent = through
        // Refused (not now) or unanswered: asked again shortly, or what is on screen stays unread
        // until something else happens. A closed link starts over from 0 when it reopens.
        submit(Body.Read(through)) {
            if (it is Outcome.NoAnswer || it is Outcome.Refused) {
                // Not now: the same `through` stays sent until the retry, so the publish that follows
                // this answer does not send it again at once, and again, while the node is busy.
                handler.postDelayed({
                    if (readSent == through) readSent = 0
                    flushRead()
                }, 3_000)
            }
        }
    }

    /**
     * The user has shared their position from this app with someone through the app's node: the
     * phone may give the node its location while it shares. A permission granted for something
     * else, such as scanning on Android 11 and earlier, or sharing turned on from another client,
     * is not the user choosing this.
     */
    fun chooseToShare() {
        val address = _state.value.node?.address ?: return
        locationPrefs.edit().putBoolean(address, true).apply()
        location.want(wantsLocation(_state.value))
    }

    /**
     * The user has just allowed the app their location, or not: the service may now keep it while
     * the app is away, and the node is given it if it shares its position.
     */
    fun locationPermissionChanged() {
        val s = _state.value
        if (s.node != null && !s.phase.stayingOff) NodeService.start(context)
        location.want(wantsLocation(s))
    }

    /**
     * Whether the node is given the phone's position: while it is synced, speaks positions, and
     * shares its own with someone. It rounds the fix itself for each of them.
     */
    private fun wantsLocation(s: NodeState) = s.phase == Phase.READY && (s.version ?: 0) >= 5 &&
        (s.records.sharing.isNotEmpty() || s.records.groupSharing.isNotEmpty()) &&
        s.node?.let { locationPrefs.getBoolean(it.address, false) } == true

    /** Asks ternmesh.org for the latest release, and finds the image for the node's board and region. */
    fun checkForUpdate() {
        val s = _state.value
        val board = s.board?.takeIf { it.isNotEmpty() } ?: return
        if (busyUpdating(s.update)) return
        // Over the link or by the bootloader, as INFO says; a node of version 11 or earlier with a board, over the link.
        val way = updateBy(board, s.updatedBy)
        if (way == UpdateBy.NONE) return
        setUpdate(FirmwareUpdate.Checking)
        checkedAgainst = s.release
        // What the check is for, taken now: the node may change before the manifest arrives.
        val region = connection.records.self?.region.orEmpty()
        val release = s.release
        checking?.cancel()
        checking = scope.launch {
            val next = try {
                val manifest = FirmwareDownload.manifest()
                FirmwareUpdate.Checked(manifest.release, manifest.imageFor(board, region, way), manifest.offerTo(release))
            } catch (e: IOException) {
                FirmwareUpdate.CheckFailed
            }
            // Only the latest check answers, and not after its node was let go of.
            if (checking === coroutineContext.job && _state.value.update == FirmwareUpdate.Checking) {
                checking = null
                if (next is FirmwareUpdate.Checked) checked = next
                setUpdate(next)
            }
        }
    }

    /**
     * Downloads [image], checks it is the one the manifest names, and gives it to the node. The
     * update goes on across a dropped link, and after the node restarts into the image, the release
     * its `INFO` gives says whether it runs it.
     */
    /**
     * Whether the node is still what [image] was chosen for, asked before downloading and again
     * before sending: its region may have changed since the check, by this client or another, and
     * so may its firmware, so that what was newer is not. An image is for one board and one region.
     */
    private fun stillFor(image: Manifest.Image): Boolean {
        val s = _state.value
        if (image.board.equals(s.board, ignoreCase = true) &&
            image.region.equals(connection.records.self?.region, ignoreCase = true) && s.release == checkedAgainst &&
            image.update == updateBy(s.board, s.updatedBy)
        ) {
            return true
        }
        checked = null // what it offered is no longer for this node: dismissing goes back to checking afresh
        setUpdate(FirmwareUpdate.Failed(FirmwareFailure.CHANGED))
        return false
    }

    /**
     * [insisted]: the user asked for an image not signed by a release key this app holds, after being
     * told it may not be the release's. Without it only a signed one is sent; and an unsigned one is
     * never sent to a node that takes only signed images (`install`).
     */
    fun startUpdate(release: String, image: Manifest.Image, insisted: Boolean = false) {
        if (busyUpdating(_state.value.update)) return
        if (!stillFor(image)) return
        when (install(image.signed(), _state.value.version, image.update)) {
            Install.OFFER -> {}
            Install.WARN -> if (!insisted) return
            Install.REFUSE -> return setUpdate(FirmwareUpdate.Failed(FirmwareFailure.UNSIGNED))
        }
        // The service keeps the process, and so the link, while the app is not on screen.
        NodeService.start(context)
        setUpdate(FirmwareUpdate.Downloading(release, 0, image.size))
        downloading = scope.launch {
            val bytes = try {
                FirmwareDownload.image(image) { received ->
                    handler.post {
                        val u = _state.value.update
                        if (u is FirmwareUpdate.Downloading && received > u.received) setUpdate(u.copy(received = received))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: FirmwareDownload.Corrupt) {
                if (isActive) setUpdate(FirmwareUpdate.Failed(FirmwareFailure.CORRUPT))
                return@launch
            } catch (e: IOException) {
                // A read cut off by cancelling can end this way too: then a newer download may be the one shown.
                if (isActive) setUpdate(FirmwareUpdate.Failed(FirmwareFailure.DOWNLOAD))
                return@launch
            } finally {
                // A download cancelled and replaced lets go of nothing but itself.
                if (downloading === coroutineContext.job) downloading = null
            }
            // Cancelled while its last read was under way: it sends nothing.
            coroutineContext.ensureActive()
            if (!stillFor(image)) return@launch
            // The bytes are the manifest's, so their digest is the one signed; checked again over
            // the bytes themselves, before the node is asked for anything. A bootloader checks no
            // signature, so this is the only check an nRF52840 node's package has.
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            if (Signatures.check(digest, image.signatureBytes) != Signed.VERIFIED && !insisted) {
                return@launch setUpdate(FirmwareUpdate.Failed(FirmwareFailure.CORRUPT))
            }
            if (image.update == UpdateBy.BOOTLOADER) {
                val pkg = try {
                    DfuPackage.read(bytes)
                } catch (e: IllegalArgumentException) {
                    return@launch setUpdate(FirmwareUpdate.Failed(FirmwareFailure.CORRUPT))
                }
                val b = BootloaderUpdater(pkg)
                bootUpdater = b
                updatingTo = release
                updateImage = image
                b.onChange = ::bootloaderChanged
                resumeWhenSynced = true
                if (_state.value.phase == Phase.READY) resumeIfStillFor()
                return@launch
            }
            // The signature goes to a node of version 13 or later, which checks it again.
            val u = Updater(bytes, image.signatureBytes)
            updater = u
            updatingTo = release
            updateImage = image
            u.onChange = ::updaterChanged
            // It goes on once the node has synced, which may be now.
            resumeWhenSynced = true
            if (_state.value.phase == Phase.READY) resumeIfStillFor()
        }
    }

    /** Stops downloading or sending. The node keeps what it was sent until it restarts or another update begins. */
    fun cancelUpdate() {
        downloading?.cancel()
        downloading = null
        val u = updater
        if (u != null) {
            u.cancel()
        } else if (_state.value.update is FirmwareUpdate.Downloading) {
            setUpdate(checked ?: FirmwareUpdate.Idle)
        }
    }

    /** Puts away what an update ended with, back to the last check. */
    fun dismissUpdate() {
        val u = _state.value.update
        if (u is FirmwareUpdate.BootloaderStopped) return leaveBootloader()
        if (busyUpdating(u)) return
        setUpdate(if (u is FirmwareUpdate.Done || u is FirmwareUpdate.NotRunning) FirmwareUpdate.Idle else checked ?: FirmwareUpdate.Idle)
    }

    private fun busyUpdating(u: FirmwareUpdate) = u is FirmwareUpdate.Downloading || u is FirmwareUpdate.Sending ||
        u is FirmwareUpdate.Restarting || u == FirmwareUpdate.Checking || u is FirmwareUpdate.Bootloader ||
        u is FirmwareUpdate.BootloaderStopped

    /** Drops any update: another node is chosen. */
    private fun stopUpdate() {
        checking?.cancel()
        checking = null
        downloading?.cancel()
        downloading = null
        updater?.let {
            it.onChange = {}
            it.cancel()
        }
        updater = null
        dropBootloader()
        updatingTo = null
        updateImage = null
        checked = null
        setUpdate(FirmwareUpdate.Idle)
    }

    private fun updaterChanged(u: Updater) {
        val release = updatingTo ?: return
        val next = when (val s = u.state) {
            UpdateState.Beginning, UpdateState.Sending, UpdateState.Waiting ->
                FirmwareUpdate.Sending(release, u.acknowledged, u.size, waiting = s == UpdateState.Waiting)
            // Every byte is the node's: what is left is its answer to UPDATE_END.
            UpdateState.Ending -> FirmwareUpdate.Sending(release, u.size, u.size, waiting = false)
            UpdateState.Restarting -> FirmwareUpdate.Restarting(release, confirmed = true)
            UpdateState.Unknown -> FirmwareUpdate.Restarting(release, confirmed = false)
            is UpdateState.Refused -> FirmwareUpdate.Refused(s.code)
            UpdateState.NotTrusted -> FirmwareUpdate.NotTrusted
            is UpdateState.Failed -> FirmwareUpdate.Failed(FirmwareFailure.UNSUPPORTED)
            UpdateState.Cancelled -> checked ?: FirmwareUpdate.Idle
        }
        if (u.isFinished && u.state != UpdateState.Restarting && u.state != UpdateState.Unknown) {
            updater = null
            updatingTo = null
            updateImage = null
        }
        setUpdate(next)
    }

    /** The node answered HELLO: an update goes on, or one that ended is judged by the release the node now runs. */
    private fun updateOnReady(e: ConnectionEvent.Ready) {
        bootUpdater?.let { b ->
            // Not yet asked: it is, once the node has synced, if the node is still what the image is for.
            if (b.state == BootloaderState.Waiting) {
                resumeWhenSynced = true
                return
            }
            if (b.state == BootloaderState.Asking) return
            // Back from its bootloader: the image started, or the node went back to its firmware.
            val wanted = updatingTo.orEmpty()
            val started = b.state == BootloaderState.Started
            dropBootloader()
            updatingTo = null
            updateImage = null
            checked = null
            setUpdate(if (e.release == wanted) FirmwareUpdate.Done(wanted) else FirmwareUpdate.NotRunning(e.release.orEmpty(), wanted, started))
            return
        }
        val u = updater ?: return
        if (u.state == UpdateState.Restarting || u.state == UpdateState.Unknown) {
            val wanted = updatingTo.orEmpty()
            val confirmed = u.state == UpdateState.Restarting
            updater = null
            updatingTo = null
            setUpdate(
                if (e.release == wanted) {
                    FirmwareUpdate.Done(wanted)
                } else {
                    FirmwareUpdate.NotRunning(e.release.orEmpty(), wanted, confirmed)
                },
            )
            checked = null
        } else if (u.state == UpdateState.Ending) {
            u.resume(connection) // the node restarted with UPDATE_END unanswered: not known to have taken
        } else if (!u.isFinished) {
            resumeWhenSynced = true // after the sync, which says whether the node is still what it was chosen for
        }
    }

    /**
     * An update waiting for the node goes on, once the node has synced, if the node is still what
     * its image was chosen for: while the link was down another client may have changed its region
     * or its firmware. If not, it stops.
     */
    private fun resumeIfStillFor() {
        val b = bootUpdater
        if (b != null && b.state == BootloaderState.Waiting) {
            val image = updateImage ?: return
            if (!resumeWhenSynced) return
            resumeWhenSynced = false
            if (stillFor(image)) {
                b.ask(connection)
                scheduleTick()
            } else {
                dropBootloader()
                updatingTo = null
                updateImage = null
            }
            return
        }
        val u = updater ?: return
        val image = updateImage ?: return
        if (!resumeWhenSynced || u.isFinished) return
        resumeWhenSynced = false
        if (stillFor(image)) {
            u.resume(connection)
            scheduleTick()
        } else {
            u.onChange = {}
            u.cancel()
            updater = null
            updatingTo = null
            updateImage = null
        }
    }

    private fun bootloaderChanged(b: BootloaderUpdater) {
        if (b !== bootUpdater) return
        val release = updatingTo ?: return
        when (val s = b.state) {
            BootloaderState.Waiting -> {}
            BootloaderState.Asking -> setUpdate(FirmwareUpdate.Bootloader(release, BootloaderStep.ASKING))
            // Off the connection's callback before its link is let go of.
            is BootloaderState.Restarting -> handler.post(::enterBootloader)
            is BootloaderState.Sending -> {
                val size = b.pkg.imageSize.toLong()
                val next = when (val d = s.dfu) {
                    DfuState.Idle -> FirmwareUpdate.Bootloader(release, BootloaderStep.CONNECTING)
                    DfuState.Erasing -> FirmwareUpdate.Bootloader(release, BootloaderStep.ERASING)
                    DfuState.Initialising -> FirmwareUpdate.Bootloader(release, BootloaderStep.SENDING, 0, size)
                    is DfuState.Sending -> FirmwareUpdate.Bootloader(release, BootloaderStep.SENDING, d.sent, d.size)
                    else -> FirmwareUpdate.Bootloader(release, BootloaderStep.CHECKING, size, size)
                }
                // Each packet is a change; the screens are told of each percent.
                val shown = _state.value.update
                if (shown !is FirmwareUpdate.Bootloader || shown.step != next.step || percent(shown) != percent(next)) setUpdate(next)
                scheduleDfuTick()
            }
            BootloaderState.Started -> {
                // The bootloader starts the image, and the node comes back as itself: its INFO says which it runs.
                handler.removeCallbacks(dfuTick)
                bootLink.close()
                setUpdate(FirmwareUpdate.Restarting(release, confirmed = true))
                val node = _state.value.node ?: return
                update { it.copy(phase = Phase.CONNECTING, problem = null) }
                link.connect(node.address, waitForIt = true)
            }
            is BootloaderState.Stopped -> {
                handler.removeCallbacks(dfuTick)
                bootLink.close()
                setUpdate(FirmwareUpdate.BootloaderStopped(release, BootloaderFailure.STOPPED, s.dfu))
            }
            is BootloaderState.Refused -> {
                dropBootloader()
                updatingTo = null
                updateImage = null
                setUpdate(FirmwareUpdate.Refused(s.code))
            }
            is BootloaderState.Failed -> {
                dropBootloader()
                updatingTo = null
                updateImage = null
                setUpdate(FirmwareUpdate.Failed(FirmwareFailure.UNSUPPORTED))
            }
        }
    }

    /**
     * The node said it restarts into its bootloader, or did not answer and may be: the app lets go
     * of the node's link, which is going anyway, and looks for the bootloader.
     */
    private fun enterBootloader() {
        if (bootUpdater == null) return
        update { it.copy(phase = Phase.BOOTLOADER, problem = null) }
        handler.removeCallbacks(tick)
        link.disconnect()
        connection.close()
        save()
        findBootloader()
    }

    /** Looks for the bootloader, links to it, and gives it the image: after the node restarted into it, or to try again. */
    private fun findBootloader() {
        val b = bootUpdater ?: return
        val release = updatingTo ?: return
        val node = _state.value.node ?: return
        setUpdate(FirmwareUpdate.Bootloader(release, BootloaderStep.FINDING))
        bootScan.find(node.address) { address ->
            if (bootUpdater !== b) return@find
            if (address == null) return@find setUpdate(FirmwareUpdate.BootloaderStopped(release, BootloaderFailure.NOT_FOUND))
            setUpdate(FirmwareUpdate.Bootloader(release, BootloaderStep.CONNECTING))
            bootLink.connect(address) { ready ->
                if (bootUpdater !== b) return@connect
                if (!ready) return@connect setUpdate(FirmwareUpdate.BootloaderStopped(release, BootloaderFailure.NO_LINK))
                b.send(bootLink, SystemClock::elapsedRealtime)
                scheduleDfuTick()
            }
        }
    }

    /** Tries the bootloader again, after an update by it stopped: it waits for the image, and takes it afresh. */
    fun retryBootloader() {
        if (_state.value.update !is FirmwareUpdate.BootloaderStopped || _state.value.phase != Phase.BOOTLOADER) return
        NodeService.start(context)
        findBootloader()
    }

    /**
     * Gives up on an update by the bootloader that stopped, and connects to the node again. A node
     * still in its bootloader does not answer until the bootloader is given an image; one that went
     * back to its firmware answers, and its release says it was not updated.
     */
    private fun leaveBootloader() {
        dropBootloader()
        updatingTo = null
        updateImage = null
        setUpdate(checked ?: FirmwareUpdate.Idle)
        val node = _state.value.node ?: return
        if (_state.value.phase == Phase.BOOTLOADER) connectTo(node, waitForIt = true)
    }

    private fun dropBootloader() {
        bootUpdater?.let {
            it.onChange = {}
            it.cancel()
        }
        bootUpdater = null
        bootScan.stop()
        bootLink.close()
        handler.removeCallbacks(dfuTick)
    }

    /** The transfer's timers: at its next deadline, and at least every second, since a step's deadline is set without a change to say so. */
    private val dfuTick = Runnable {
        bootUpdater?.dfu?.tick()
        scheduleDfuTick()
    }

    private fun scheduleDfuTick() {
        handler.removeCallbacks(dfuTick)
        val dfu = bootUpdater?.dfu ?: return
        if (dfu.isFinished) return
        val wait = dfu.nextDeadline?.let { it - SystemClock.elapsedRealtime() } ?: 1_000L
        handler.postDelayed(dfuTick, wait.coerceIn(0L, 1_000L))
    }

    /** Says where the update is, and in the service's notification, each time its percentage changes. */
    private fun setUpdate(u: FirmwareUpdate) {
        val before = _state.value.update
        update { it.copy(update = u) }
        if (before::class != u::class || percent(before) != percent(u)) NodeService.refresh(context, _state.value)
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
        seen.clear()
        queuedFloor = 0
        notified.clear()
        notified += records.items.keys
        arrived.clear()
    }

    private fun connectTo(node: ChosenNode, waitForIt: Boolean) {
        prefs.edit().remove(KEY_DISCONNECTED).remove(KEY_OFF).apply()
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
                // The node restarting into its bootloader drops the link: the app is at the bootloader now.
                if (_state.value.phase == Phase.BOOTLOADER) return
                connection.close()
                handler.removeCallbacks(tick)
                save()
                val node = _state.value.node
                if (s.why == LinkFailure.LOST && node != null) {
                    // Out of range, or the node restarted: Android reconnects when it is back.
                    update { it.copy(phase = Phase.CONNECTING, problem = Problem.Link(s.why)) }
                    handler.postDelayed({ reconnect(node) }, 1_000)
                } else {
                    update { it.copy(phase = Phase.STOPPED, problem = Problem.Link(s.why)) }
                }
            }
        }
    }

    /** Connects again to [node] after the link dropped, if it is still the node and still wanted. */
    private fun reconnect(node: ChosenNode) {
        val s = _state.value
        // By address: a rename since the retry was set changes the label, not the node.
        if (s.node?.address == node.address && !s.phase.stayingOff && s.phase != Phase.BOOTLOADER) {
            link.connect(node.address, waitForIt = true)
        }
    }

    private fun event(e: ConnectionEvent) {
        when (e) {
            is ConnectionEvent.Ready -> {
                update {
                    it.copy(
                        phase = Phase.SYNCING, lockedOut = false, version = e.version, firmware = e.firmware, board = e.board,
                        release = e.release, updatedBy = e.update, problem = null,
                    )
                }
                updateOnReady(e)
            }
            is ConnectionEvent.News -> news(e.body)
            ConnectionEvent.Synced -> {
                update { it.copy(phase = Phase.READY, lockedOut = false) }
                resumeIfStillFor()
                save()
                viewing?.let(::markRead)
            }
            // A node whose Bluetooth is locked takes nothing over it but HELLO and PING: it says so here.
            is ConnectionEvent.SyncRefused -> if (e.code == ErrorCode.LOCKED) update { it.copy(lockedOut = true) }
            is ConnectionEvent.Refused -> {
                link.disconnect()
                update { it.copy(phase = Phase.STOPPED, problem = Problem.Refused(e.code)) }
            }
            ConnectionEvent.Gone -> {
                link.disconnect()
                if (_state.value.phase == Phase.BOOTLOADER) return
                val node = _state.value.node ?: return
                update { it.copy(phase = Phase.CONNECTING, problem = Problem.Gone) }
                handler.postDelayed({ reconnect(node) }, 1_000)
            }
        }
        publish()
    }

    private fun news(body: Body) {
        if (body is Body.Asked) {
            setAsked { list -> list.filter { it.address != body.address } + body }
            return
        }
        // A request to the node managed over the mesh: where its message is, and what came back.
        if (body is Body.State || body is Body.Managed) {
            remote?.let { r ->
                r.receive(body)
                publishRemote(r)
                scheduleTick()
            }
            if (body is Body.Managed) return
        }
        if (body is Body.OwnerChanged) {
            // Someone at a node, or one of its owners, changed who may manage it: the user may not
            // have, and is told, here and in a notification.
            setOwnerChanges { OwnerNotices.add(it, body) }
            // And whether the app's node is now an owner of that node, or no longer one.
            setOwned { OwnedNodes.afterNotice(it, connection.records.self?.address, body) }
            notifier.ownerChanged(connection.records, body)
            return
        }
        if (body is Body.Forgotten) forgotten(Peer.Contact(body.contact), body.through)
        if (body is Body.GroupForgotten) forgotten(Peer.Group(body.group), body.through)
        if (body is Body.Position || body is Body.GroupPosition || body is Body.Sharing || body is Body.GroupSharing ||
            body is Body.Card || body is Body.Fix
        ) {
            arrived[body] = SystemClock.elapsedRealtime()
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
        // An unanswered send the records now show the node holding went: it needs no retry.
        val records = connection.records
        if (_state.value.unanswered.any { Conversations.holdsSent(records, it.peer, it.text, it.after) }) {
            setUnanswered { list -> list.filterNot { Conversations.holdsSent(records, it.peer, it.text, it.after) } }
        }
        val held = records.positions.values + records.groupPositions.values + records.sharing.values + records.groupSharing.values +
            records.cards.values + listOfNotNull(records.fix)
        arrived.keys.retainAll(held.toSet())
        update {
            it.copy(
                records = connection.records.copy(), setUp = it.node?.let { n -> setUp(n.address, records) } ?: true,
                arrived = arrived.toMap(),
            )
        }
        NodeService.refresh(context, _state.value)
        // What was seen may now be readable: news read on another client, or a sync come in.
        handler.post(::flushRead)
    }

    private val tick = Runnable {
        connection.tick()
        remote?.let { r ->
            r.tick()
            publishRemote(r)
        }
        scheduleTick()
    }

    private fun scheduleTick() {
        handler.removeCallbacks(tick)
        val at = listOfNotNull(connection.nextDeadline, remote?.nextDeadline).minOrNull() ?: return
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
        val before = _state.value
        _state.value = change(before)
        // The service's notification says where the link is: it follows every change of phase, and
        // says what a node managed over the mesh is waiting for.
        if (_state.value.phase != before.phase || managingKey(_state.value) != managingKey(before)) NodeService.refresh(context, _state.value)
        location.want(wantsLocation(_state.value))
    }

    /** What the service's notification says of managing changes only with these. */
    private fun managingKey(s: NodeState) = s.managed?.let { Triple(it.address, it.checking, it.asking != null || it.settleUntil != null) }

    companion object {
        private const val KEY_ADDRESS = "address"
        private const val KEY_NAME = "name"
        private const val KEY_DISCONNECTED = "disconnected"

        /** With [KEY_DISCONNECTED]: the app stays off the node because the user turned it off. */
        private const val KEY_OFF = "off"
    }
}
