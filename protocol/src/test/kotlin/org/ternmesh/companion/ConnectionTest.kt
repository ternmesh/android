// The connection, as the client in the specification's exchanges, and its timers and recovery
// against a node played by hand.
package org.ternmesh.companion

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionTest {
    // The specification's connections, from the client's side

    /**
     * The client sends the exchange's frames, byte for byte, each only once the one before it is
     * answered, and ends holding what the node's news said.
     */
    @Test
    fun exchange() {
        val frames = vectors.list("exchange")
        val setTime = Codec.decode(frames.first { it.str("type") == "SET_TIME" }.bytes("frame")).body as Body.SetTime
        val link = Link(Connection(now = { 0 }, wallTime = { setTime.time }))
        val answers = link.replay(frames)

        assertEquals(11, answers.size)
        assertTrue(answers.all { it is Outcome.Answered }, "every request answered: $answers")
        val r = link.connection.records
        assertEquals("EU868", r.self?.region)
        assertEquals(2, r.syncedVersion)
        assertEquals(listOf("Bob", "Carol"), r.contacts.values.map { it.name }.sorted())
        assertEquals(listOf(0, 0), r.contacts.values.map { it.session }, "Bob's session ended; Carol never had one")
        assertEquals(listOf("Ridge walkers"), r.groups.values.map { it.name }, "the group made was left, the one joined renamed")
        assertEquals((17L..22L).toList(), r.items.keys.sorted())
        assertEquals(emptyList(), r.ordered.filter { it.isUnread }, "READ marked the message, group message and invite read")
        assertEquals(MessageState.DELIVERED, r.items[18L]?.state)
        assertEquals(MessageState.SENT, r.items[20L]?.state)
        assertEquals(1, r.neighbours.size)
        assertEquals(1, link.events.count { it is ConnectionEvent.News && it.body is Body.Asked })
    }

    /** A client of version 0 that holds messages through 17 asks only for those after them, and sets no clock when it has none to give. */
    @Test
    fun olderVersion0() {
        val older = vectors.list("older").first { it.int("version") == 0 }
        val held = Records()
        held.items[17L] = Body.Message(17, Address(ByteArray(32) { 1 }), 0, 1, MessageState.RECEIVED, 0, 0, "x")
        held.syncedVersion = 0
        val link = Link(Connection(version = 0, records = held, now = { 0 }, wallTime = null))
        val answers = link.replay(older.list("frames"))
        assertEquals(1, answers.size)
        assertEquals(0, link.connection.agreed)
        assertEquals(2, link.connection.records.contacts.size)
    }

    /**
     * A client of version 1 speaks version 1 to a node of version 2, and does not send a request
     * version 1 does not define: it fails here, with nothing on the link.
     */
    @Test
    fun olderVersion1AndTheRequestItMustNotSend() {
        val frames = vectors.list("older").first { it.int("version") == 1 }.list("frames")
        val link = Link(Connection(version = 1, now = { 0 }, wallTime = null))
        link.replay(frames.dropLast(2))
        assertEquals("MAKE_GROUP", frames[frames.size - 2].str("type"))

        var result: Outcome? = null
        link.connection.submit(Body.MakeGroup("Hut")) { result = it }
        assertEquals(Outcome.Unsupported, result)
        assertEquals(0, link.out.size)
        assertEquals(3, link.connection.records.items.size)
    }

    /** A client of version 2 talking to a node of version 1 does the same. */
    @Test
    fun aNodeOfAnEarlierVersionIsNotAskedWhatItCannotDo() {
        val node = Node(version = 1)
        node.connection.open()
        node.answerAll()
        assertEquals(1, node.connection.agreed)
        var result: Outcome? = null
        node.connection.submit(Body.Join(3)) { result = it }
        assertEquals(Outcome.Unsupported, result)
        node.connection.submit(Body.EndSession(Node.BOB)) { result = it }
        node.answerAll()
        assertEquals(Outcome.Answered(Body.Ok), result)
    }

    // One request at a time

    @Test
    fun oneRequestAtATime() {
        val node = Node()
        node.connection.open()
        node.answerAll()
        var answered = 0
        repeat(3) { node.connection.submit(Body.Ping) { answered++ } }
        assertEquals(1, node.sent.size)
        node.answerOne()
        assertEquals(1, node.sent.size)
        node.answerAll()
        assertEquals(3, answered)
    }

    @Test
    fun anAnswerWithAnotherSeqIsIgnored() {
        val node = Node()
        node.connection.open()
        node.answerAll()
        var result: Outcome? = null
        node.connection.submit(Body.Read(4)) { result = it }
        val request = Codec.decode(node.sent.removeFirst())
        node.connection.receive(Codec.encode(Frame((request.seq - 1) and 0xFF, Body.Ok)))
        assertNull(result)
        node.connection.receive(Codec.encode(Frame(request.seq, Body.Ok)))
        assertEquals(Outcome.Answered(Body.Ok), result)
    }

    // Timers

    @Test
    fun noAnswerWithinTheWaitAndTheNodeIsGone() {
        val node = Node()
        node.connection.open()
        node.answerAll()
        var first: Outcome? = null
        var second: Outcome? = null
        node.connection.submit(Body.Ping) { first = it }
        node.connection.submit(Body.Ping) { second = it }
        node.time += Companion.ANSWER_WAIT_MS - 1
        node.connection.tick()
        assertNull(first)
        node.time += 1
        node.connection.tick()
        assertEquals(Outcome.NoAnswer, first)
        assertEquals(Outcome.Closed, second)
        assertEquals(ConnectionEvent.Gone, node.events.last())
        assertNull(node.connection.nextDeadline)
    }

    @Test
    fun eachNewsFrameOfASyncStartsTheWaitAgain() {
        val node = Node()
        node.connection.open()
        node.answerOne() // INFO
        node.answerOne() // OK to SET_TIME
        node.sent.removeFirst() // SYNC, left unanswered
        repeat(3) {
            node.time += Companion.ANSWER_WAIT_MS - 1_000
            node.news(Body.Power(3900, 80, 0))
            node.connection.tick()
        }
        assertFalse(ConnectionEvent.Gone in node.events)
        node.time += Companion.ANSWER_WAIT_MS
        node.connection.tick()
        assertEquals(ConnectionEvent.Gone, node.events.last())
    }

    @Test
    fun aPingWhenNothingWasAskedForIdleSeconds() {
        val node = Node()
        node.connection.open()
        node.answerAll()
        assertEquals(Companion.IDLE_MS, node.connection.nextDeadline)
        node.time = Companion.IDLE_MS - 1_000
        node.news(Body.Power(3900, 80, 0)) // news is not an answer
        node.connection.tick()
        assertEquals(0, node.sent.size)
        node.time = Companion.IDLE_MS
        node.connection.tick()
        assertEquals(listOf<Body>(Body.Ping), node.sent.map { Codec.decode(it).body })
    }

    // Counted news, and syncing again

    @Test
    fun missedNewsSyncsFromTheLeastMessageWhoseStateMayHaveChanged() {
        val node = Node()
        node.connection.open()
        node.answerOne()
        node.answerOne()
        node.sent.removeFirst()
        node.news(Node.message(5, MessageState.DELIVERED))
        node.news(Node.message(7, MessageState.SENT))
        node.news(Node.groupMessage(6, MessageState.SENT))
        node.news(Node.message(9, MessageState.RECEIVED))
        node.connection.receive(Codec.encode(Frame(node.seq, Body.Synced)))
        assertEquals(2, node.connection.records.syncedVersion)

        node.newsCount++ // one lost
        node.news(Body.State(9, MessageState.RECEIVED, 0, 0))
        // A sent message may have been delivered since; a sent group message stays sent.
        assertEquals(listOf<Body>(Body.Sync(6)), node.sent.map { Codec.decode(it).body })
    }

    @Test
    fun withNothingThatMayChangeItSyncsFromTheGreatest() {
        val r = Records()
        r.syncedVersion = 2
        r.items[4L] = Node.message(4, MessageState.DELIVERED)
        r.items[8L] = Node.groupMessage(8, MessageState.SENT)
        assertEquals(8, r.after(2, missedSince = 8))
        assertEquals(8, r.after(2))
        // Speaking a later version than at the last sync: everything, once.
        r.syncedVersion = 1
        assertEquals(0, r.after(2))
    }

    /** What was lost may be older than what came after it: losing 11 and then hearing of 12 asks again from 10, not 12. */
    @Test
    fun missedNewsSyncsFromNoLaterThanWhatWasHeldBeforeTheGap() {
        val node = synced(Node.message(10, MessageState.DELIVERED))
        node.newsCount++ // MESSAGE 11, lost
        node.news(Node.message(12, MessageState.RECEIVED))
        assertEquals(listOf<Body>(Body.Sync(10)), node.sent.map { Codec.decode(it).body })
    }

    /** Another client's READ may have been the news lost: a received message still unread is asked for again. */
    @Test
    fun missedNewsSyncsFromAMessageStillUnread() {
        val node = synced(Node.message(4, MessageState.RECEIVED), Node.message(5, MessageState.DELIVERED))
        node.newsCount++ // MESSAGE 4, read, lost
        node.news(Body.Power(3900, 80, 0))
        assertEquals(listOf<Body>(Body.Sync(3)), node.sent.map { Codec.decode(it).body })
    }

    /** A sync that missed some of its news proves nothing about what is gone: the next one does. */
    @Test
    fun aSyncThatMissedNewsForgetsNothing() {
        val node = Node()
        node.connection.open()
        node.answerOne()
        node.answerOne()
        node.sent.removeFirst() // SYNC
        node.news(Body.Contact(Node.BOB, 1, "Bob"))
        node.connection.receive(Codec.encode(Frame(node.seq, Body.Synced)))
        assertEquals(1, node.connection.records.contacts.size)

        node.connection.resync()
        node.sent.removeFirst()
        node.newsCount++ // CONTACT Bob, lost
        node.news(Body.Power(3900, 80, 0))
        node.connection.receive(Codec.encode(Frame(node.seq, Body.Synced)))
        assertEquals(1, node.connection.records.contacts.size, "Bob is not taken for gone")
        assertEquals(1, node.events.count { it == ConnectionEvent.Synced })
        assertEquals(listOf("SYNC"), node.sent.map { Codec.decode(it).body.typeName }, "and it syncs again")
    }

    @Test
    fun newsOfATypeThisClientDoesNotKnowIsCountedAndIgnored() {
        val node = Node()
        node.connection.open()
        node.answerAll()
        val before = node.connection.records.copy()
        node.connection.receive(byteArrayOf(0xBF.toByte(), node.newsCount.toByte(), 1, 2, 3))
        node.newsCount++
        node.news(Body.Power(3900, 80, 0))
        assertEquals(0, node.sent.size, "no sync: nothing was missed")
        assertNotEquals(before, node.connection.records)
    }

    @Test
    fun aSyncIsTheWholeListOfContactsGroupsAndNeighboursButNotOfMessages() {
        val node = Node()
        node.connection.open()
        node.answerOne()
        node.answerOne()
        node.sent.removeFirst()
        node.news(Body.Contact(Node.BOB, 1, "Bob"))
        node.news(Body.Contact(Node.CAROL, 0, "Carol"))
        node.news(Body.Group(Node.HUT, "Hut"))
        node.news(Body.Neighbour(7, 1, 20, 3))
        node.news(Node.message(3, MessageState.RECEIVED))
        node.connection.receive(Codec.encode(Frame(node.seq, Body.Synced)))

        node.connection.resync()
        node.sent.removeFirst()
        node.news(Body.Contact(Node.BOB, 1, "Bob"))
        node.connection.receive(Codec.encode(Frame(node.seq, Body.Synced)))
        val r = node.connection.records
        assertEquals(setOf(Node.BOB), r.contacts.keys)
        assertEquals(emptyMap(), r.groups)
        assertEquals(emptyMap(), r.neighbours)
        assertEquals(setOf(3L), r.items.keys)
    }

    // Taken for gone

    @Test
    fun error6StartsAgainAndAsksOnceMore() {
        val node = Node()
        node.connection.open()
        node.answerAll()
        var result: Outcome? = null
        node.connection.submit(Body.SaveContact(Node.CAROL, "Carol")) { result = it }
        val refused = Codec.decode(node.sent.removeFirst())
        node.connection.receive(Codec.encode(Frame(refused.seq, Body.Error(ErrorCode.HELLO_FIRST))))
        assertNull(result)
        assertEquals(listOf("HELLO", "SET_TIME", "SYNC", "SAVE_CONTACT"), node.answerAll().map { it.typeName })
        assertEquals(Outcome.Answered(Body.Ok), result)
    }

    @Test
    fun aHelloRefusedForTheMtu() {
        val node = Node()
        node.connection.open()
        val hello = Codec.decode(node.sent.removeFirst())
        node.connection.receive(Codec.encode(Frame(hello.seq, Body.Error(ErrorCode.MTU))))
        assertEquals(listOf<ConnectionEvent>(ConnectionEvent.Refused(ErrorCode.MTU)), node.events)
        var result: Outcome? = null
        node.connection.submit(Body.Ping) { result = it }
        assertEquals(Outcome.Closed, result)
    }

    @Test
    fun sendingAgainWithTheSameRef() {
        val node = Node()
        node.connection.open()
        node.answerAll()
        val ref = node.connection.sendMessage("On my way", Node.BOB)
        node.connection.sendMessage("On my way", Node.BOB, ref)
        val sent = node.answerAll().map { it as Body.Send }
        assertEquals(sent[0].ref, sent[1].ref)
        var result: Outcome? = null
        node.connection.sendMessage("x".repeat(129), Node.BOB) { result = it }
        assertTrue(result is Outcome.Invalid, "$result")
        assertEquals(0, node.sent.size)
    }
}

