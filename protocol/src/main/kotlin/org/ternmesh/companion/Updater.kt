// One firmware image given to a node, as "Updating the firmware" in draft/companion.md has it:
// UPDATE_BEGIN, the image's bytes from wherever the node says to go on from, and UPDATE_END.
//
// Like the connection it drives, it does no I/O and keeps no time: it makes requests on whatever
// connection it is handed, and goes on from their outcomes. A connection given up on is closed, so
// an unanswered UPDATE_DATA is not sent again on it: the next connection's UPDATE_BEGIN says how much
// of the image the node holds, and the chunk whose answer was lost goes again from there if the node
// does not hold it. That is safe either way, since a node takes the same bytes twice without holding
// them twice. UPDATE_END is never sent again: the node may be restarting into the image.
package org.ternmesh.companion

import java.security.MessageDigest

/** Where an update is. */
sealed interface UpdateState {
    /** `UPDATE_BEGIN` sent: asking the node where to go on from. */
    data object Beginning : UpdateState

    /** Sending the image's bytes. */
    data object Sending : UpdateState

    /** The link went, or the update was not yet begun: resume() on a connection goes on. */
    data object Waiting : UpdateState

    /** `UPDATE_END` sent. */
    data object Ending : UpdateState

    /** The node took the image and is restarting into it. Its next `INFO`'s `release` says whether it runs it. */
    data object Restarting : UpdateState

    /**
     * `UPDATE_END` went unanswered. The node may be restarting into the image, or may never have
     * had the request; it is not sent again, and the node's next `INFO` says which.
     */
    data object Unknown : UpdateState

    /** The node refused it with this code: 5 if it cannot be updated this way or has no room, 11 if the image is not one it runs. */
    data class Refused(val code: Int) : UpdateState

    /** It could not be asked: the node's version, or this client's, does not define updates. */
    data class Failed(val outcome: Outcome) : UpdateState

    data object Cancelled : UpdateState
}

/**
 * Gives [image] to a node. Hand it a connection with resume(), and again after each new connection
 * opens; [onChange] says each step. Everything happens on the connection's thread.
 */
class Updater(image: ByteArray) {
    private val image = image.copyOf()

    val size: Long = image.size.toLong()

    /** The image's SHA-256, which the node checks it against. */
    val digest: ByteArray = MessageDigest.getInstance("SHA-256").digest(image)

    var state: UpdateState = UpdateState.Waiting
        private set

    /** How many bytes of the image the node holds, as far as this client knows: its progress. */
    var acknowledged = 0L
        private set

    var onChange: (Updater) -> Unit = {}

    /** Nothing more will be sent: the update ended one way or another. */
    val isFinished: Boolean
        get() = when (state) {
            UpdateState.Beginning, UpdateState.Sending, UpdateState.Waiting, UpdateState.Ending -> false
            else -> true
        }

    private var connection: Connection? = null

    /** Counts the connections handed over, and cancelling: an outcome for an earlier one is ignored. */
    private var attempt = 0

    /** `ERROR` 10s since the node last took any bytes: a node that keeps sending the client back is given up on. */
    private var sentBack = 0

    init {
        require(size in 1..0xFFFF_FFFFL) { "an image is 1 to 4294967295 bytes, not $size" }
    }

    /**
     * Begins the update on [connection], or goes on with it after the link was lost: `UPDATE_BEGIN`,
     * whose answer says how much of the image the node holds. A connection that is opening sends it
     * once the node has answered `HELLO`. Does nothing once the update has finished; an
     * `UPDATE_END` still unanswered on another connection is given up on.
     */
    fun resume(connection: Connection) {
        if (isFinished) return
        attempt++
        if (state == UpdateState.Ending) return set(UpdateState.Unknown)
        this.connection = connection
        sentBack = 0
        begin()
    }

    /** Stops sending. The node keeps what it was sent until it restarts or another update begins. An `UPDATE_END` sent cannot be taken back, so it is not cancelled. */
    fun cancel() {
        if (isFinished || state == UpdateState.Ending) return
        attempt++
        connection = null
        set(UpdateState.Cancelled)
    }

    private fun begin() {
        set(UpdateState.Beginning)
        request(Body.UpdateBegin(size, digest)) { outcome ->
            val offset = ((outcome as? Outcome.Answered)?.body as? Body.Updating)?.offset
            when {
                offset != null && offset <= size -> {
                    acknowledged = offset
                    next()
                }
                // An offset past the image's end is not one this image has: the node is not
                // updating to it, and saying so is the only refusal that fits.
                offset != null -> set(UpdateState.Refused(ErrorCode.NOT_THERE))
                else -> failed(outcome)
            }
        }
    }

    private fun next() {
        if (acknowledged >= size) return end()
        set(UpdateState.Sending)
        val from = acknowledged
        val until = minOf(size, from + Companion.UPDATE_CHUNK)
        request(Body.UpdateData(from, image.copyOfRange(from.toInt(), until.toInt()))) { outcome ->
            if (outcome is Outcome.Answered) {
                acknowledged = until
                sentBack = 0
                next()
            } else {
                failed(outcome)
            }
        }
    }

    private fun end() {
        set(UpdateState.Ending)
        request(Body.UpdateEnd) { outcome ->
            when (outcome) {
                is Outcome.Answered -> set(UpdateState.Restarting)
                Outcome.NoAnswer, Outcome.Closed -> set(UpdateState.Unknown)
                else -> failed(outcome)
            }
        }
    }

    /** What a request's failure means while sending: begin again where the node says, wait for a link, or stop. */
    private fun failed(outcome: Outcome) {
        when (outcome) {
            is Outcome.Refused -> if (outcome.code == ErrorCode.NOT_THERE && ++sentBack <= SENT_BACK) {
                begin()
            } else {
                set(UpdateState.Refused(outcome.code))
            }
            Outcome.NoAnswer, Outcome.Closed -> set(UpdateState.Waiting)
            else -> set(UpdateState.Failed(outcome))
        }
    }

    private fun request(body: Body, then: (Outcome) -> Unit) {
        val c = connection ?: return
        val mine = attempt
        c.submit(body) { if (mine == attempt) then(it) }
    }

    private fun set(s: UpdateState) {
        state = s
        onChange(this)
    }
}

/** How many `ERROR` 10s in a row, with no bytes taken between them, before an update is given up on. */
private const val SENT_BACK = 3
