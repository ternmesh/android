// The codec against the specification's vectors (src/test/resources/vectors/companion.json, a copy
// of vectors/companion.json in ternmesh/spec): its conformance section, as a client.
package org.ternmesh.companion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class CompanionVectorsTest {
    private val v = vectors

    @Test
    fun crcCheck() {
        val c = v.obj("crc_check")
        assertEquals(c.int("crc"), ByteStream.crc16(c.bytes("input")))
    }

    @Test
    fun framesBuiltReadWrappedAndFound() {
        val cases = v.list("frames")
        assertTrue(cases.size >= 64)
        for (c in cases) {
            val name = c.str("type")
            val frame = Frame(c.int("seq"), body(name, c.obj("fields")))
            assertEquals(c.str("frame"), Hex.encode(Codec.encode(frame)), name)
            val read = Codec.decode(c.bytes("frame"))
            assertEquals(frame, read, name)
            assertEquals(name, read.body.typeName)
            assertEquals(c.str("stream"), Hex.encode(ByteStream.wrap(c.bytes("frame"))), name)
            assertEquals(listOf(StreamItem.Frame(c.bytes("frame"))), StreamReader().push(c.bytes("stream")), name)
        }
    }

    @Test
    fun extendedBytesAfterTheFieldsAreIgnored() {
        for (c in v.list("extended")) {
            val name = c.str("type")
            assertEquals(Frame(c.int("seq"), body(name, c.obj("fields"))), Codec.decode(c.bytes("frame")), name)
        }
    }

    @Test
    fun rejectedAClientDiscardsEachAndANodeAnswersTheCode() {
        for (c in v.list("rejected")) {
            val why = c.str("why")
            val bytes = c.bytes("frame")
            val e = assertFailsWith<DecodeException>(why) { Codec.decode(bytes) }
            val answer = c["answer"].let { if (it == null || it is JsonNull) null else it.jsonPrimitive.int }
            assertEquals(answer, Codec.errorCode(bytes, e.reason), why)
        }
    }

    @Test
    fun streamsAllAtOnceAndAByteAtATime() {
        for (c in v.list("streams")) {
            val why = c.str("why")
            val expected = c.list("items").map {
                if ("frame" in it) StreamItem.Frame(it.bytes("frame")) else StreamItem.Text(it.bytes("text"))
            }
            val whole = StreamReader()
            assertEquals(expected, whole.push(c.bytes("stream")), why)
            assertEquals(c.str("pending"), Hex.encode(whole.pending), why)

            // A byte at a time the frames are the same and in the same order, with the text
            // between them in as many pieces as it came in.
            val slow = StreamReader()
            val items = mutableListOf<StreamItem>()
            for (b in c.bytes("stream")) {
                for (i in slow.push(byteArrayOf(b))) {
                    val last = items.lastOrNull()
                    if (i is StreamItem.Text && last is StreamItem.Text) {
                        items[items.size - 1] = StreamItem.Text(last.bytes + i.bytes)
                    } else {
                        items += i
                    }
                }
            }
            assertEquals(expected, items, why)
            assertEquals(c.str("pending"), Hex.encode(slow.pending), why)
        }
    }

    @Test
    fun exchangeEveryFrameReadsAndBuildsBackToTheSameBytes() {
        for (c in v.list("exchange")) {
            val name = c.str("type")
            val f = Codec.decode(c.bytes("frame"))
            assertEquals(name, f.body.typeName)
            assertEquals(c.int("seq"), f.seq, name)
            assertEquals(c.str("from") == "client", Companion.isRequest(f.body.type), name)
            assertEquals(c.str("frame"), Hex.encode(Codec.encode(f)), name)
        }
    }

    /** Each older connection's frames read by the version its client speaks, and build back; one that version does not define is undefined. */
    @Test
    fun olderEveryFrameReadsByItsVersion() {
        for (c in v.list("older")) {
            val version = c.int("version")
            for (f in c.list("frames")) {
                val name = f.str("type")
                // Version 1's client ends with a request its version does not define.
                val latest = runCatching { Codec.decode(f.bytes("frame")) }.getOrNull()
                if (latest != null && latest.body.since > version) {
                    assertFailsWith<DecodeException>(name) { Codec.decode(f.bytes("frame"), version) }
                    continue
                }
                val frame = Codec.decode(f.bytes("frame"), version)
                assertEquals(name, frame.body.typeName)
                assertEquals(f.str("frame"), Hex.encode(Codec.encode(frame)), "$version $name")
            }
        }
        // And by version 3 or later, version 2's SYNCED is cut short.
        assertFailsWith<DecodeException> { Codec.decode(byteArrayOf(0x43, 0x02)) }
        assertEquals(Body.Synced(null), Codec.decode(byteArrayOf(0x43, 0x02), 2).body)
    }

    /** The update's and the refusals' frames, each read and built back; the image's digest is the one given. */
    @Test
    fun updateEveryFrameReadsAndBuildsBack() {
        val image = v.bytes("image")
        assertEquals(v.str("image_digest"), Hex.encode(java.security.MessageDigest.getInstance("SHA-256").digest(image)))
        val connections = v.getValue("update").jsonArray.map { c -> c.jsonArray.map { it.jsonObject } }
        assertEquals(2, connections.size)
        for (f in connections.flatten() + v.list("refusals")) {
            val name = f.str("type")
            val frame = Codec.decode(f.bytes("frame"))
            assertEquals(name, frame.body.typeName)
            assertEquals(f.int("seq"), frame.seq, name)
            assertEquals(f.str("from") == "client", Companion.isRequest(frame.body.type), name)
            assertEquals(f.str("frame"), Hex.encode(Codec.encode(frame)), name)
        }
    }

    /** `INFO`'s board and release are version 4's: a client of 4 reads a node of 3's without them, and a client of 3 a node of 4's. */
    @Test
    fun infoHasBoardAndReleaseOnlyWhenBothSpeak4() {
        val four = Codec.encode(Frame(1, Body.Info(4, "tern", "heltec-v3", "0.2.0")))
        assertEquals(Body.Info(4, "tern", "heltec-v3", "0.2.0"), Codec.decode(four).body)
        assertEquals(Body.Info(4, "tern"), Codec.decode(four, 3).body)
        val three = Codec.encode(Frame(1, Body.Info(3, "tern")))
        assertEquals(Body.Info(3, "tern"), Codec.decode(three).body)
        assertFailsWith<IllegalArgumentException> { Codec.encode(Frame(1, Body.Info(4, "tern", "heltec-v3", null))) }
        // Version 3 does not define updates.
        val begin = Codec.encode(Frame(1, Body.UpdateBegin(1, ByteArray(32))))
        assertEquals(Unreadable.UNDEFINED, assertFailsWith<DecodeException> { Codec.decode(begin, 3) }.reason)
        assertFailsWith<IllegalArgumentException> { Codec.encode(Frame(1, Body.UpdateData(0, ByteArray(173)))) }
        assertFailsWith<IllegalArgumentException> { Body.UpdateBegin(1, ByteArray(31)) }
    }

    /** `SELF`'s cards and card name are version 6's, and so are the two settings and the two news types. */
    @Test
    fun cardsAreVersion6s() {
        val ada = Address(ByteArray(32) { 0xAD.toByte() })
        val six = Codec.encode(Frame(0, Body.Self(ada, 1, "EU868", 14, 0, 1, "Ada")))
        assertEquals(Body.Self(ada, 1, "EU868", 14, 0, 1, "Ada"), Codec.decode(six).body)
        assertEquals(Body.Self(ada, 1, "EU868", 14, 0), Codec.decode(six, 5).body)
        val five = Codec.encode(Frame(0, Body.Self(ada, 1, "EU868", 14, 0)))
        assertEquals(Body.Self(ada, 1, "EU868", 14, 0), Codec.decode(five, 5).body)
        assertEquals(Unreadable.MALFORMED, assertFailsWith<DecodeException> { Codec.decode(five) }.reason)
        assertFailsWith<IllegalArgumentException> { Codec.encode(Frame(0, Body.Self(ada, 1, "EU868", 14, 0, 1, null))) }

        for (setting in listOf(Setting.Cards(1), Setting.CardName("Ada"))) {
            val set = Codec.encode(Frame(1, Body.Set(setting)))
            assertEquals(6, Body.Set(setting).since)
            assertEquals(Body.Set(setting), Codec.decode(set).body)
            assertEquals(Unreadable.UNDEFINED, assertFailsWith<DecodeException> { Codec.decode(set, 5) }.reason)
            // Undefined before its value is read: cut short, it is still a setting the version lacks.
            assertEquals(Unreadable.UNDEFINED, assertFailsWith<DecodeException> { Codec.decode(set.copyOf(3), 5) }.reason)
            assertEquals(Unreadable.MALFORMED, assertFailsWith<DecodeException> { Codec.decode(set.copyOf(3)) }.reason)
        }
        assertEquals(0, Body.Set(Setting.Passkey(1)).since)
        assertFailsWith<IllegalArgumentException> { Codec.encode(Frame(1, Body.Set(Setting.CardName("x".repeat(32))))) }
        assertFailsWith<IllegalArgumentException> { Codec.encode(Frame(1, Body.Set(Setting.Cards(256)))) }

        // A card is held a day, which is past two bytes of seconds.
        val card = Body.Card(ada, 86_399, "")
        assertEquals(card, Codec.decode(Codec.encode(Frame(0, card))).body)
        assertEquals(Unreadable.UNDEFINED, assertFailsWith<DecodeException> { Codec.decode(Codec.encode(Frame(0, card)), 5) }.reason)
    }

    /** Join codes are version 7's, a link is at most 102 bytes, and a frame's text leaves the secret out. */
    @Test
    fun joinCodesAreVersion7s() {
        val link = "HTTPS://TERNMESH.ORG/G#" + "A".repeat(Companion.LINK_MAX - 23)
        for (body in listOf(Body.GroupLink(GroupId(ByteArray(8) { 1 })), Body.JoinLink(link), Body.Link(link))) {
            val bytes = Codec.encode(Frame(1, body))
            assertEquals(7, body.since)
            assertEquals(body, Codec.decode(bytes).body)
            assertEquals(Unreadable.UNDEFINED, assertFailsWith<DecodeException> { Codec.decode(bytes, 6) }.reason)
        }
        assertFailsWith<IllegalArgumentException> { Codec.encode(Frame(1, Body.JoinLink(link + "A"))) }
        assertTrue("TERNMESH" !in Body.JoinLink(link).toString() && "TERNMESH" !in Body.Link(link).toString())
    }

    /** A frame of a later version than the one both ends speak is one that version does not define. */
    @Test
    fun aFrameOfALaterVersionIsUndefined() {
        val gone = Codec.encode(Frame(1, Body.GroupGone(GroupId(ByteArray(8) { 1 }))))
        assertEquals(Unreadable.UNDEFINED, assertFailsWith<DecodeException> { Codec.decode(gone, 1) }.reason)
        Codec.decode(gone, 2)
        val end = Codec.encode(Frame(1, Body.EndSession(Address(ByteArray(32) { 1 }))))
        assertEquals(Unreadable.UNDEFINED, assertFailsWith<DecodeException> { Codec.decode(end, 0) }.reason)
        Codec.decode(end, 1)
        // Undefined before its fields are read: cut short, it is still a type the version lacks.
        assertEquals(Unreadable.UNDEFINED, assertFailsWith<DecodeException> { Codec.decode(byteArrayOf(0x1A, 1), 0) }.reason)
        assertEquals(Unreadable.MALFORMED, assertFailsWith<DecodeException> { Codec.decode(byteArrayOf(0x1A, 1), 1) }.reason)
        assertEquals(Unreadable.UNDEFINED, assertFailsWith<DecodeException> { Codec.decode(byteArrayOf(0x8A.toByte(), 1), 1) }.reason)
    }

    /**
     * A frame of a type only a later version defines is, to a receiver speaking an earlier one, a
     * type it does not know, whatever its own version: a node answers the code given, and a client
     * reads nothing from it. The connection's half, news ignored and answers discarded, is in
     * ConnectionTest.
     */
    @Test
    fun unknownToOlderIsUndefinedByTheVersionSpoken() {
        val cases = v.list("unknown_to_older")
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val name = c.str("type")
            val version = c.int("version")
            val bytes = c.bytes("frame")
            assertEquals(name, Codec.decode(bytes).body.typeName)
            assertTrue(Codec.decode(bytes).body.since > version, name)
            val e = assertFailsWith<DecodeException>("$version $name") { Codec.decode(bytes, version) }
            assertEquals(Unreadable.UNDEFINED, e.reason, "$version $name")
            val answer = c["answer"].let { if (it == null || it is JsonNull) null else it.jsonPrimitive.int }
            assertEquals(answer, Codec.errorCode(bytes, e.reason), "$version $name")
        }
    }

    /** Positions' signed fields, at their ends, build and read back. */
    @Test
    fun positionsSignedFieldsAtTheirEnds() {
        val south = Body.SetPosition(-900_000_000, -1_800_000_000, Companion.NO_ALTITUDE, 0xFFFF, 0xFFFF)
        assertEquals(south, Codec.decode(Codec.encode(Frame(1, south))).body)
        val group = GroupId(ByteArray(8) { 1 })
        val there = Body.GroupPosition(group, 0xFFFF_FFFFL, 24, Int.MAX_VALUE, Int.MIN_VALUE, 32767, 255, 0xFFFF_FFFFL)
        assertEquals(there, Codec.decode(Codec.encode(Frame(0, there))).body)
        assertFailsWith<IllegalArgumentException> { Codec.encode(Frame(1, south.copy(altitude = -32769))) }
        assertFailsWith<IllegalArgumentException> { Codec.encode(Frame(1, south.copy(accuracy = 0x10000))) }
    }

    @Test
    fun aFrameThatNeverFinishesIsGivenUpAsText() {
        val r = StreamReader()
        assertEquals(emptyList(), r.push(Hex.decode("f554000240")!!))
        assertEquals(listOf(StreamItem.Text(Hex.decode("f554000240")!!)), r.stale())
        assertEquals(0, r.pending.size)
        // And a frame after it is still found.
        assertEquals(listOf(StreamItem.Frame(byteArrayOf(0x40, 0x04))), r.push(Hex.decode("f55400024004a7e8")!!))
    }

    @Test
    fun encodeRefusesWhatIsNotAFrame() {
        val bob = Address(ByteArray(32))
        assertEquals(null, Address.fromHex("00"))
        assertFailsWith<IllegalArgumentException> { Address(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { Codec.encode(Frame(1, Body.Send(1, bob, "x".repeat(129)))) }
        assertFailsWith<IllegalArgumentException> { Codec.encode(Frame(1, Body.SaveContact(bob, "é".repeat(16)))) }
        assertFailsWith<IllegalArgumentException> { Codec.encode(Frame(1, Body.Sync(-1))) }
        assertFailsWith<IllegalArgumentException> { Codec.encode(Frame(256, Body.Ping)) }
        assertFailsWith<IllegalArgumentException> { Codec.encode(Frame(1, Body.Set(Setting.Power(128)))) }
        Codec.encode(Frame(1, Body.Send(1, bob, "x".repeat(128))))
    }

    // The vectors' fields, by name, as the codec's types.
    private fun body(name: String, f: JsonObject): Body = when (name) {
        "HELLO" -> Body.Hello(f.int("version"))
        "SYNC" -> Body.Sync(f.long("after"))
        "PING" -> Body.Ping
        "SET_TIME" -> Body.SetTime(f.long("time"))
        "SET" -> Body.Set(
            when (f.int("setting")) {
                1 -> Setting.Region(f.str("value"))
                2 -> Setting.Role(f.int("value"))
                3 -> Setting.Power(f.int("value"))
                4 -> Setting.Passkey(f.long("value"))
                5 -> Setting.Cards(f.int("value"))
                6 -> Setting.CardName(f.str("value"))
                else -> fail("setting ${f.int("setting")}")
            },
        )
        "SEND" -> Body.Send(f.long("ref"), f.addr("to"), f.str("text"))
        "READ" -> Body.Read(f.long("through"))
        "SAVE_CONTACT" -> Body.SaveContact(f.addr("address"), f.str("name"))
        "REMOVE_CONTACT" -> Body.RemoveContact(f.addr("address"))
        "END_SESSION" -> Body.EndSession(f.addr("address"))
        "MAKE_GROUP" -> Body.MakeGroup(f.str("name"))
        "LEAVE_GROUP" -> Body.LeaveGroup(f.gid("group"))
        "NAME_GROUP" -> Body.NameGroup(f.gid("group"), f.str("name"))
        "SEND_GROUP" -> Body.SendGroup(f.long("ref"), f.gid("group"), f.str("text"))
        "SEND_INVITE" -> Body.SendInvite(f.gid("group"), f.addr("to"))
        "JOIN" -> Body.Join(f.long("id"))
        "GROUP_LINK" -> Body.GroupLink(f.gid("group"))
        "JOIN_LINK" -> Body.JoinLink(f.str("link"))
        "UPDATE_BEGIN" -> Body.UpdateBegin(f.long("size"), f.bytes("digest"))
        "UPDATE_DATA" -> Body.UpdateData(f.long("offset"), f.bytes("data"))
        "UPDATE_END" -> Body.UpdateEnd
        "SET_POSITION" -> Body.SetPosition(f.int("lat"), f.int("lon"), f.int("altitude"), f.int("accuracy"), f.int("age"))
        "SHARE" -> Body.Share(f.addr("contact"), f.int("precision"), f.int("fields"), f.int("interval"), f.int("minutes"))
        "SHARE_GROUP" -> Body.ShareGroup(f.gid("group"), f.int("precision"), f.int("fields"), f.int("interval"), f.int("minutes"))
        "OK" -> Body.Ok
        "ERROR" -> Body.Error(f.int("code"))
        "INFO" -> Body.Info(
            f.int("version"), f.str("firmware"),
            if (f.containsKey("board")) f.str("board") else null,
            if (f.containsKey("release")) f.str("release") else null,
        )
        "SYNCED" -> Body.Synced(if (f.containsKey("news")) f.int("news") else null)
        "QUEUED" -> Body.Queued(f.long("id"))
        "MADE" -> Body.Made(f.gid("group"))
        "UPDATING" -> Body.Updating(f.long("offset"))
        "LINK" -> Body.Link(f.str("link"))
        "SELF" -> Body.Self(
            f.addr("address"), f.int("role"), f.str("region"), f.int("power"), f.long("time"),
            if (f.containsKey("cards")) f.int("cards") else null,
            if (f.containsKey("card_name")) f.str("card_name") else null,
        )
        "CONTACT" -> Body.Contact(f.addr("address"), f.int("session"), f.str("name"))
        "CONTACT_GONE" -> Body.ContactGone(f.addr("address"))
        "MESSAGE" -> Body.Message(
            f.long("id"), f.addr("contact"), f.long("time"), f.int("flags"), f.int("state"), f.int("reason"),
            f.int("wait"), f.str("text"),
        )
        "STATE" -> Body.State(f.long("id"), f.int("state"), f.int("reason"), f.int("wait"))
        "NEIGHBOUR" -> Body.Neighbour(f.long("routing_id"), f.int("role"), f.int("snr_quarter_db"), f.int("heard"))
        "NEIGHBOUR_GONE" -> Body.NeighbourGone(f.long("routing_id"))
        "AIRTIME" -> Body.Airtime(f.long("period"), f.long("allowed"), f.long("used"), f.long("wait"))
        "POWER" -> Body.Power(f.int("millivolts"), f.int("percent"), f.int("flags"))
        "ASKED" -> Body.Asked(f.addr("address"), f.int("why"))
        "GROUP" -> Body.Group(f.gid("group"), f.str("name"))
        "GROUP_GONE" -> Body.GroupGone(f.gid("group"))
        "GROUP_MESSAGE" -> Body.GroupMessage(
            f.long("id"), f.gid("group"), f.long("from"), f.long("time"), f.int("flags"), f.int("state"),
            f.int("reason"), f.int("wait"), f.str("text"),
        )
        "INVITE" -> Body.Invite(
            f.long("id"), f.addr("contact"), f.gid("group"), f.long("time"), f.int("flags"), f.int("state"),
            f.int("reason"), f.int("wait"), f.str("name"),
        )
        "POSITION" -> Body.Position(
            f.addr("contact"), f.int("precision"), f.int("lat"), f.int("lon"), f.int("altitude"), f.int("accuracy"),
            f.long("age"),
        )
        "GROUP_POSITION" -> Body.GroupPosition(
            f.gid("group"), f.long("from"), f.int("precision"), f.int("lat"), f.int("lon"), f.int("altitude"),
            f.int("accuracy"), f.long("age"),
        )
        "SHARING" -> Body.Sharing(f.addr("contact"), f.int("precision"), f.int("fields"), f.int("interval"), f.int("minutes"))
        "GROUP_SHARING" -> Body.GroupSharing(
            f.gid("group"), f.int("precision"), f.int("fields"), f.int("interval"), f.int("minutes"),
        )
        "CARD" -> Body.Card(f.addr("address"), f.long("heard"), f.str("name"))
        "CARD_GONE" -> Body.CardGone(f.addr("address"))
        else -> fail("no frame named $name")
    }
}

/** The vectors: CI sets the property to run against the specification's own, as they are on main. */
internal val vectors: JsonObject by lazy {
    val text = System.getProperty("tern.companion.vectors")?.let { File(it).readText() }
        ?: CompanionVectorsTest::class.java.getResource("/vectors/companion.json")!!.readText()
    Json.parseToJsonElement(text).jsonObject
}

internal fun JsonObject.obj(k: String) = getValue(k).jsonObject
internal fun JsonObject.list(k: String): List<JsonObject> = getValue(k).jsonArray.map(JsonElement::jsonObject)
internal fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content
internal fun JsonObject.int(k: String) = getValue(k).jsonPrimitive.int
internal fun JsonObject.long(k: String) = getValue(k).jsonPrimitive.long
internal fun JsonObject.bytes(k: String) = Hex.decode(str(k))!!
internal fun JsonObject.addr(k: String) = Address(bytes(k))
internal fun JsonObject.gid(k: String) = GroupId(bytes(k))
