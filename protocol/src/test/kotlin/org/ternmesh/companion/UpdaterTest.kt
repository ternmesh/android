// The updater, as the client in the specification's update, and its recovery against a node played
// by hand.
package org.ternmesh.companion

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdaterTest {
    private val image = vectors.bytes("image")

    /**
     * The client sends both connections' frames byte for byte: the first until the link is lost, the
     * second going on from the offset the node gives, through `UPDATE_END`. Each connection is a new
     * one over the same records, as after a link that dropped.
     */
    @Test
    fun update() {
        val connections = vectors.getValue("update").jsonArray.map { c -> c.jsonArray.map { it.jsonObject } }
        val setTime = Codec.decode(connections[0].first { it.str("type") == "SET_TIME" }.bytes("frame")).body as Body.SetTime
        val updater = Updater(image)
        assertEquals(vectors.str("image_digest"), Hex.encode(updater.digest))

        val first = Played(Connection(now = { 0 }, wallTime = { setTime.time }), updater)
        first.play(connections[0])
        assertEquals(344, updater.acknowledged)
        assertEquals(UpdateState.Sending, updater.state)
        // The next chunk went out as the link was lost.
        assertEquals(1, first.out.size)
        first.connection.close()
        assertEquals(UpdateState.Waiting, updater.state)

        val second = Played(Connection(records = first.connection.records, now = { 0 }, wallTime = { setTime.time }), updater)
        second.play(connections[1])
        assertEquals(0, second.out.size)
        assertEquals(400, updater.acknowledged)
        assertEquals(UpdateState.Restarting, updater.state)
    }

    @Test
    fun aWholeUpdateInChunksOf172() {
        val node = HandNode()
        val updater = node.start(image)
        val asked = node.serveAll()
        assertEquals(
            listOf("UPDATE_BEGIN", "UPDATE_DATA", "UPDATE_DATA", "UPDATE_DATA", "UPDATE_END"),
            asked.map { it.typeName },
        )
        assertEquals(listOf(172, 172, 56), asked.filterIsInstance<Body.UpdateData>().map { it.data.size })
        assertContentEquals(image, node.held.toByteArray())
        assertEquals(UpdateState.Restarting, updater.state)
        assertTrue(updater.isFinished)
    }

    /** `ERROR` 10: the node is not where the client is. `UPDATE_BEGIN` again says where it is, and the client goes on from there. */
    @Test
    fun error10BeginsAgainAndGoesOnFromWhereTheNodeSays() {
        val node = HandNode()
        val updater = node.start(image)
        node.serve() // UPDATE_BEGIN
        node.serve() // UPDATE_DATA 0
        // The node restarted, and holds no update: it sends the client back.
        node.held.reset()
        node.size = 0
        assertEquals(172, (node.peek() as Body.UpdateData).offset)
        node.serve()
        assertEquals("UPDATE_BEGIN", node.serve().typeName)
        assertEquals(0, (node.peek() as Body.UpdateData).offset)
        node.serveAll()
        assertContentEquals(image, node.held.toByteArray())
        assertEquals(UpdateState.Restarting, updater.state)
    }

    /** `ERROR` 10 to `UPDATE_END`: the node holds less than the image. The client begins again rather than ending. */
    @Test
    fun error10ToEndBeginsAgain() {
        val node = HandNode()
        val updater = node.start(image)
        repeat(4) { node.serve() }
        assertEquals("UPDATE_END", node.peek().typeName)
        node.reply(Body.Error(ErrorCode.NOT_THERE))
        assertEquals(listOf("UPDATE_BEGIN", "UPDATE_END"), node.serveAll().map { it.typeName })
        assertEquals(UpdateState.Restarting, updater.state)
    }

    /** A node that sends the client back each time it is told where to go is given up on, not asked for ever. */
    @Test
    fun aNodeThatKeepsSendingTheClientBackIsGivenUpOn() {
        val node = HandNode()
        val updater = node.start(image)
        node.serve() // UPDATE_BEGIN
        repeat(4) {
            node.reply(Body.Error(ErrorCode.NOT_THERE)) // UPDATE_DATA
            if (!updater.isFinished) node.serve() // UPDATE_BEGIN, answered 0 again
        }
        assertEquals(UpdateState.Refused(ErrorCode.NOT_THERE), updater.state)
        assertEquals(0, node.sent.size)
    }

    /** The answer to `UPDATE_DATA` lost: the connection is given up on, and the next one asks where to go on from, sending again the chunk the node does not hold, and not one it does. */
    @Test
    fun anUnansweredChunkGoesAgainFromWhereTheNextConnectionSays() {
        val node = HandNode()
        val updater = node.start(image)
        node.serve() // UPDATE_BEGIN
        node.serve() // UPDATE_DATA 0
        val lost = node.take() as Body.UpdateData // 172, never reaches the node
        node.time += Companion.ANSWER_WAIT_MS
        node.connection.tick()
        assertEquals(UpdateState.Waiting, updater.state)
        assertEquals(172, updater.acknowledged)

        node.reconnect(updater)
        assertEquals(listOf(Body.UpdateBegin(400, updater.digest)), listOf(node.serve()))
        assertEquals(lost, node.peek(), "the same chunk again")

        // And the next chunk, taken by the node this time with its answer lost: not sent twice.
        node.serve()
        node.held.write(image, 344, 56).also { node.take() }
        node.time += Companion.ANSWER_WAIT_MS
        node.connection.tick()
        node.reconnect(updater)
        node.serve() // UPDATE_BEGIN, answered 400
        assertEquals(listOf("UPDATE_END"), node.serveAll().map { it.typeName })
        assertEquals(UpdateState.Restarting, updater.state)
    }

    /** `UPDATE_END` unanswered is not sent again, on that connection or the next: the node may be restarting into the image. */
    @Test
    fun anUnansweredEndIsNotSentAgain() {
        val node = HandNode()
        val updater = node.start(image)
        repeat(4) { node.serve() }
        assertEquals(Body.UpdateEnd, node.take())
        node.time += Companion.ANSWER_WAIT_MS
        node.connection.tick()
        assertEquals(UpdateState.Unknown, updater.state)
        assertTrue(updater.isFinished)
        node.reconnect(updater)
        assertEquals(0, node.sent.size)
        updater.resume(node.connection)
        assertEquals(0, node.sent.size)
        assertEquals(UpdateState.Unknown, updater.state)
    }

    /** The link dropped with `UPDATE_END` out: unknown too, and a connection handed over before the old one said so changes nothing. */
    @Test
    fun anEndOutWhenTheLinkDropsIsUnknown() {
        val node = HandNode()
        val updater = node.start(image)
        repeat(4) { node.serve() }
        node.take()
        updater.resume(Connection(now = { 0 }, wallTime = null))
        assertEquals(UpdateState.Unknown, updater.state)
        node.connection.close()
        assertEquals(UpdateState.Unknown, updater.state)
    }

    @Test
    fun error11IsTheEndOfIt() {
        val node = HandNode()
        val updater = node.start(image)
        repeat(4) { node.serve() }
        node.reply(Body.Error(ErrorCode.NOT_AN_IMAGE))
        assertEquals(UpdateState.Refused(ErrorCode.NOT_AN_IMAGE), updater.state)
        assertEquals(0, node.sent.size)
    }

    /** A node whose board is empty cannot be updated this way, and says so with `ERROR` 5. */
    @Test
    fun aNodeThatCannotBeUpdatedRefusesTheBegin() {
        val node = HandNode()
        val updater = node.start(image)
        node.reply(Body.Error(ErrorCode.NO_ROOM))
        assertEquals(UpdateState.Refused(ErrorCode.NO_ROOM), updater.state)
        assertEquals(0, node.sent.size)
    }

    @Test
    fun aNodeOfVersion3IsNotAsked() {
        val node = HandNode(version = 3)
        val updater = node.start(image)
        assertEquals(UpdateState.Failed(Outcome.Unsupported), updater.state)
        assertEquals(0, node.sent.size)
    }

    /** Cancelled, it sends nothing more, and the answer to what it had out changes nothing. */
    @Test
    fun cancelled() {
        val node = HandNode()
        val updater = node.start(image)
        node.serve()
        updater.cancel()
        node.serve()
        assertEquals(UpdateState.Cancelled, updater.state)
        assertEquals(0, node.sent.size)
        assertEquals(0, updater.acknowledged)
    }
}

