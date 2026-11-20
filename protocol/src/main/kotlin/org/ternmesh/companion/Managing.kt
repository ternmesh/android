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

    /**
     * The client's own node took the request (`QUEUED`). A keeper holding it for a node that
     * sleeps sends no `STATE` until the node wakes, so this may be all that is heard for a while.
     */
    data object Queued : RemoteEvent

    /**
     * Where the message carrying the request is, as its `STATE` says: a [MessageState], and while
     * it waits, [reason] and [wait] as a message's are.
     */
    data class Carried(val state: Int, val reason: Int = 0, val wait: Int = 0) : RemoteEvent

    /**
     * The managed node said whether it holds this client's node as an owner: [owner] when it
     * answered anything at all, and not when it answered `ERROR` 15.
     */
    data class Owner(val owner: Boolean) : RemoteEvent

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
 * A node that answers `ERROR` 15 does not take this client's node as an owner: nothing more is asked.
 *
 * A node that sleeps answers only once it wakes: with [wake] and [listen] known, each wait is longer
 * by them, and with whether it sleeps not known, it is [UNKNOWN_WAIT_MS] ([answerWaitMs]).
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

    /** When the last sync over the mesh finished, on [now]'s clock; null before one has. */
    var syncedAt: Long? = null
        private set

    /** When the `SET` on trial is taken back, on [now]'s clock, unless the node hears again; null when none is. */
    var trialUntil: Long? = null
        private set

    /** The request on its way, or waiting for its answer; null when none is. */
    val asking: Body? get() = flights.firstOrNull()?.pending?.body

    /** Whether a request is on its way or waiting for its answer. */
    val busy: Boolean get() = flights.isNotEmpty() || queue.isNotEmpty()

    /**
     * Until when the next request waits, on [now]'s clock, for the node to come back from the
     * restart that applies a role or a power: it can acknowledge nothing for a while after one, and
     * two messages unacknowledged in a row make it start first contact again. Null when nothing waits.
     */
    var settleUntil: Long? = null
        private set

    /**
     * The managed node's seconds between wakes, 0 if it is known not to sleep, null if the client
     * does not know; and how long it listens after each. The app sets them from what it knows, and a
     * sync over the mesh sets them from the node's own `SLEEP`.
     */
    var wake: Int? = null
    var listen: Int = 0

    /** How long a request waits for its answer from each step its message takes: longer by a sleep and a listen for a node that sleeps. */
    val answerWaitMs: Long get() = waitFor(wake, listen)

    /** When the request on its way is given up on unless something more is heard; null when none is. */
    val deadline: Long? get() = flights.minOfOrNull { it.deadline }

    /** When tick() next has something to do; null when nothing waits. */
    val nextDeadline: Long? get() = deadline ?: settleUntil?.takeIf { queue.isNotEmpty() }

    /** A request asked, and what to tell of it. */
    private class Pending(val body: Body, val then: (Outcome) -> Unit)

    private inner class InFlight(val pending: Pending, val seq: Int, val frame: ByteArray) {
        var attempt = 0
        var id: Long? = null
        var deadline = now() + answerWaitMs

        /** The message carrying it was given up on: it waits a moment for an answer from a copy that arrived, then goes again. */
        var lost = false
    }

    private val queue = ArrayDeque<Pending>()

    /** The request on its way, or waiting for its answer: one at a time, as draft/managing.md has it, so at most one. */
    private val flights = mutableListOf<InFlight>()
    private var seq = 0

    /** The `SYNC` on its way, whose news the records take. */
    private var syncFlight: InFlight? = null

    /** The node's count of news on this client's connection when the sync under way began: 0 after `HELLO`, then each `SYNCED`'s. */
    private var newsFrom = 0

    /** The `seq`s of the news the sync under way has brought, which may come in any order, and twice. */
    private val newsSeen = mutableSetOf<Int>()

    /** A `SYNCED` that came before all the news it counts: answers can pass each other on the mesh. */
    private var heldSynced: Body.Synced? = null

    /**
     * Asks for the node's state, before anything else asked; and says `HELLO` first, unless the node
     * has answered one from this client already, which holds until it answers `ERROR` 6. [then]
     * hears what came of the sync, or of a `HELLO` that failed. A node whose state came less than
     * [FRESH_MS] ago, or is on its way, is not asked again: opening its screen once more, as when
     * the app comes back to the front, costs a sleeping node no wake. [then] hears nothing then.
     */
    fun open(then: (Outcome) -> Unit = {}) {
        if (syncing()) return
        if (nodeVersion != null && syncedAt?.let { now() - it < FRESH_MS } == true) return
        queue.removeAll { it.body is Body.Hello }
        if (nodeVersion != null) {
            queue.addFirst(Pending(Body.Sync(0), then))
            return pump()
        }
        val sync = Pending(Body.Sync(0), then)
        queue.addFirst(sync)
        queue.addFirst(Pending(Body.Hello(version)) { outcome ->
            if (outcome !is Outcome.Answered && queue.remove(sync)) then(outcome)
        })
        pump()
    }

    /**
     * Asks the node for its own state again: itself, its contacts and neighbours, its airtime, power,
     * fix and sleep. Not while a sync is already on its way or waiting: [then] hears nothing then.
     */
    fun sync(then: (Outcome) -> Unit = {}) {
        if (syncing()) return
        submit(Body.Sync(0), then)
    }

    /** Whether a sync is on its way, or waiting to go. */
    private fun syncing() = syncFlight != null || queue.any { it.body is Body.Sync }

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

    /** Gives up on every request, unanswered or waiting: the app no longer manages the node. What it has learned of the node stays. */
    fun close() {
        val pending = flights.map { it.pending } + queue
        flights.clear()
        queue.clear()
        if (syncFlight != null) {
            syncFlight = null
            records.abandonSync()
        }
        heldSynced = null
        for (p in pending) p.then(Outcome.Closed)
    }

    /** One news frame from the client's own node: `STATE` for a request's message, and `MANAGED` from [to]. Anything else is ignored. */
    fun receive(news: Body) {
        when (news) {
            is Body.State -> flights.find { it.id == news.id }?.let { carried(it, news) }
            is Body.Managed -> if (news.from == to && flights.isNotEmpty()) answered(news.answer)
            else -> {}
        }
    }

    /** Gives up on answers overdue: tries a request again if its message was lost and tries are left. */
    fun tick() {
        for (f in flights.toList()) {
            if (f !in flights || now() < f.deadline) continue
            val synced = heldSynced
            if (f === syncFlight && synced != null) {
                // The rest of the sync never came: what did is held, but proves nothing gone.
                heldSynced = null
                newsFrom = synced.news ?: 0
                finish(f, Outcome.Answered(synced), next = false)
            } else if (f.lost && f.attempt < TRIES) {
                attempt(f)
            } else {
                finish(f, Outcome.NoAnswer, next = false)
            }
        }
        pump()
    }

    private fun pump() {
        while (true) {
            settleUntil?.let { if (now() < it) return else settleUntil = null }
            val p = queue.firstOrNull() ?: return
            // One at a time.
            if (flights.isNotEmpty()) return
            queue.removeFirst()
            // A request asked before the HELLO was answered: the node's version is known only now.
            if (p.body.since > (agreed ?: version)) {
                p.then(Outcome.Unsupported)
                continue
            }
            val body = if (p.body is Body.Hello) Body.Hello(version) else p.body
            seq = seq % 255 + 1
            val frame = try {
                Codec.encode(Frame(seq, body)).also { require(it.size <= Protocol.REQUEST_MAX) { "longer than a request may be" } }
            } catch (e: IllegalArgumentException) {
                p.then(Outcome.Invalid(e.message ?: body.typeName))
                continue
            }
            // A HELLO starts the node's count of news on this connection again.
            if (body is Body.Hello) newsFrom = 0
            val f = InFlight(p, seq, frame)
            if (body is Body.Sync) {
                records.beginSync()
                newsSeen.clear()
                heldSynced = null
                syncFlight = f
            }
            flights += f
            attempt(f)
        }
    }

    private fun attempt(f: InFlight) {
        f.attempt += 1
        f.lost = false
        f.id = null
        f.deadline = now() + answerWaitMs
        onEvent(RemoteEvent.Trying(f.attempt))
        manage(Body.Manage(newRef(), to, f.frame)) { outcome ->
            if (f !in flights) return@manage
            when {
                outcome is Outcome.Answered && outcome.body is Body.Queued -> {
                    f.id = outcome.body.id
                    onEvent(RemoteEvent.Queued)
                }
                // The client's own node refused it, or could not be asked: nothing went.
                outcome is Outcome.Answered -> finish(f, Outcome.NoAnswer)
                else -> finish(f, outcome)
            }
        }
    }

    private fun carried(f: InFlight, news: Body.State) {
        onEvent(RemoteEvent.Carried(news.state, news.reason, news.wait))
        when (news.state) {
            // Delivered: the answer comes within the wait, or not at all.
            MessageState.DELIVERED -> f.deadline = now() + answerWaitMs
            // Given up: its answer may still come, from a copy that arrived. A moment for it, then again.
            MessageState.NOT_DELIVERED -> {
                f.lost = true
                f.deadline = now() + LOST_GRACE_MS
            }
            // Still on its way: the wait starts again from each step it takes.
            else -> f.deadline = maxOf(f.deadline, now() + answerWaitMs)
        }
    }

    private fun answered(answer: ByteArray) {
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
                val s = syncFlight ?: continue
                if (newsSeen.add(frame.seq)) records.apply(frame.body)
                heldSynced?.takeIf { syncComplete(it) }?.let { synced ->
                    heldSynced = null
                    answer(s, synced)
                }
            } else if (Protocol.isAnswer(type)) {
                flights.find { it.seq == frame.seq }?.let { answer(it, frame.body) }
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
        if (body is Body.Error && body.code == ErrorCode.NOT_AN_OWNER) {
            // Not one of its owners: asking again, or anything else, comes to the same.
            onEvent(RemoteEvent.Owner(false))
            val refused = Outcome.Refused(body.code)
            for (other in flights.toList()) finish(other, refused, next = false)
            return failQueued(refused)
        }
        // Whatever else it answered, it answers only its owners.
        onEvent(RemoteEvent.Owner(true))
        if (p.body is Body.Sync && body is Body.Synced && !syncComplete(body)) {
            // Its news is still on the way: wait for it, as long as for any answer.
            heldSynced = body
            f.deadline = now() + answerWaitMs
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
                flights.remove(f)
                if (syncFlight === f) {
                    syncFlight = null
                    records.abandonSync()
                }
                nodeVersion = null
                queue.addFirst(p)
                queue.addFirst(Pending(Body.Hello(version)) { outcome -> if (outcome !is Outcome.Answered) failQueued(outcome) })
                return pump()
            }
            p.body is Body.Hello && body is Body.Info -> {
                nodeVersion = body.version
                firmware = body.firmware
                records.speaking = agreed
                onEvent(RemoteEvent.Ready(body.version, body.firmware))
            }
            p.body is Body.Sync && body is Body.Synced -> {
                newsFrom = body.news ?: 0
                syncFlight = null
                if (records.finishSync(agreed ?: version)) {
                    syncedAt = now()
                    // Its own word on whether it sleeps is better than any the app had; a node of
                    // version 14 or earlier, which sends no SLEEP, does not.
                    wake = sleepsFor(records.sleep)
                    listen = records.sleep?.listen?.takeIf { wake != 0 } ?: 0
                    onEvent(RemoteEvent.Synced)
                }
            }
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

    private fun finish(f: InFlight, outcome: Outcome, next: Boolean = true) {
        if (!flights.remove(f)) return
        if (syncFlight === f) {
            syncFlight = null
            records.abandonSync()
        }
        f.pending.then(outcome)
        if (next) pump()
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

        /** How long a node's state from its last sync stands, before opening it asks for it again: [sync] asks at any time. */
        const val FRESH_MS = 600_000L

        /** How long a request waits for its answer, from each step its message takes, to a node that may sleep for all the client knows. */
        const val UNKNOWN_WAIT_MS = 600_000L

        /**
         * How long a request waits for its answer, from each step its message takes, to a node that
         * sleeps [wake] seconds between wakes, 0 for one that does not, null for one the client does
         * not know of, and listens [listen] after each: it may not hear it, or answer, until it next
         * wakes, and its keeper holds the request until then.
         */
        /**
         * Seconds between wakes of a node whose own `SLEEP` is [sleep]: its wake when it is to sleep,
         * as a leaf, even while it listens all the time for a keeper (it sleeps again once it has
         * one); 0 when it is not to sleep, or is a relay; and 0 from a node that sends no `SLEEP`.
         */
        fun sleepsFor(sleep: Body.Sleep?): Int =
            if (sleep != null && sleep.wake > 0 && sleep.state != Sleeping.AWAKE) sleep.wake else 0

        fun waitFor(wake: Int?, listen: Int): Long = when {
            wake == null -> UNKNOWN_WAIT_MS
            wake > 0 -> Protocol.MESH_ANSWER_WAIT_MS + (wake.toLong() + listen.coerceAtLeast(0)) * 1000
            else -> Protocol.MESH_ANSWER_WAIT_MS
        }
    }
}