/** A node and a connection to it that has synced, the node holding [messages]. */
private fun synced(vararg messages: Body.Message): Node {
    val node = Node()
    node.connection.open()
    node.answerOne()
    node.answerOne()
    node.sent.removeFirst()
    for (m in messages) node.news(m)
    node.connection.receive(Codec.encode(Frame(node.seq, Body.Synced)))
    assertEquals(0, node.sent.size)
    return node
}

/** A connection with its frames caught, and a replay of the specification's. */
private class Link(val connection: Connection) {
    val out = ArrayDeque<ByteArray>()
    val events = mutableListOf<ConnectionEvent>()

    init {
        connection.send = { out.addLast(it) }
        connection.onEvent = { events += it }
    }

    /**
     * Plays the node's half of [frames], having asked for the client's requests up front, and checks
     * the client sends its half in order and never two requests at once. Returns each request's
     * outcome.
     */
    fun replay(frames: List<JsonObject>): List<Outcome> {
        val answers = mutableListOf<Outcome>()
        connection.open()
        for (f in frames.filter { it.str("from") == "client" }) {
            val body = Codec.decode(f.bytes("frame")).body
            if (body is Body.Hello || body is Body.Sync || body is Body.SetTime) continue
            connection.submit(body) { answers += it }
        }
        for (f in frames) {
            assertTrue(out.size <= 1, "two requests at once")
            if (f.str("from") == "client") {
                assertEquals(f.str("frame"), out.removeFirstOrNull()?.let(Hex::encode), f.str("type"))
            } else {
                connection.receive(f.bytes("frame"))
            }
        }
        assertEquals(0, out.size)
        return answers
    }
}