/** A connection playing the client's half of the specification's frames, with [updater] handed it once the node answers `HELLO`. */
private class Played(val connection: Connection, updater: Updater) {
    val out = ArrayDeque<ByteArray>()

    init {
        connection.send = { out.addLast(it) }
        connection.onEvent = { if (it is ConnectionEvent.Ready) updater.resume(connection) }
        connection.open()
    }

    fun play(frames: List<JsonObject>) {
        for (f in frames) {
            assertTrue(out.size <= 1, "two requests at once")
            if (f.str("from") == "client") {
                assertEquals(f.str("frame"), out.removeFirstOrNull()?.let(Hex::encode), f.str("type"))
            } else {
                connection.receive(f.bytes("frame"))
            }
        }
    }
}

/** A node played by hand that holds an update as the specification says, and answers the rest with nothing to report. */
private class HandNode(val version: Int = Companion.VERSION) {
    var time = 0L
    val sent = ArrayDeque<ByteArray>()
    var connection = connect()

    var size = 0L
    var digest = ByteArray(0)
    val held = java.io.ByteArrayOutputStream()

    private var lastSeq = 0

    private fun connect() = Connection(now = { time }, wallTime = null).also { it.send = { b -> sent.addLast(b) } }

    /** Opens, greets, and hands [image]'s updater the connection once it is ready. */
    fun start(image: ByteArray): Updater {
        val updater = Updater(image)
        connection.open()
        serve() // HELLO
        serve() // SYNC
        updater.resume(connection)
        return updater
    }