/**
 * What a request over the mesh waits for now, for a client to say: from the `STATE` of the message
 * that carries it, and nothing more. Whether the node sleeps is [ManageStep.sleeps]'s.
 */
sealed interface ManageStep {
    /** Handed to the client's own node, which has not yet taken it (`QUEUED`). */
    data object Starting : ManageStep

    /** Taken by the client's own node, which has not yet said where it is. */
    data object Queued : ManageStep

    /** Waiting for a session: first contact with the managed node is under way. */
    data object FirstContact : ManageStep

    /** Waiting for a route to the managed node. */
    data object FindingRoute : ManageStep

    /** Waiting for the region's limit (3), the airtime budget (4) or the radio (5): [reason] as a message's, [wait] seconds, 0 if unknown. */
    data class Airtime(val reason: Int, val wait: Int) : ManageStep

    /** Waiting, for nothing the node can name. */
    data object Waiting : ManageStep

    /** On the air, not yet delivered. */
    data object Sent : ManageStep

    /** Delivered: the answer is all that is left. */
    data object Delivered : ManageStep

    /** Delivered to the keeper of a node known to sleep, which holds it until the node wakes, every [wake] seconds. */
    data class Held(val wake: Int) : ManageStep

    /** Delivered a while ago, and no answer yet, from a node not known to sleep: it may be asleep, its request held. */
    data object MaybeAsleep : ManageStep

