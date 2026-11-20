// Managing a node over the mesh (draft/managing.md in ternmesh/spec, version 17), the client's half:
// one companion request at a time goes to the managed node inside a MANAGE, and its answer comes back
// in MANAGED, as companion frames each after a length.
//
// Like Connection, a Remote does no I/O and keeps no time of its own. The app gives it a way to make
// a request of its own node, hands it each news frame its own node sends, and calls tick() by
// nextDeadline. Everything happens on the caller's thread.
package org.ternmesh.companion

// Remote's own companion object would take the bare name.
import org.ternmesh.companion.Companion as Protocol
import kotlin.random.Random

/** A request's and an answer's content, as messages for the node carry them. */
object Managing {
    /** A message for the node of this kind carries a request to manage. */
    const val REQUEST = 0x05

    /** And of this kind, what managing answers. */
    const val ANSWER = 0x06

    /**
     * The content of the message that carries [frame], one companion request. A client gives its
     * node only the frame, in `MANAGE`: the node adds the kind.
     */
    fun request(frame: ByteArray): ByteArray {
        require(frame.isNotEmpty() && frame.size <= Protocol.REQUEST_MAX) { "a request is 1 to ${Protocol.REQUEST_MAX} bytes, not ${frame.size}" }
        return byteArrayOf(REQUEST.toByte()) + frame
    }

    /** The request a message's [content] carries, or null for one a receiver ignores: of no frame, or longer than `REQUEST_MAX`. */
    fun readRequest(content: ByteArray): ByteArray? =
        if (content.size < 2 || content[0].toInt() != REQUEST || content.size - 1 > Protocol.REQUEST_MAX) null
        else content.copyOfRange(1, content.size)

    /**
     * The contents of the answers that carry [frames], in order: as many frames as fit in each,
     * each after its length, and another answer when the next does not fit.
     */
    fun pack(frames: List<ByteArray>): List<ByteArray> {
        val answers = mutableListOf<ByteArray>()
        var current = mutableListOf<ByteArray>()
        var size = 0
        for (f in frames) {
            require(f.isNotEmpty() && 1 + f.size <= Protocol.ANSWER_MAX) { "a frame of ${f.size} bytes does not fit an answer" }
            if (size + 1 + f.size > Protocol.ANSWER_MAX) {
                answers += content(current)
                current = mutableListOf()
                size = 0
            }
            current += f
            size += 1 + f.size
        }
        if (current.isNotEmpty()) answers += content(current)
        return answers
    }

    private fun content(frames: List<ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(ANSWER)
        for (f in frames) {
            out.write(f.size)
            out.write(f)
        }
        return out.toByteArray()
    }

    /**
     * The frames in an answer: a `MANAGED`'s `answer`, which is the content after its kind. Null
     * for one a receiver ignores: one that holds no frame, a frame of no bytes, longer than
     * `ANSWER_MAX`, or whose last length runs past its end.
     */
    fun unpack(answer: ByteArray): List<ByteArray>? {
        if (answer.isEmpty() || answer.size > Protocol.ANSWER_MAX) return null
        val frames = mutableListOf<ByteArray>()
        var at = 0
        while (at < answer.size) {
            val n = answer[at].toInt() and 0xFF
            if (n == 0 || at + 1 + n > answer.size) return null
            frames += answer.copyOfRange(at + 1, at + 1 + n)
            at += 1 + n
        }
        return frames
    }
}

/** What a [Remote] tells the app, besides each request's outcome. */
sealed interface RemoteEvent {
    /** The managed node answered `HELLO`: [version] is its own, and the two speak the lesser of it and the client's. */
    data class Ready(val version: Int, val firmware: String) : RemoteEvent

    /** A sync over the mesh finished: [Remote.records] are the managed node's own state, as of now. */
    data object Synced : RemoteEvent

    /** A request is on its way, or was given up on and goes again: [attempt] counts from 1. */
    data class Trying(val attempt: Int) : RemoteEvent

    /** Where the message carrying the request is, as its `STATE` says: a [MessageState]. */
    data class Carried(val state: Int) : RemoteEvent

    /**
     * A `SET` was answered, and is on trial: the node takes it back unless it hears from an owner
     * again before [until], on the connection's clock. The Remote sends the `PING` that confirms it.
     */
    data class Trial(val until: Long) : RemoteEvent

