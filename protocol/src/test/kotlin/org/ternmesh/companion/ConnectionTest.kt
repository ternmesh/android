// The connection, as the client in the specification's exchanges, and its timers and recovery
// against a node played by hand.
package org.ternmesh.companion

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

        assertEquals(34, answers.size)
        // SHARE_GROUP asks for a precision past 24, SET 5 and SET 7 for a value neither 0 nor 1, the
        // last JOIN_LINK hands the node a join code whose check fails, and SET 8 and 9 ask for a wake
        // and a listen below the least.
        assertEquals(List(6) { Outcome.Refused(ErrorCode.REFUSED) }, answers.filter { it !is Outcome.Answered })
        assertEquals(Outcome.Answered(Body.Ok), answers.last(), "OFF is answered before the node turns off")
        val shown = answers.firstNotNullOf { (it as? Outcome.Answered)?.body as? Body.Link }
        assertEquals(JoinCode(GroupId.fromHex("c8eafadc0857a696")!!, "Ridge walkers"), JoinCode.read(shown.link))
        val r = link.connection.records
        assertEquals("EU868", r.self?.region)
        assertEquals(Pairing.SET, r.self?.pairing, "the node paired with the default passkey until the user set their own")
        assertEquals(Companion.VERSION, r.syncedVersion)
        assertEquals(listOf("Bob", "Carol", "Dave (trail crew)"), r.contacts.values.map { it.name }.sorted())
        assertEquals(listOf(0, 0, 0), r.contacts.values.map { it.session }, "Bob's session ended; Carol and Dave never had one")
        assertEquals(
            listOf("Hut", "Ridge walkers"),
            r.groups.values.map { it.name }.sorted(),
            "the group made was left and joined again from its code, the one joined from an invite renamed",
        )
        assertEquals((17L..22L).toList(), r.items.keys.sorted())
        assertEquals(emptyList(), r.ordered.filter { it.isUnread }, "READ marked the message, group message and invite read")
        assertEquals(MessageState.DELIVERED, r.items[18L]?.state)
        assertEquals(MessageState.SENT, r.items[20L]?.state)
        assertEquals(1, r.neighbours.size)
        assertEquals(Body.Sleep(600, 4, Sleeping.ASLEEP, r.neighbours.keys.single(), 0), r.sleep, "the node sleeps, kept by its neighbour")
        assertEquals(0, r.self?.role, "a leaf")
        assertEquals(1, link.events.count { it is ConnectionEvent.News && it.body is Body.Asked })
        val bob = Codec.decode(frames.first { it.str("type") == "POSITION" }.bytes("frame")).body as Body.Position
        assertEquals(mapOf(bob.contact to bob), r.positions, "Bob's position is held")
        assertEquals(16, bob.precision)
        assertEquals(Companion.NO_ALTITUDE, bob.altitude)
        assertEquals(emptyMap(), r.groupPositions)
        val shared = link.events.mapNotNull { (it as? ConnectionEvent.News)?.body as? Body.Sharing }
        assertEquals(listOf(20, 0), shared.map { it.precision }, "sharing with Bob on for an hour, then off")
        assertEquals(emptyMap(), r.sharing, "and off is not held")
        assertEquals(emptyMap(), r.groupSharing)
        val cards = link.events.mapNotNull { (it as? ConnectionEvent.News)?.body as? Body.Card }
        assertEquals(listOf(1260L, 0L), cards.map { it.heard }, "the card held from the start, then a newer one")
        assertEquals(listOf("Trail crew · ask me"), cards.map { it.name }.distinct())
        assertTrue(cards[0].address in r.contacts, "its sender was saved as a contact, under the user's name for him")
        assertEquals(emptyMap(), r.cards, "and the card forgotten since")
        assertEquals(0, r.self?.cards, "cards turned on, and off again")
        assertEquals("Ada · hut warden", r.self?.cardName, "the name is kept while they are off")
        val fixes = link.events.mapNotNull { (it as? ConnectionEvent.News)?.body as? Body.Fix }
        assertEquals(fixes.last(), r.fix)
        assertEquals(ReceiverState.SEARCHING, r.fix?.receiver, "the receiver turned off, then on again")
        assertEquals(FixSource.RECEIVER, r.fix?.source, "and its fix kept while it was off")
    }

    /**
     * Forgetting (version 16): the client forgets Bob's conversation while a message to him waits,
     * and the group's, and holds only what came after; a client that held every message through 21
     * is told what was forgotten, and keeps none of it.
     */
    @Test
    fun forgottenConversationsAreGoneFromEveryClient() {
        val (first, second) = vectors.getValue("forget").jsonArray.map { c -> c.jsonArray.map { it.jsonObject } }
        val link = Link(Connection(now = { 0 }, wallTime = null))
        val answers = link.replay(first)
        // FORGET of the node's own address is refused; the rest are answered.
        assertEquals(listOf(Outcome.Refused(ErrorCode.BAD_ADDRESS)), answers.filter { it !is Outcome.Answered })
        assertEquals(listOf(23L), link.connection.records.items.keys.toList())

        val held = Records()
        for (f in first.takeWhile { it.str("type") != "SYNCED" }) {
            val body = Codec.decode(f.bytes("frame")).body
            if (body is Item) held.items[body.id] = body
        }
        assertEquals((17L..21L).toList(), held.items.keys.sorted())
        held.syncedVersion = Companion.VERSION
        val away = Link(Connection(records = held, now = { 0 }, wallTime = null))
        away.replay(second)
        assertEquals(listOf(23L), away.connection.records.items.keys.toList())
        val told = away.events.mapNotNull { (it as? ConnectionEvent.News)?.body }
        assertEquals(1, told.count { it is Body.Forgotten })
        assertEquals(1, told.count { it is Body.GroupForgotten })
    }

    /**
     * Managing (version 17): the client's node refuses an owner that is not a contact and one neither
     * 0 nor 1, and takes Bob; it refuses a `MANAGE` to itself and one of nothing. Then the client
     * manages Bob's node over the mesh: `HELLO`, a sync of its own state in two answers, a power set
     * on trial and the `PING` that confirms it, sent only once the node has had time to restart to
     * apply it, a `SEND` refused as not over the mesh, and a restart.
     * Every inner request is the [Remote]'s, byte for byte, and each answer is unpacked from `MANAGED`.
     *
     * The vectors' connection asks for no sync after `HELLO`; the one this connection asks for is
     * answered by hand, as a node holding nothing answers it, and puts each request after it one
     * `seq` later.
     */
    @Test
    fun managingANodeOverTheMesh() {
        val frames = vectors.list("manage")
        val requests = frames.filter { it.str("from") == "client" }.map { Codec.decode(it.bytes("frame")).body }
        val manages = requests.filterIsInstance<Body.Manage>()
        val bob = manages[2].to
        val inner = manages.drop(2).map { Codec.decode(it.request).body }
        assertEquals(listOf("HELLO", "SYNC", "SET", "PING", "SEND", "RESTART"), inner.map { it.typeName })

        var time = 0L
        val connection = Connection(now = { time }, wallTime = null)
        val out = ArrayDeque<ByteArray>()
        val news = mutableListOf<Body>()
        var remote: Remote? = null
        connection.send = { out.addLast(it) }
        connection.onEvent = { e ->
            if (e is ConnectionEvent.News) {
                news += e.body
                remote?.receive(e.body)
            }
        }
        connection.open()
        val local = mutableListOf<Outcome>()
        for (body in requests.filterIsInstance<Body.Owner>() + manages.take(2)) connection.submit(body) { local += it }

        val refs = ArrayDeque(manages.drop(2).map { it.ref })
        val r = Remote(bob, manage = { body, then -> connection.submit(body, then) }, now = { time }, newRef = { refs.removeFirst() })
        remote = r
        val events = mutableListOf<RemoteEvent>()
        r.onEvent = { events += it }
        val outcomes = mutableListOf<Outcome>()
        r.open { outcomes += it }
        r.submit(inner[2]) { outcomes += it }
        r.submit(inner[4]) { outcomes += it }
        r.submit(inner[5]) {
            outcomes += it
            connection.submit(Body.Restart) { o -> local += o }
        }

        var shift = 0
        for (f in frames) {
            val bytes = f.bytes("frame")
            if (f.str("from") == "client") {
                if (shift == 0 && out.firstOrNull()?.get(0) == 0x02.toByte()) {
                    val sync = out.removeFirst()
                    connection.receive(Codec.encode(Frame(sync[1].toInt() and 0xFF, Body.Synced(0))))
                    shift = 1
                }
                if (out.isEmpty() && r.settleUntil != null) {
                    // The PING after the power waits for the node to restart, and nothing goes before then.
                    assertEquals(Remote.SETTLE_MS, r.nextDeadline)
                    time = Remote.SETTLE_MS - 1
                    r.tick()
                    assertEquals(0, out.size)
                    time = Remote.SETTLE_MS
                    r.tick()
                }
                val expected = bytes.copyOf().also { it[1] = ((it[1] + shift) and 0xFF).toByte() }
                assertEquals(Hex.encode(expected), out.removeFirstOrNull()?.let(Hex::encode), f.str("type"))
            } else {
                val answer = Companion.isAnswer(bytes[0].toInt() and 0xFF)
                connection.receive(if (answer) bytes.copyOf().also { it[1] = ((it[1] + shift) and 0xFF).toByte() } else bytes)
            }
        }
        assertEquals(0, out.size)
        assertFalse(r.busy)

        assertEquals(
            listOf(
                Outcome.Refused(ErrorCode.NOT_A_CONTACT), Outcome.Refused(ErrorCode.REFUSED), Outcome.Answered(Body.Ok),
                Outcome.Refused(ErrorCode.BAD_ADDRESS), Outcome.Refused(ErrorCode.REFUSED), Outcome.Answered(Body.Ok),
            ),
            local,
        )
        assertEquals(1, connection.records.contacts[bob]?.owner, "Bob is an owner of the client's node")
        assertEquals(
            listOf(Outcome.Answered(Body.Synced(9)), Outcome.Answered(Body.Ok), Outcome.Refused(ErrorCode.NOT_OVER_THE_MESH), Outcome.Answered(Body.Ok)),
            outcomes,
        )
        assertEquals(17, r.agreed)
        assertEquals(RemoteEvent.Ready(17, r.firmware!!), events.first { it is RemoteEvent.Ready })
        assertEquals(6, events.count { it == RemoteEvent.Trying(1) }, "each request delivered at its first try")
        assertEquals(listOf(MessageState.SENT, MessageState.DELIVERED), events.filterIsInstance<RemoteEvent.Carried>().map { it.state }.distinct())
        assertEquals(1, events.count { it == RemoteEvent.Synced })
        assertEquals(listOf(RemoteEvent.Trial(Companion.TRIAL_MS), RemoteEvent.Confirmed), events.filter { it is RemoteEvent.Trial || it == RemoteEvent.Confirmed })
        assertNull(r.trialUntil, "the PING confirmed the power")

        val held = r.records
        assertEquals(bob, held.self?.address, "the managed node's own state, apart from the client's node's")
        assertEquals(1, held.self?.role)
        assertEquals(0, held.self?.locked)
        val owner = held.contacts.values.single()
        assertEquals(1, owner.owner, "its one contact is its owner, the client's node")
        assertEquals(3, held.neighbours.size)
        assertTrue(held.airtime != null && held.power != null && held.fix != null && held.sleep != null)
        assertEquals(ReceiverState.NONE, held.fix?.receiver)
        assertEquals(emptyMap(), held.items)

        val changed = news.filterIsInstance<Body.OwnerChanged>().single()
        assertEquals(bob, changed.from)
        assertEquals(1, changed.owner)
        assertEquals(0, changed.by, "made one over a link, at the node")
        assertEquals(7, news.count { it is Body.Managed }, "one answer to each request but the sync, which takes two")
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

    /** A client of version 2 reads the node's `SYNCED` as version 2's, without the count. */
    @Test
    fun olderVersion2() {
        val frames = vectors.list("older").first { it.int("version") == 2 }.list("frames")
        val link = Link(Connection(version = 2, now = { 0 }, wallTime = null))
        link.replay(frames)
        assertEquals(2, link.connection.agreed)
        assertEquals(1, link.events.count { it == ConnectionEvent.Synced })
        assertEquals(2, link.connection.records.syncedVersion)
    }

    /**
     * Clients of versions 3 and 4 are sent no position and no sharing, one of version 5 no card and
     * a `SELF` without the cards' fields, one of version 6 its card, one of version 7 no fix, and one
     * of version 8 its fix, and one of version 11 no `update`; one of version 12 is refused an unsigned
     * update, one of version 15 may not forget, and one of version 16 is told nothing of owners and
     * may not make one. None sends the request, or the setting, its version does not define: it
     * fails here, with nothing on the link.
     */
    @Test
    fun olderVersions3To8And11To16AndTheRequestEachMustNotSend() {
        for (version in (3..8) + 11 + 12 + 15 + 16) {
            val frames = vectors.list("older").first { it.int("version") == version }.list("frames")
            val link = Link(Connection(version = version, now = { 0 }, wallTime = null))
            val answers = link.replay(frames.dropLast(2))
            // Version 12's UPDATE_BEGIN carries no signature, and a node of 13 refuses it.
            if (version == 12) assertEquals(Outcome.Refused(ErrorCode.NOT_AN_IMAGE), answers.last())
            assertEquals(version, link.connection.agreed)
            assertEquals(if (version >= 4) "heltec-v3" else null, link.connection.board)
            assertEquals(if (version >= 12) UpdateBy.LINK else null, link.connection.update)
            assertEquals(1, link.events.count { it == ConnectionEvent.Synced })
            assertEquals(version, link.connection.records.syncedVersion)
            assertEquals(emptyMap(), link.connection.records.positions)
            assertEquals(emptyMap(), link.connection.records.sharing)
            assertEquals(if (version >= 6) 1 else 0, link.connection.records.cards.size)
            assertEquals(version >= 6, link.connection.records.self?.cards != null)
            assertEquals(version >= 6, link.connection.records.self?.cardName != null)
            assertEquals(version >= 8, link.connection.records.fix != null)
            assertEquals(null, link.connection.records.self?.locked)
            assertTrue(link.connection.records.contacts.values.all { it.owner == null })

            val refused = Codec.decode(frames[frames.size - 2].bytes("frame"), version + 1).body
            assertEquals(version + 1, refused.since)
            var result: Outcome? = null
            link.connection.submit(refused) { result = it }
            assertEquals(Outcome.Unsupported, result)
            assertEquals(0, link.out.size)
        }
    }

    /**
     * A client of an earlier version takes a frame only a later one defines as of a type it does
     * not know: news is ignored, but counted, so nothing is taken for missed; an answer is discarded,
     * and the request it came for still waits for its own.
     */
    @Test
    fun unknownToOlderIgnoredAsNewsAndDiscardedAsAnAnswer() {
        for (c in vectors.list("unknown_to_older")) {
            val name = c.str("type")
            val version = c.int("version")
            val frame = c.bytes("frame")
            if (Companion.isRequest(frame[0].toInt() and 0xFF)) continue
            val node = Node(version = Companion.VERSION, clientVersion = version)
            node.connection.open()
            node.answerAll()
            assertEquals(version, node.connection.agreed, name)
            val before = node.connection.records.copy()
            val events = node.events.size
            if (Companion.isNews(frame[0].toInt() and 0xFF)) {
                node.connection.receive(frame.copyOf().also { it[1] = node.newsCount.toByte() })
                node.newsCount++
                assertEquals(before, node.connection.records, "$version $name")
                assertEquals(events, node.events.size, "$version $name")
                node.news(Body.Power(3900, 80, 0))
                assertEquals(0, node.sent.size, "$version $name: nothing missed")
            } else {
                var result: Outcome? = null
                node.connection.submit(Body.Ping) { result = it }
                val ping = Codec.decode(node.sent.removeFirst())
                node.connection.receive(frame.copyOf().also { it[1] = ping.seq.toByte() })
                assertNull(result, "$version $name")
                node.connection.receive(Codec.encode(Frame(ping.seq, Body.Ok)))
                assertEquals(Outcome.Answered(Body.Ok), result, "$version $name")
            }
        }
    }

    /**
     * The client of version 12 in the bootloader's first connection: told the node is updated by its
     * bootloader, it asks it to restart into it. The sync between, which the vectors leave out, is
     * answered as a node that holds only itself answers it.
     */
    @Test
    fun bootloaderTheClientOfVersion12AsksForTheBootloader() {
        val frames = vectors.getValue("bootloader").jsonArray[0].jsonArray.map { it.jsonObject }
        val node = Node(version = Companion.VERSION)
        val events = mutableListOf<ConnectionEvent>()
        node.connection.onEvent = { events += it }
        node.connection.open()
        assertEquals(frames[0].str("frame"), Hex.encode(node.sent.removeFirst()))
        node.connection.receive(frames[1].bytes("frame"))
        assertEquals(UpdateBy.BOOTLOADER, node.connection.update)
        assertEquals(ConnectionEvent.Ready(Companion.VERSION, "tern 0.2.0 heltec-t114", "heltec-t114", "0.2.0", UpdateBy.BOOTLOADER), events.first())
        node.answerAll() // SET_TIME and SYNC

        val outcomes = mutableListOf<Outcome>()
        node.connection.submit(Codec.decode(frames[2].bytes("frame")).body) { outcomes += it }
        val updater = BootloaderUpdater(DfuPackage(byteArrayOf(1), byteArrayOf(2)))
        updater.ask(node.connection)
        for (f in frames.drop(2)) {
            if (f.str("from") == "client") {
                assertEquals(f.str("frame"), Hex.encode(node.sent.removeFirst()), f.str("type"))
            } else {
                node.connection.receive(f.bytes("frame"))
            }
        }
        assertEquals(listOf<Outcome>(Outcome.Refused(ErrorCode.NO_ROOM)), outcomes, "no room for an image beside the one it runs")
        assertEquals(BootloaderState.Restarting(confirmed = true), updater.state)
    }

    /** The client of version 11 in the bootloader's second connection is told no `update`, and no board: to it, the node cannot be updated. */
    @Test
    fun bootloaderTheClientOfVersion11IsGivenNoBoard() {
        val frames = vectors.getValue("bootloader").jsonArray[1].jsonArray.map { it.jsonObject }
        val node = Node(version = Companion.VERSION, clientVersion = 11)
        node.connection.open()
        assertEquals(frames[0].str("frame"), Hex.encode(node.sent.removeFirst()))
        node.connection.receive(frames[1].bytes("frame"))
        assertEquals(11, node.connection.agreed)
        assertEquals("", node.connection.board)
        assertEquals(null, node.connection.update)
        assertEquals(UpdateBy.NONE, updateBy(node.connection.board, node.connection.update))
        node.answerAll()
        val updater = BootloaderUpdater(DfuPackage(byteArrayOf(1), byteArrayOf(2)))
        updater.ask(node.connection)
        assertEquals(BootloaderState.Failed(Outcome.Unsupported), updater.state, "version 11 does not define UPDATE_BOOTLOADER")
        assertEquals(0, node.sent.size)
    }

    /** A client of version 4 talking to a node of version 1 does the same. */
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

    /** An app that opens again from the timed-out request's callback keeps the HELLO it sent. */
    @Test
    fun openingAgainFromTheCallbackOfARequestGivenUpOn() {
        val node = Node()
        node.connection.open()
        node.answerAll()
        var gone = false
        node.connection.onEvent = { if (it == ConnectionEvent.Gone) gone = true }
        node.connection.submit(Body.Ping) {
            assertTrue(gone, "the app is told before the request's callback")
            node.connection.open()
        }
        node.connection.submit(Body.Ping)
        node.sent.removeFirst()
        node.time += Companion.ANSWER_WAIT_MS
        node.connection.tick()
        assertEquals(listOf("HELLO", "SET_TIME", "SYNC"), node.answerAll().map { it.typeName })
        assertEquals(Companion.VERSION, node.connection.agreed)
    }

    /** Opening again fails what was held only once the new HELLO is out, so a callback that opens again too sends no second one: one request at a time holds. */
    @Test
    fun openingAgainFromTheCallbackOfARequestOpeningClosed() {
        val node = Node()
        node.connection.open()
        node.answerAll()
        var result: Outcome? = null
        node.connection.submit(Body.Ping) {
            result = it
            node.connection.open()
        }
        node.sent.removeFirst()
        node.connection.open()
        assertEquals(Outcome.Closed, result)
        assertEquals(listOf("HELLO"), node.sent.map { Codec.decode(it).body.typeName })
        assertEquals(listOf("HELLO", "SET_TIME", "SYNC"), node.answerAll().map { it.typeName })
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
        node.answerSync()
        assertEquals(Companion.VERSION, node.connection.records.syncedVersion)

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
        r.missedSince = 8
        assertEquals(8, r.after(2))
        r.missedSince = null
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

    /** What was missed is kept with the records: an app that stops before the sync after a gap finishes, and starts again from what it saved, still asks again from before the gap. */
    @Test
    fun missedNewsOutlivesTheConnection() {
        val node = synced(Node.message(10, MessageState.DELIVERED))
        node.newsCount++ // MESSAGE 11, lost
        node.news(Node.message(12, MessageState.RECEIVED))
        val saved = node.connection.records.copy()
        assertEquals(10L, saved.missedSince)

        val next = Node(records = saved)
        next.connection.open()
        next.answerOne()
        next.answerOne()
        assertEquals(listOf<Body>(Body.Sync(10)), next.sent.map { Codec.decode(it).body })
        next.answerOne()
        assertNull(next.connection.records.missedSince)
    }

    /** Another client's READ may have been the news lost: a received message still unread is asked for again. */
    @Test
    fun missedNewsSyncsFromAMessageStillUnread() {
        val node = synced(Node.message(4, MessageState.RECEIVED), Node.message(5, MessageState.DELIVERED))
        node.newsCount++ // MESSAGE 4, read, lost
        node.news(Body.Power(3900, 80, 0))
        assertEquals(listOf<Body>(Body.Sync(3)), node.sent.map { Codec.decode(it).body })
    }

    /** News of a type this client knows that it cannot read is a record lost: it syncs again. */
    @Test
    fun newsItCannotReadIsNewsMissed() {
        val node = synced(Node.message(4, MessageState.DELIVERED))
        val message = Codec.encode(Frame(node.newsCount, Node.message(5, MessageState.RECEIVED)))
        node.connection.receive(message.copyOf(20))
        node.newsCount++
        assertEquals(listOf<Body>(Body.Sync(4)), node.sent.map { Codec.decode(it).body })
    }

    /** A sync that comes to nothing leaves what was missed marked: after ERROR 6 and a new HELLO, the next sync asks from 10 again, not from the 12 heard of since. */
    @Test
    fun whatWasMissedStaysMarkedUntilASyncFinishes() {
        val node = synced(Node.message(10, MessageState.DELIVERED))
        node.newsCount++ // MESSAGE 11, lost
        node.news(Node.message(12, MessageState.DELIVERED))
        val sync = Codec.decode(node.sent.removeFirst())
        assertEquals(Body.Sync(10), sync.body)
        node.connection.receive(Codec.encode(Frame(sync.seq, Body.Error(ErrorCode.HELLO_FIRST))))
        node.answerOne() // INFO
        node.answerOne() // OK to SET_TIME
        assertEquals(listOf<Body>(Body.Sync(10)), node.sent.map { Codec.decode(it).body })
    }

    /** A client away from the node missed every change made meanwhile: a connection's first sync reaches back to the oldest message whose state may have changed. */
    @Test
    fun aConnectionStartsAsIfNewsWereMissed() {
        val node = synced(Node.message(5, MessageState.SENT), Node.message(8, MessageState.DELIVERED))
        node.connection.close()
        node.connection.open()
        node.answerOne() // INFO
        node.answerOne() // OK to SET_TIME
        assertEquals(listOf<Body>(Body.Sync(4)), node.sent.map { Codec.decode(it).body })
    }

    /** A sync the node refuses is said, and asked for again at the next idle deadline in place of a PING: not at once, which a node that keeps refusing would answer for ever. */
    /** News missed while a sync is out, and then that sync refused: the sync it wanted waits for the idle deadline with the one refused, rather than going out at once. */
    @Test
    fun aSyncWantedWhileTheRefusedOneWasOutWaitsToo() {
        val node = Node()
        node.connection.open()
        node.answerOne() // INFO
        node.answerOne() // OK to SET_TIME
        val sync = Codec.decode(node.sent.removeFirst())
        node.newsCount++ // one lost
        node.news(Body.Power(3900, 80, 0))
        node.connection.receive(Codec.encode(Frame(sync.seq, Body.Error(ErrorCode.NOT_NOW))))
        assertEquals(0, node.sent.size)
        node.time = Companion.IDLE_MS
        node.connection.tick()
        assertEquals(listOf("SYNC"), node.sent.map { Codec.decode(it).body.typeName })
    }

    @Test
    fun aRefusedSyncIsAskedForAgainWhenIdle() {
        val node = Node()
        node.connection.open()
        node.answerOne() // INFO
        node.answerOne() // OK to SET_TIME
        val sync = Codec.decode(node.sent.removeFirst())
        node.connection.receive(Codec.encode(Frame(sync.seq, Body.Error(ErrorCode.NOT_NOW))))
        assertEquals(ConnectionEvent.SyncRefused(ErrorCode.NOT_NOW), node.events.last())
        assertEquals(0, node.sent.size)
        node.time = Companion.IDLE_MS
        node.connection.tick()
        assertEquals(listOf("SYNC"), node.sent.map { Codec.decode(it).body.typeName })
        node.answerAll()
        assertEquals(ConnectionEvent.Synced, node.events.last())
        node.time = 2 * Companion.IDLE_MS
        node.connection.tick()
        assertEquals(listOf("PING"), node.sent.map { Codec.decode(it).body.typeName })
    }

    /** A sync that finishes some other way pays what a refused one owed: the idle deadline pings. */
    @Test
    fun aSyncThatFinishesClearsTheOneOwed() {
        val node = Node()
        node.connection.open()
        node.answerOne() // INFO
        node.answerOne() // OK to SET_TIME
        val sync = Codec.decode(node.sent.removeFirst())
        node.connection.receive(Codec.encode(Frame(sync.seq, Body.Error(ErrorCode.NOT_NOW))))
        node.connection.resync()
        node.answerAll()
        assertEquals(ConnectionEvent.Synced, node.events.last())
        node.time = Companion.IDLE_MS
        node.connection.tick()
        assertEquals(listOf("PING"), node.sent.map { Codec.decode(it).body.typeName })
    }

    /** A sync that missed some of its news proves nothing about what is gone: the next one does. */
    @Test
    fun aSyncThatMissedNewsForgetsNothing() {
        val node = Node()
        node.connection.open()
        node.answerOne()
        node.answerOne()
        node.sent.removeFirst() // SYNC
        node.news(Body.Contact(Node.BOB, 1, "Bob", 0, 0))
        node.answerSync()
        assertEquals(1, node.connection.records.contacts.size)

        node.connection.resync()
        node.sent.removeFirst()
        node.newsCount++ // CONTACT Bob, lost
        node.news(Body.Power(3900, 80, 0))
        node.answerSync()
        assertEquals(1, node.connection.records.contacts.size, "Bob is not taken for gone")
        assertEquals(1, node.events.count { it == ConnectionEvent.Synced })
        assertEquals(listOf("SYNC"), node.sent.map { Codec.decode(it).body.typeName }, "and it syncs again")
    }

    /** The sync's last news lost, with nothing after it to show a gap: `SYNCED`'s count does, and the client forgets nothing on that sync's account and syncs again. */
    @Test
    fun aSyncWhoseLastNewsWasLostIsToldBySyncedsCount() {
        val node = synced(Node.message(10, MessageState.DELIVERED))
        node.connection.resync()
        node.sent.removeFirst() // SYNC
        node.news(Body.Contact(Node.BOB, 1, "Bob", 0, 0))
        node.answerSync()
        assertEquals(1, node.connection.records.contacts.size)

        node.connection.resync()
        node.sent.removeFirst()
        node.newsCount++ // CONTACT Bob, the sync's last news, lost
        val synceds = node.events.count { it == ConnectionEvent.Synced }
        node.answerSync()
        assertEquals(1, node.connection.records.contacts.size, "Bob is not taken for gone")
        assertEquals(synceds, node.events.count { it == ConnectionEvent.Synced })
        assertEquals(listOf<Body>(Body.Sync(10)), node.sent.map { Codec.decode(it).body }, "and it syncs again")
        node.sent.removeFirst()

        // News after it is not taken for another gap.
        node.news(Body.Power(3900, 80, 0))
        node.answerSync()
        assertEquals(0, node.sent.size)
        assertEquals(ConnectionEvent.Synced, node.events.last())
    }

    /** A client of version 4 reads a node of version 2's `SYNCED` as the two bytes it is. */
    @Test
    fun aNodeOfVersion2SyncsWithoutTheCount() {
        val node = Node(version = 2)
        node.connection.open()
        node.answerAll()
        assertEquals(2, node.connection.agreed)
        assertEquals(ConnectionEvent.Synced, node.events.last())
        assertEquals(2, node.connection.records.syncedVersion)
    }

    /** Records kept from a version 2 connection, synced with a node that speaks an earlier version, keep their groups: that sync could not have sent them. */
    @Test
    fun aSyncOfAnEarlierVersionKeepsTheGroups() {
        val r = Records()
        r.apply(Body.Group(Node.HUT, "Hut"))
        r.beginSync()
        assertTrue(r.finishSync(1))
        assertEquals(setOf(Node.HUT), r.groups.keys)
        r.beginSync()
        assertTrue(r.finishSync(2))
        assertEquals(emptyMap(), r.groups)
    }

    /** A position or sharing of precision 0 is none: it takes away the one held, and is not held. */
    @Test
    fun precision0IsNone() {
        val r = Records()
        r.apply(Body.Position(Node.BOB, 16, 603_945_922, 52_871_704, Companion.NO_ALTITUDE, 0, 40))
        r.apply(Body.GroupPosition(Node.HUT, 7, 24, 1, 2, 3, 4, 5))
        r.apply(Body.Sharing(Node.BOB, 20, 3, 900, 60))
        r.apply(Body.GroupSharing(Node.HUT, 12, 0, 300, 0))
        assertEquals(setOf(Node.BOB), r.positions.keys)
        assertEquals(setOf(Node.HUT to 7L), r.groupPositions.keys)
        assertEquals(setOf(Node.BOB), r.sharing.keys)
        assertEquals(setOf(Node.HUT), r.groupSharing.keys)
        r.apply(Body.Position(Node.BOB, 0, 0, 0, 0, 0, 0))
        r.apply(Body.GroupPosition(Node.HUT, 8, 0, 0, 0, 0, 0, 0))
        r.apply(Body.Sharing(Node.CAROL, 0, 0, 0, 0))
        r.apply(Body.GroupSharing(Node.HUT, 0, 0, 0, 0))
        assertEquals(emptyMap(), r.positions)
        assertEquals(setOf(Node.HUT to 7L), r.groupPositions.keys, "another member's position is another record")
        assertEquals(setOf(Node.BOB), r.sharing.keys)
        assertEquals(emptyMap(), r.groupSharing)
    }

    /** A sync is the whole list of positions and of sharing: what it did not send is forgotten, or off. One of version 4 sends neither, and says nothing of them. */
    @Test
    fun aSyncIsTheWholeListOfPositionsAndSharing() {
        val r = Records()
        r.apply(Body.Position(Node.BOB, 16, 1, 2, 3, 4, 5))
        r.apply(Body.Position(Node.CAROL, 16, 1, 2, 3, 4, 5))
        r.apply(Body.GroupPosition(Node.HUT, 7, 24, 1, 2, 3, 4, 5))
        r.apply(Body.Sharing(Node.BOB, 20, 3, 900, 60))
        r.apply(Body.GroupSharing(Node.HUT, 12, 0, 300, 0))
        val held = r.copy()
        r.beginSync()
        assertTrue(r.finishSync(4))
        assertEquals(held.copy().also { it.syncedVersion = 4 }, r)

        r.beginSync()
        r.apply(Body.Position(Node.CAROL, 12, 1, 2, 3, 4, 50))
        r.apply(Body.GroupSharing(Node.HUT, 12, 0, 300, 0))
        assertTrue(r.finishSync(5))
        assertEquals(setOf(Node.CAROL), r.positions.keys)
        assertEquals(emptyMap(), r.groupPositions)
        assertEquals(emptyMap(), r.sharing, "sharing the sync did not send is off")
        assertEquals(setOf(Node.HUT), r.groupSharing.keys)
    }

    /** A sync is the whole list of cards: one it did not send is forgotten. One of version 5 sends none, and says nothing of them. A card replaces the one before it from the same address, and saving or removing its sender leaves it. */
    @Test
    fun aSyncIsTheWholeListOfCards() {
        val r = Records()
        r.apply(Body.Card(Node.BOB, 60, "Bob?"))
        r.apply(Body.Card(Node.CAROL, 1260, ""))
        r.apply(Body.Card(Node.BOB, 0, "Bob!"))
        assertEquals(listOf("Bob!", ""), r.cards.values.map { it.name })
        r.apply(Body.Contact(Node.BOB, 0, "Robert"))
        r.apply(Body.ContactGone(Node.BOB))
        assertEquals(setOf(Node.BOB, Node.CAROL), r.cards.keys)

        r.beginSync()
        assertTrue(r.finishSync(5))
        assertEquals(setOf(Node.BOB, Node.CAROL), r.cards.keys)

        r.beginSync()
        r.apply(Body.Card(Node.CAROL, 1300, ""))
        assertTrue(r.finishSync(6))
        assertEquals(setOf(Node.CAROL), r.cards.keys)
        r.apply(Body.CardGone(Node.CAROL))
        assertEquals(emptyMap(), r.cards)
    }

    /** A node of version 5 is not asked to turn cards on, or to name them: it fails here, with nothing on the link. */
    @Test
    fun aNodeOfVersion5IsNotSetWhatItDoesNotDefine() {
        val node = Node(version = 5)
        node.connection.open()
        node.answerAll()
        assertEquals(5, node.connection.agreed)
        for (setting in listOf(Setting.Cards(1), Setting.CardName("Ada"))) {
            var result: Outcome? = null
            node.connection.submit(Body.Set(setting)) { result = it }
            assertEquals(Outcome.Unsupported, result)
        }
        assertEquals(0, node.sent.size)
        var result: Outcome? = null
        node.connection.submit(Body.Set(Setting.Role(1))) { result = it }
        node.answerAll()
        assertEquals(Outcome.Answered(Body.Ok), result)
    }

    /** A sync that missed some of its news forgets no position and turns no sharing off. */
    @Test
    fun aSyncThatMissedNewsKeepsPositionsAndSharing() {
        val node = Node()
        node.connection.open()
        node.answerOne()
        node.answerOne()
        node.sent.removeFirst() // SYNC
        node.news(Body.Position(Node.BOB, 16, 1, 2, 3, 4, 5))
        node.news(Body.Sharing(Node.BOB, 20, 3, 900, 60))
        node.answerSync()

        node.connection.resync()
        node.sent.removeFirst()
        node.newsCount += 2 // both, lost
        node.answerSync()
        assertEquals(setOf(Node.BOB), node.connection.records.positions.keys)
        assertEquals(setOf(Node.BOB), node.connection.records.sharing.keys)
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
        node.news(Body.Contact(Node.BOB, 1, "Bob", 0, 0))
        node.news(Body.Contact(Node.CAROL, 0, "Carol", 0, 0))
        node.news(Body.Group(Node.HUT, "Hut"))
        node.news(Body.Neighbour(7, 1, 20, 3))
        node.news(Node.message(3, MessageState.RECEIVED))
        node.answerSync()

        node.connection.resync()
        node.sent.removeFirst()
        node.news(Body.Contact(Node.BOB, 1, "Bob", 0, 0))
        node.answerSync()
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

    /** A refused HELLO is said before the requests waiting on it fail: one that opens again keeps the connection it opens. */
    @Test
    fun aRefusedHelloIsSaidBeforeTheRequestsWaitingOnIt() {
        val node = Node()
        var refused = false
        node.connection.onEvent = { if (it is ConnectionEvent.Refused) refused = true }
        node.connection.open()
        node.connection.submit(Body.Ping) {
            assertTrue(refused, "the app is told before the request's callback")
            node.connection.open()
        }
        val hello = Codec.decode(node.sent.removeFirst())
        node.connection.receive(Codec.encode(Frame(hello.seq, Body.Error(ErrorCode.MTU))))
        assertEquals(listOf("HELLO"), node.sent.map { Codec.decode(it).body.typeName })
    }

    /** A client speaks only the versions it implements: claiming a later one would let the node send what it cannot read. */
    @Test
    fun aVersionThisClientDoesNotSpeakIsRefused() {
        assertFailsWith<IllegalArgumentException> { Connection(version = Companion.VERSION + 1, now = { 0 }, wallTime = null) }
        assertFailsWith<IllegalArgumentException> { Connection(version = -1, now = { 0 }, wallTime = null) }
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
    node.answerSync()
    assertEquals(0, node.sent.size)
    return node
}

/** `INFO` as a node of [version] answers a client of [client]: board and release from version 4, and update from 12. */
internal fun info(version: Int, client: Int): Body.Info {
    val both = minOf(version, client)
    return when {
        both >= 12 -> Body.Info(version, "test", "test-board", "0.1.0", UpdateBy.LINK)
        both >= 4 -> Body.Info(version, "test", "test-board", "0.1.0")
        else -> Body.Info(version, "test")
    }
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
            val body = Codec.decode(f.bytes("frame"), connection.version).body
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
/** The version a [Node] speaks unless told otherwise: Node's own companion object hides [Companion]. */
private const val LATEST = Companion.VERSION

/** [version] is the node's; [clientVersion] the connection's. */
private class Node(val version: Int = LATEST, records: Records = Records(), clientVersion: Int = LATEST) {
    var time = 0L
    var newsCount = 0
    val sent = ArrayDeque<ByteArray>()
    val events = mutableListOf<ConnectionEvent>()

    /** The seq of the last request. */
    var seq = 0

    val connection = Connection(clientVersion, records, now = { time }, wallTime = { 1_790_000_000 }).also {
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

    /** `SYNCED` as this node answers it: with its count from version 3. */
    fun syncedAnswer() = Body.Synced(if (minOf(version, connection.version) >= 3) newsCount else null)

    /** Answers the last request, a `SYNC`, now. */
    fun answerSync() = connection.receive(Codec.encode(Frame(seq, syncedAnswer())))

    /** Answers the oldest request sent. */
    fun answerOne(): Body {
        val request = Codec.decode(sent.removeFirst())
        val answer = when (request.body) {
            is Body.Hello -> {
                newsCount = 0
                info(version, connection.version)
            }
            is Body.Sync -> syncedAnswer()
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
