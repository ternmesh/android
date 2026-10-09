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
import org.ternmesh.app.link.LinkFailure
import org.ternmesh.app.link.LinkState
import org.ternmesh.app.ui.outcomeText
import org.ternmesh.companion.Body
// This class's own companion object would take the bare name.
import org.ternmesh.companion.Companion as CompanionProtocol
import org.ternmesh.companion.Connection
import org.ternmesh.companion.ConnectionEvent
import org.ternmesh.companion.Conversations
import org.ternmesh.companion.Item
import org.ternmesh.companion.Manifest
import org.ternmesh.companion.MessageState
import org.ternmesh.companion.Outcome
import org.ternmesh.companion.Peer
import org.ternmesh.companion.Records
import org.ternmesh.companion.RecordsFile
import org.ternmesh.companion.UpdateState
import org.ternmesh.companion.Updater
import java.io.File
import java.io.IOException
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

    /** The user disconnected: the node stays chosen, and nothing connects until they say so. */
    DISCONNECTED,
}

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
    val update: FirmwareUpdate = FirmwareUpdate.Idle,
    /** First contacts the node refused, newest last, until the user deals with them. */
    val asked: List<Body.Asked> = emptyList(),
    val unanswered: List<Unanswered> = emptyList(),
    /** Every node the app keeps records of, most recently chosen first. */
    val known: List<ChosenNode> = emptyList(),
    /** The first-run setup was finished, or skipped, for this node. */
    val setUp: Boolean = true,
    /**
     * When each position and sharing record held arrived, on the elapsed clock: the `age` and
     * `minutes` each gives are as of then.
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

    /** The last check's answer, which a cancelled update goes back to. */
    private var checked: FirmwareUpdate.Checked? = null

    /** The release the node ran when it was last checked for an update: what the offer was weighed against. */
    private var checkedAgainst: String? = null

    private val _state = MutableStateFlow(NodeState())
    val state: StateFlow<NodeState> = _state

    /** The conversation on screen, whose new messages need no notification. */
    var viewing: Peer? = null

    /** When each position and sharing record arrived; see [NodeState.arrived]. */
    private val arrived = mutableMapOf<Body, Long>()

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
            // A node chosen before the app kept a list of them.
            if (!knownPrefs.contains(address)) remember(node)
            adopt(load(address))
            val phase = if (prefs.getBoolean(KEY_DISCONNECTED, false)) Phase.DISCONNECTED else Phase.NONE
            update {
                it.copy(
                    node = node, phase = phase, records = connection.records.copy(),
                    unanswered = loadUnanswered(address), asked = loadAsked(address),
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

    /** Makes [node] the app's node and connects to it. */
    fun choose(node: ChosenNode) {
        if (_state.value.node?.address != node.address) {
            leave()
            prefs.edit().putString(KEY_ADDRESS, node.address).putString(KEY_NAME, node.name).apply()
            adopt(load(node.address))
            update {
                NodeState(
                    node = node, records = connection.records.copy(), unanswered = loadUnanswered(node.address),
                    asked = loadAsked(node.address),
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
    fun disconnect() {
        if (_state.value.node == null) return
        // Before the link closes, so nothing that answers to its closing shows the service again.
        update { it.copy(phase = Phase.DISCONNECTED, problem = null) }
        prefs.edit().putBoolean(KEY_DISCONNECTED, true).apply()
        handler.removeCallbacksAndMessages(null)
        link.disconnect()
        connection.close()
        save()
        NodeService.stop(context)
    }

    /** Drops the link and the node, keeping its records on disk in case it is chosen again. */
    fun leave() {
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
        knownPrefs.edit().remove(address).apply()
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

    /** Adds [node] to the nodes the app keeps, as the one chosen last. */
    private fun remember(node: ChosenNode) {
        knownPrefs.edit().putString(node.address, "${System.currentTimeMillis()}:${node.name.orEmpty()}").apply()
    }

    private fun loadKnown(): List<ChosenNode> = knownPrefs.all.mapNotNull { (address, value) ->
        val (at, name) = (value as? String)?.split(':', limit = 2)?.takeIf { it.size == 2 } ?: return@mapNotNull null
        (at.toLongOrNull() ?: 0L) to ChosenNode(address, name.ifEmpty { null })
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
        if (s.node != null && s.phase != Phase.DISCONNECTED) NodeService.start(context)
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
        setUpdate(FirmwareUpdate.Checking)
        checkedAgainst = s.release
        // What the check is for, taken now: the node may change before the manifest arrives.
        val region = connection.records.self?.region.orEmpty()
        val release = s.release
        checking?.cancel()
        checking = scope.launch {
            val next = try {
                val manifest = FirmwareDownload.manifest()
                FirmwareUpdate.Checked(manifest.release, manifest.imageFor(board, region), manifest.offerTo(release))
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
            image.region.equals(connection.records.self?.region, ignoreCase = true) && s.release == checkedAgainst
        ) {
            return true
        }
        checked = null // what it offered is no longer for this node: dismissing goes back to checking afresh
        setUpdate(FirmwareUpdate.Failed(FirmwareFailure.CHANGED))
        return false
    }

    fun startUpdate(release: String, image: Manifest.Image) {
        if (busyUpdating(_state.value.update)) return
        if (!stillFor(image)) return
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
            val u = Updater(bytes)
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
        if (busyUpdating(_state.value.update)) return
        val u = _state.value.update
        setUpdate(if (u is FirmwareUpdate.Done || u is FirmwareUpdate.NotRunning) FirmwareUpdate.Idle else checked ?: FirmwareUpdate.Idle)
    }

    private fun busyUpdating(u: FirmwareUpdate) = u is FirmwareUpdate.Downloading || u is FirmwareUpdate.Sending ||
        u is FirmwareUpdate.Restarting || u == FirmwareUpdate.Checking

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
        prefs.edit().remove(KEY_DISCONNECTED).apply()
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
        if (s.node == node && s.phase != Phase.DISCONNECTED) link.connect(node.address, waitForIt = true)
    }

    private fun event(e: ConnectionEvent) {
        when (e) {
            is ConnectionEvent.Ready -> {
                update {
                    it.copy(
                        phase = Phase.SYNCING, version = e.version, firmware = e.firmware, board = e.board,
                        release = e.release, problem = null,
                    )
                }
                updateOnReady(e)
            }
            is ConnectionEvent.News -> news(e.body)
            ConnectionEvent.Synced -> {
                update { it.copy(phase = Phase.READY) }
                resumeIfStillFor()
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
        if (body is Body.Position || body is Body.GroupPosition || body is Body.Sharing || body is Body.GroupSharing) {
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
        val held = records.positions.values + records.groupPositions.values + records.sharing.values + records.groupSharing.values
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
        val before = _state.value
        _state.value = change(before)
        // The service's notification says where the link is: it follows every change of phase.
        if (_state.value.phase != before.phase) NodeService.refresh(context, _state.value)
        location.want(wantsLocation(_state.value))
    }

    companion object {
        private const val KEY_ADDRESS = "address"
        private const val KEY_NAME = "name"
        private const val KEY_DISCONNECTED = "disconnected"
    }
}