    /** The node heard from this client after the `SET` on trial: the setting stays. */
    data object Confirmed : RemoteEvent
}

/**
 * The node at [to], managed over the mesh through the client's own node, one of whose owners that
 * node must hold: to anyone else, a node does not answer at all.
 *
 * Each request goes in a `MANAGE`, and is tried again, as a new message, up to [TRIES] times when it
 * was not delivered: each a node acts on over the mesh may be acted on twice without harm. It waits
 * [Protocol.MESH_ANSWER_WAIT_MS] for an answer once the request is delivered. A node that answers
 * `ERROR` 6 has restarted and forgotten this client's `HELLO`: it is said again, and the request
 * asked once more. A `SET` that is answered is on trial, and a `PING` follows it at once to confirm it.
 *
 * @param manage makes a request of the client's own node: [Connection.submit], or the app's own.
 * @param now a clock in milliseconds that only goes forward.
 */
class Remote(
    val to: Address,
    private val manage: (Body, (Outcome) -> Unit) -> Unit,
    private val now: () -> Long,
    /** The version this client speaks to the managed node. */
    val version: Int = Protocol.VERSION,
    private val newRef: () -> Long = { Random.nextLong(1, 0x1_0000_0000L) },
) {
    /** The managed node's own state, as its syncs over the mesh give it, apart from the client's own node's. */
    val records = Records()

    var onEvent: (RemoteEvent) -> Unit = {}

    /** The managed node's version and firmware, from its `INFO` over the mesh. */
    var nodeVersion: Int? = null
        private set
    var firmware: String? = null
        private set

    /** The version both ends speak, once the managed node has said its own. */
    val agreed: Int? get() = nodeVersion?.let { minOf(it, version) }

    /** When the `SET` on trial is taken back, on [now]'s clock, unless the node hears again; null when none is. */
    var trialUntil: Long? = null
        private set

    /** The request on its way, or waiting for its answer; null when none is. */
    val asking: Body? get() = inFlight?.pending?.body

    /** Whether a request is on its way or waiting for its answer. */
    val busy: Boolean get() = inFlight != null || queue.isNotEmpty()

    /**
     * Until when the next request waits, on [now]'s clock, for the node to come back from the
     * restart that applies a role or a power: it can acknowledge nothing for a while after one, and
     * two messages unacknowledged in a row make it start first contact again. Null when nothing waits.
     */
    var settleUntil: Long? = null
        private set

    /** When tick() next has something to do; null when nothing waits. */
    val nextDeadline: Long? get() = inFlight?.deadline ?: settleUntil?.takeIf { queue.isNotEmpty() }

    private class Pending(val body: Body, val then: (Outcome) -> Unit)

    private inner class InFlight(val pending: Pending, val seq: Int, val frame: ByteArray) {
        var attempt = 0
        var id: Long? = null
        var deadline = now() + Protocol.MESH_ANSWER_WAIT_MS

        /** The message carrying it was given up on: it waits a moment for an answer from a copy that arrived, then goes again. */
        var lost = false
    }

    private val queue = ArrayDeque<Pending>()
    private var inFlight: InFlight? = null
    private var seq = 0
    private var syncing = false

    /** The node's count of news on this client's connection when the sync under way began: 0 after `HELLO`, then each `SYNCED`'s. */
    private var newsFrom = 0

    /** The `seq`s of the news the sync under way has brought, which may come in any order, and twice. */
    private val newsSeen = mutableSetOf<Int>()

    /** A `SYNCED` that came before all the news it counts: answers can pass each other on the mesh. */
    private var heldSynced: Body.Synced? = null

    /** Says `HELLO`, then asks for the node's state, before anything else asked. [then] hears what came of the sync, or of a `HELLO` that failed. */
    fun open(then: (Outcome) -> Unit = {}) {
        queue.removeAll { it.body is Body.Hello }
        val sync = Pending(Body.Sync(0), then)
        queue.addFirst(sync)
        queue.addFirst(Pending(Body.Hello(version)) { outcome ->
            if (outcome !is Outcome.Answered && queue.remove(sync)) then(outcome)
        })
        pump()
    }

    /** Asks the node for its own state again: itself, its contacts and neighbours, its airtime, power, fix and sleep. */
    fun sync(then: (Outcome) -> Unit = {}) = submit(Body.Sync(0), then)

    /**
     * Asks [body] of the managed node. One its version, or this client's, does not define is not
     * sent ([Outcome.Unsupported]); one the managed node refuses is [Outcome.Refused], as is one the
     * client's own node refuses to send.
     */
    fun submit(body: Body, then: (Outcome) -> Unit = {}) {
        require(Protocol.isRequest(body.type)) { "${body.typeName} is not a request" }
        if (body.since > (agreed ?: version)) return then(Outcome.Unsupported)
        queue.addLast(Pending(body, then))
        pump()
    }

    /** Gives up on every request, unanswered or waiting: the app no longer manages the node. */
    fun close() {
        val pending = listOfNotNull(inFlight?.pending) + queue
        inFlight = null
        queue.clear()
        for (p in pending) p.then(Outcome.Closed)
    }

    /** One news frame from the client's own node: `STATE` for a request's message, and `MANAGED` from [to]. Anything else is ignored. */
    fun receive(news: Body) {
        val f = inFlight ?: return
        when (news) {
            is Body.State -> if (news.id == f.id) carried(f, news.state)
            is Body.Managed -> if (news.from == to) answered(f, news.answer)
            else -> {}
        }
    }

    /** Gives up on an answer overdue: tries the request again if its message was lost and tries are left. */
    fun tick() {
        val f = inFlight ?: return pump()
        if (now() < f.deadline) return
        heldSynced?.let { synced ->
            // The rest of the sync never came: what did is held, but proves nothing gone.
            heldSynced = null
            newsFrom = synced.news ?: 0
            return finish(f, Outcome.Answered(synced))
        }
        if (f.lost && f.attempt < TRIES) {
            attempt(f)
        } else {
            finish(f, Outcome.NoAnswer)
        }
    }

    private fun pump() {
        if (inFlight != null) return
        settleUntil?.let { if (now() < it) return else settleUntil = null }
        val p = queue.removeFirstOrNull() ?: return
        // A request asked before the HELLO was answered: the node's version is known only now.
        if (p.body.since > (agreed ?: version)) {
            p.then(Outcome.Unsupported)
            return pump()
        }
        val body = if (p.body is Body.Hello) Body.Hello(version) else p.body
        seq = seq % 255 + 1
        val frame = try {
            Codec.encode(Frame(seq, body)).also { require(it.size <= Protocol.REQUEST_MAX) { "longer than a request may be" } }
        } catch (e: IllegalArgumentException) {
            p.then(Outcome.Invalid(e.message ?: body.typeName))
            return pump()
        }
        if (body is Body.Sync) {
            records.beginSync()
            newsSeen.clear()
            heldSynced = null
        }
        syncing = body is Body.Sync
        val f = InFlight(p, seq, frame)
        inFlight = f
        attempt(f)
    }

    private fun attempt(f: InFlight) {
        f.attempt += 1
        f.lost = false
        f.id = null
        f.deadline = now() + Protocol.MESH_ANSWER_WAIT_MS
        onEvent(RemoteEvent.Trying(f.attempt))
        manage(Body.Manage(newRef(), to, f.frame)) { outcome ->
            if (inFlight !== f) return@manage
            when {
                outcome is Outcome.Answered && outcome.body is Body.Queued -> f.id = outcome.body.id
                // The client's own node refused it, or could not be asked: nothing went.
                outcome is Outcome.Answered -> finish(f, Outcome.NoAnswer)
                else -> finish(f, outcome)
            }
        }
    }

    private fun carried(f: InFlight, state: Int) {
        onEvent(RemoteEvent.Carried(state))
        when (state) {
            // Delivered: the answer comes within the wait, or not at all.
            MessageState.DELIVERED -> f.deadline = now() + Protocol.MESH_ANSWER_WAIT_MS
            // Given up: its answer may still come, from a copy that arrived. A moment for it, then again.
            MessageState.NOT_DELIVERED -> {
                f.lost = true
                f.deadline = now() + LOST_GRACE_MS
            }
            // Still on its way: the wait starts again from each step it takes.
            else -> f.deadline = maxOf(f.deadline, now() + Protocol.MESH_ANSWER_WAIT_MS)
        }
    }

    private fun answered(f: InFlight, answer: ByteArray) {
        val frames = Managing.unpack(answer) ?: return
        for (bytes in frames) {
            if (bytes.size < 2) continue
            val type = bytes[0].toInt() and 0xFF
            val frame = try {
                Codec.decode(bytes, agreed ?: version)
            } catch (e: DecodeException) {
                // A frame of a later version, or one spoilt: the node counted it, and it is ignored.
                continue
            }
            if (Protocol.isNews(type)) {
                if (syncing && inFlight === f && newsSeen.add(frame.seq)) records.apply(frame.body)
                heldSynced?.takeIf { inFlight === f && syncComplete(it) }?.let { synced ->
                    heldSynced = null
                    answer(f, synced)
                }
            } else if (Protocol.isAnswer(type) && frame.seq == f.seq && inFlight === f) {
                answer(f, frame.body)
            }
        }
    }

    /** Whether every news frame [synced] counts since the sync began has come. */
    private fun syncComplete(synced: Body.Synced): Boolean {
        val news = synced.news ?: return true
        return newsSeen.size >= ((news - newsFrom) and 0xFF)
    }

    private fun answer(f: InFlight, body: Body) {
        val p = f.pending
        if (p.body is Body.Sync && body is Body.Synced && !syncComplete(body)) {
            // Its news is still on the way: wait for it, as long as for any answer.
            heldSynced = body
            f.deadline = now() + Protocol.MESH_ANSWER_WAIT_MS
            return
        }
        // Any request an owner makes over the mesh ends a trial, whatever it is answered: it reached the node.
        if (trialUntil != null) {
            trialUntil = null
            onEvent(RemoteEvent.Confirmed)
        }
        when {
            body is Body.Error && body.code == ErrorCode.HELLO_FIRST && p.body !is Body.Hello -> {
                // The node restarted, as one does to apply some settings, and forgot this client:
                // say HELLO again, and ask once more. Nothing it refused was acted on.
                inFlight = null
                syncing = false
                records.abandonSync()
                queue.addFirst(p)
                queue.addFirst(Pending(Body.Hello(version)) { outcome -> if (outcome !is Outcome.Answered) failQueued(outcome) })
                return pump()
            }
            p.body is Body.Hello && body is Body.Info -> {
                nodeVersion = body.version
                firmware = body.firmware
                newsFrom = 0
                records.speaking = agreed
                onEvent(RemoteEvent.Ready(body.version, body.firmware))
            }
            p.body is Body.Sync && body is Body.Synced -> {
                newsFrom = body.news ?: 0
                syncing = false
                if (records.finishSync(agreed ?: version)) onEvent(RemoteEvent.Synced)
            }
            p.body is Body.Sync -> records.abandonSync()
            p.body is Body.Set && body == Body.Ok -> {
                val until = now() + Protocol.TRIAL_MS
                trialUntil = until
                // A role or a power the node restarts to apply: the PING waits until it can answer.
                if (p.body.setting.number in RESTARTS) settleUntil = now() + SETTLE_MS
                onEvent(RemoteEvent.Trial(until))
                // The PING that ends the trial, before anything else is asked: if the setting has
                // left the node out of reach, it does not arrive, and the node goes back by itself.
                queue.addFirst(Pending(Body.Ping) {})
            }
        }
        finish(f, if (body is Body.Error) Outcome.Refused(body.code) else Outcome.Answered(body))
    }

    private fun finish(f: InFlight, outcome: Outcome) {
        if (inFlight !== f) return
        inFlight = null
        if (syncing) {
            syncing = false
            records.abandonSync()
        }
        f.pending.then(outcome)
        pump()
    }

    /** A `HELLO` said again was not answered: what waited on it fails too. */
    private fun failQueued(outcome: Outcome) {
        val rest = queue.toList()
        queue.clear()
        for (p in rest) p.then(outcome)
    }

    companion object {
        /** How many times a request goes, as a new message each time, when it is not delivered. */
        const val TRIES = 3

        /**
         * How long a request whose message was given up on waits for an answer from a copy that
         * arrived, before it goes again: tries spaced so, not closer.
         */
        const val LOST_GRACE_MS = 15_000L

        /** The settings a node restarts to apply, when the value changes: role and power. */
        val RESTARTS = setOf(2, 3)

        /** How long after such a `SET` is answered the next request waits: a node can acknowledge nothing for about 30 s after it starts. */
        const val SETTLE_MS = 45_000L
    }
}
