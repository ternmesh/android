// One connection to a node, as the client's half of the specification's conversation: HELLO and
// what both versions define, one request at a time, news counted from the HELLO, a sync again when
// some was missed, and a request at least every IDLE so a node on a serial port does not take the
// client for gone.
//
// It does no I/O and keeps no time of its own. Whatever carries the frames (Android's Bluetooth
// GATT, a serial port, a test) hands it each frame it receives and sends what [Connection.send]
// gives it; whatever runs the app calls tick() by nextDeadline. Everything happens on the caller's
// thread, and none of it is safe to call from two.
package org.ternmesh.companion

import kotlin.random.Random

/** What became of a request. */
sealed interface Outcome {
    /** The node's answer: `OK`, `QUEUED` or `MADE`. */
    data class Answered(val body: Body) : Outcome

    /** The node answered `ERROR` with this code. */
    data class Refused(val code: Int) : Outcome

    /** The node's version, or this client's, does not define the request, so it was not sent. */
    data object Unsupported : Outcome

    /** No answer within [Companion.ANSWER_WAIT_MS]. It may have been acted on all the same. */
    data object NoAnswer : Outcome

    /** The connection closed before the request was answered, or was closed when it was made. */
    data object Closed : Outcome

    /** It could not be built: text longer than its field allows. */
    data class Invalid(val why: String) : Outcome
}

/** What a connection tells the app, besides each request's outcome. */
sealed interface ConnectionEvent {
    /** The node answered `HELLO`. [version] is the node's; the connection speaks the lesser of it and its own. */
    data class Ready(val version: Int, val firmware: String) : ConnectionEvent

    /** A news frame, already applied to the records. `ASKED` is one, and is applied to nothing. */
    data class News(val body: Body) : ConnectionEvent

    /** A sync finished: the records are the node's, as of now. */
    data object Synced : ConnectionEvent

    /** The node refused the `HELLO` with this code: 7 if the Bluetooth link's MTU is too small. */
    data class Refused(val code: Int) : ConnectionEvent

    /** The node did not answer in time. Close the link and open it again, then call open(). */
    data object Gone : ConnectionEvent
}

/**
 * @param now a clock in milliseconds that only goes forward, for the protocol's timers.
 * @param wallTime seconds since 1970, to set the node's clock with after each `HELLO`; null to
 *   leave it.
 */