/** A node played by hand: it answers each request as a node with nothing to report would, and sends news when told to. */
private class Node(val version: Int = 2) {
    var time = 0L
    var newsCount = 0
    val sent = ArrayDeque<ByteArray>()
    val events = mutableListOf<ConnectionEvent>()

    /** The seq of the last request. */
    var seq = 0

    val connection = Connection(now = { time }, wallTime = { 1_790_000_000 }).also {
        it.send = { bytes ->
            sent.addLast(bytes)
            seq = bytes[1].toInt() and 0xFF
        }
        it.onEvent = { e -> events += e }
    }

    fun news(body: Body) {
        connection.receive(Codec.encode(Frame(newsCount and 0xFF, body)))
        newsCount = (newsCount + 1) and 0xFF
    }

    /** Answers the oldest request sent. */
    fun answerOne(): Body {
        val request = Codec.decode(sent.removeFirst())
        val answer = when (request.body) {
            is Body.Hello -> {
                newsCount = 0
                Body.Info(version, "test")
            }
            is Body.Sync -> Body.Synced
            is Body.Send, is Body.SendGroup, is Body.SendInvite -> Body.Queued(1)
            is Body.MakeGroup -> Body.Made(HUT)
            else -> Body.Ok
        }
        connection.receive(Codec.encode(Frame(request.seq, answer)))
        return request.body
    }

    /** Answers until nothing is waiting; returns what was asked. */
    fun answerAll(): List<Body> = buildList { while (sent.isNotEmpty()) add(answerOne()) }

    companion object {
        val BOB = Address(ByteArray(32) { 0xB0.toByte() })
        val CAROL = Address(ByteArray(32) { 0xCA.toByte() })
        val HUT = GroupId(ByteArray(8) { 0x48 })

        fun message(id: Long, state: Int) = Body.Message(id, BOB, 0, 0, state, 0, 0, "x")
        fun groupMessage(id: Long, state: Int) = Body.GroupMessage(id, HUT, 0, 0, 0, state, 0, 0, "x")
    }
}