    /** Given up on: it goes again, if tries are left. */
    data object NotDelivered : ManageStep

    companion object {
        /** How long after delivery, with no answer, a node not known to sleep may be asleep. */
        const val HELD_AFTER_MS = 20_000L

        /**
         * The step a message's [state], a [MessageState] or null before any, with its [reason] and
         * [wait], says it is at. Once delivered, a node that sleeps [wake] seconds between wakes has
         * it held by its keeper until it wakes; and one not known to sleep, [sinceDeliveredMs] after
         * delivery with no answer, may be asleep. Before any `STATE`, [sinceQueuedMs] is how long
         * since the client's own node took it, null if it has not: a keeper holding it for a node
         * that sleeps says nothing until the node wakes, so it is read the same way.
         */
        fun of(
            state: Int?,
            reason: Int = 0,
            wait: Int = 0,
            wake: Int? = null,
            sinceDeliveredMs: Long? = null,
            sinceQueuedMs: Long? = null,
        ): ManageStep = when (state) {
            null -> when {
                sinceQueuedMs == null -> Starting
                wake != null && wake > 0 -> Held(wake)
                sinceQueuedMs >= HELD_AFTER_MS -> MaybeAsleep
                else -> Queued
            }
            MessageState.WAITING -> when (reason) {
                1 -> FindingRoute
                2 -> FirstContact
                3, 4, 5 -> Airtime(reason, wait)
                else -> Waiting
            }
            MessageState.SENT -> Sent
            MessageState.DELIVERED -> when {
                wake != null && wake > 0 -> Held(wake)
                sinceDeliveredMs != null && sinceDeliveredMs >= HELD_AFTER_MS -> MaybeAsleep
                else -> Delivered
            }
            MessageState.NOT_DELIVERED -> NotDelivered
            else -> Waiting
        }

        /**
         * Seconds between the managed node's wakes when it is known to sleep, 0 when it is known not
         * to, and null when the client does not know. The [contactWake] its `CONTACT` in the client's
         * own node gave comes first, when it is not 0: what it last said of itself in its session,
         * news the client's node sends whenever it changes. Then its own [sleep] from a sync over
         * the mesh, which may be older; a leaf that is to sleep counts as sleeping even while it
         * listens for a keeper. A contact's 0 may mean it said nothing. Nothing is guessed.
         */
        fun sleeps(sleep: Body.Sleep?, contactWake: Int?): Int? = when {
            contactWake != null && contactWake > 0 -> contactWake
            sleep != null -> Remote.sleepsFor(sleep)
            else -> null
        }

        /** Whether, at [step], a node that sleeps may be what it waits for, and is worth saying so: before delivery. */
        fun waitsForWake(step: ManageStep): Boolean =
            step != Delivered && step != NotDelivered && step !is Held && step != MaybeAsleep
    }
}