class Connection(
    /** The version this client speaks. */
    val version: Int = Companion.VERSION,
    /** Everything the node has said it holds. Kept across open()s, so a connection that comes back asks only for what is new. */
    val records: Records = Records(),
    private val now: () -> Long,
    private val wallTime: (() -> Long)?,
) {
    /** Sends one frame to the node: one Bluetooth write, or wrapped for a byte stream. */
    var send: (ByteArray) -> Unit = {}
    var onEvent: (ConnectionEvent) -> Unit = {}

    /** The node's version and firmware, from its `INFO`. */
    var nodeVersion: Int? = null
        private set
    var firmware: String? = null
        private set

    /** The version both ends speak, once the node has said its own. */
    val agreed: Int? get() = nodeVersion?.let { minOf(it, version) }

    /** When tick() next has something to do: an answer overdue, or a `PING` due. Null while closed. */
    val nextDeadline: Long?
        get() = inFlight?.deadline ?: if (phase == Phase.OPEN) lastAnswer + Companion.IDLE_MS else null

    private enum class Phase { CLOSED, GREETING, OPEN }

    private var phase = Phase.CLOSED
    private var seq = 0
    private var expectedNews = 0
    /** The greatest `id` held when news was first missed, until a sync asks again from it. */
    private var missedSince: Long? = null
    private var syncWanted = false
    private val queue = ArrayDeque<Pending>()
    private var inFlight: InFlight? = null
    private var lastAnswer = 0L

    private sealed interface Kind {
        data class User(val body: Body) : Kind
        data object Hello : Kind
        data object SetTime : Kind
        data object Sync : Kind
        data object Ping : Kind
    }

    private class Pending(val kind: Kind, val then: (Outcome) -> Unit = {})

    private class InFlight(val pending: Pending, val seq: Int, var deadline: Long)

    /** Starts the conversation on a link that has just opened: `HELLO`, then the clock and a sync. Requests made before the node answers wait for it. */
    fun open() {
        if (phase != Phase.CLOSED) close()
        phase = Phase.GREETING
        nodeVersion = null
        firmware = null
        hello()
    }

    /** The link closed. Every request not yet answered fails with [Outcome.Closed]. */
    fun close() {
        phase = Phase.CLOSED
        records.abandonSync()
        failAll(Outcome.Closed)
    }

    /** Asks the node for anything it holds that this client may not. */
    fun resync() {
        syncWanted = true
        pump()
    }

    /** Makes a request. `HELLO` and `SYNC` are the connection's own: use open() and resync(). */
    fun submit(body: Body, then: (Outcome) -> Unit = {}) {
        require(Companion.isRequest(body.type)) { "${body.typeName} is not a request" }
        require(body !is Body.Hello && body !is Body.Sync) { "the connection says HELLO and SYNC itself" }
        when {
            phase == Phase.CLOSED -> then(Outcome.Closed)
            body.since > (agreed ?: version) -> then(Outcome.Unsupported)
            else -> {
                queue.addLast(Pending(Kind.User(body), then))
                pump()
            }
        }
    }

    /**
     * Sends [text] to [to] as a new message, under a `ref` of its own; returns the `ref`. To try
     * again after [Outcome.NoAnswer], send the same text with the same `ref`: the node sends it once.
     */
    fun sendMessage(text: String, to: Address, ref: Long = newRef(), then: (Outcome) -> Unit = {}): Long {
        submit(Body.Send(ref, to, text), then)
        return ref
    }

    /** Sends [text] to a group, as sendMessage() does to an address. */
    fun sendToGroup(text: String, group: GroupId, ref: Long = newRef(), then: (Outcome) -> Unit = {}): Long {
        submit(Body.SendGroup(ref, group, text), then)
        return ref
    }

    /** One frame from the node. */
    fun receive(bytes: ByteArray) {
        if (phase == Phase.CLOSED || bytes.size < 2) return
        val type = bytes[0].toInt() and 0xFF
        val frameSeq = bytes[1].toInt() and 0xFF
        val frame = try {
            Codec.decode(bytes)
        } catch (e: DecodeException) {
            null
        }
        val f = inFlight
        if (Companion.isNews(type)) {
            news(frameSeq, frame?.body)
        } else if (Companion.isAnswer(type) && f != null && frameSeq == f.seq && frame != null) {
            // An answer whose seq is not the request's is to one given up on, and is ignored.
            answer(f.pending, frame.body)
        }
    }

    /** Runs the timers: gives up on an overdue answer, or pings a node that has heard nothing for [Companion.IDLE_MS]. */
    fun tick() {
        val t = now()
        val f = inFlight
        if (f != null) {
            if (t < f.deadline) return
            // Everything is cleared before any callback runs, so one that opens again at once keeps
            // what it opens.
            phase = Phase.CLOSED
            records.abandonSync()
            val queued = queue.toList()
            inFlight = null
            queue.clear()
            f.pending.then(Outcome.NoAnswer)
            for (p in queued) p.then(Outcome.Closed)
            onEvent(ConnectionEvent.Gone)
        } else if (phase == Phase.OPEN && t >= lastAnswer + Companion.IDLE_MS) {
            transmit(Pending(Kind.Ping))
        }
    }

    private fun hello() {
        records.abandonSync()
        queue.removeAll { it.kind !is Kind.User }
        transmit(Pending(Kind.Hello))
    }

    private fun news(newsSeq: Int, body: Body?) {
        if (phase != Phase.OPEN) return
        // News of a type this client does not know is ignored, but the node counted it.
        if (newsSeq != expectedNews) {
            // What was lost may be a record this sync would have sent: it no longer proves what is
            // gone, and the one after it will.
            if (missedSince == null) missedSince = records.greatest
            records.abandonSync()
            syncWanted = true
        }
        expectedNews = (newsSeq + 1) and 0xFF
        inFlight?.takeIf { it.pending.kind == Kind.Sync }?.let { it.deadline = now() + Companion.ANSWER_WAIT_MS }
        if (body != null && Companion.isNews(body.type)) {
            records.apply(body)
            onEvent(ConnectionEvent.News(body))
        }
        pump()
    }

    private fun answer(p: Pending, body: Body) {
        inFlight = null
        lastAnswer = now()
        val kind = p.kind
        when {
            kind == Kind.Hello && body is Body.Info -> {
                nodeVersion = body.version
                firmware = body.firmware
                phase = Phase.OPEN
                expectedNews = 0
                missedSince = null
                syncWanted = false
                queue.addFirst(Pending(Kind.Sync))
                if (wallTime != null) queue.addFirst(Pending(Kind.SetTime))
                onEvent(ConnectionEvent.Ready(body.version, body.firmware))
            }
            kind == Kind.Hello -> {
                close()
                onEvent(if (body is Body.Error) ConnectionEvent.Refused(body.code) else ConnectionEvent.Gone)
                return
            }
            body is Body.Error && body.code == ErrorCode.HELLO_FIRST -> {
                // The node took this client for gone, and acted on nothing: start again, and ask
                // once more for what it refused.
                if (kind is Kind.User) queue.addFirst(p)
                phase = Phase.GREETING
                hello()
                return
            }
            body is Body.Error -> {
                if (kind == Kind.Sync) records.abandonSync()
                p.then(Outcome.Refused(body.code))
            }
            kind == Kind.Sync && body == Body.Synced -> {
                if (records.finishSync(agreed ?: version)) onEvent(ConnectionEvent.Synced)
                p.then(Outcome.Answered(body))
            }
            else -> p.then(Outcome.Answered(body))
        }
        pump()
    }

    /** Sends the next request, if none is unanswered. */
    private fun pump() {
        if (phase != Phase.OPEN || inFlight != null) return
        if (syncWanted && queue.none { it.kind == Kind.Sync }) queue.addFirst(Pending(Kind.Sync))
        syncWanted = false
        while (queue.isNotEmpty() && inFlight == null) {
            val p = queue.removeFirst()
            val kind = p.kind
            if (kind is Kind.User && kind.body.since > (agreed ?: version)) {
                p.then(Outcome.Unsupported)
                continue
            }
            transmit(p)
        }
    }

    private fun transmit(p: Pending) {
        val body = when (val kind = p.kind) {
            is Kind.User -> kind.body
            Kind.Hello -> Body.Hello(version)
            Kind.SetTime -> Body.SetTime(wallTime?.invoke() ?: 0)
            Kind.Ping -> Body.Ping
            Kind.Sync -> Body.Sync(records.after(agreed ?: version, missedSince)).also {
                missedSince = null
                records.beginSync()
            }
        }
        seq = (seq + 1) and 0xFF
        val bytes = try {
            Codec.encode(Frame(seq, body))
        } catch (e: IllegalArgumentException) {
            p.then(Outcome.Invalid(e.message ?: body.typeName))
            return
        }
        inFlight = InFlight(p, seq, now() + Companion.ANSWER_WAIT_MS)
        send(bytes)
    }

    private fun failAll(outcome: Outcome) {
        val pending = listOfNotNull(inFlight?.pending) + queue
        inFlight = null
        queue.clear()
        for (p in pending) p.then(outcome)
    }
}

/** A `ref` for a new message: random, so that another client, or this one restarted, does not repeat it. */
private fun newRef() = Random.nextLong(1, 0x1_0000_0000L)