    /** A new connection, after the last was given up on: the node keeps its update. */
    fun reconnect(updater: Updater) {
        sent.clear()
        connection = connect()
        connection.open()
        serve()
        serve()
        updater.resume(connection)
    }

    fun peek(): Body = Codec.decode(sent.first()).body

    /** The oldest request, taken off the link unanswered. */
    fun take(): Body = Codec.decode(sent.removeFirst()).also { lastSeq = it.seq }.body

    /** Answers the oldest request with [answer], whatever it was; returns the request that follows, if any. */
    fun reply(answer: Body): Body? {
        take()
        connection.receive(Codec.encode(Frame(lastSeq, answer)))
        return sent.firstOrNull()?.let { Codec.decode(it).body }
    }

    /** Answers the oldest request as the node would; returns it. */
    fun serve(): Body {
        val request = take()
        val answer = when (request) {
            is Body.Hello -> if (minOf(version, request.version) >= 4) Body.Info(version, "test", "test-board", "0.1.0") else Body.Info(version, "test")
            is Body.Sync -> Body.Synced(if (minOf(version, connection.version) >= 3) 0 else null)
            is Body.UpdateBegin -> {
                if (request.size != size || !request.digest.contentEquals(digest)) {
                    size = request.size
                    digest = request.digest
                    held.reset()
                }
                Body.Updating(held.size().toLong())
            }
            is Body.UpdateData -> {
                val end = request.offset + request.data.size
                when {
                    size == 0L -> Body.Error(ErrorCode.NOT_THERE)
                    request.offset == held.size().toLong() -> Body.Ok.also { held.write(request.data) }
                    end == held.size().toLong() -> Body.Ok
                    else -> Body.Error(ErrorCode.NOT_THERE)
                }
            }
            Body.UpdateEnd -> when {
                held.size() < size -> Body.Error(ErrorCode.NOT_THERE)
                !MessageDigest.getInstance("SHA-256").digest(held.toByteArray()).contentEquals(digest) -> Body.Error(ErrorCode.NOT_AN_IMAGE)
                else -> Body.Ok
            }
            else -> Body.Ok
        }
        connection.receive(Codec.encode(Frame(lastSeq, answer)))
        return request
    }

    /** Serves until nothing is waiting; returns what was asked. */
    fun serveAll(): List<Body> = buildList { while (sent.isNotEmpty()) add(serve()) }
}
