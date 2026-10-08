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
        assertTrue(cases.size >= 53)
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
        // And by version 3, version 2's SYNCED is cut short.
        assertFailsWith<DecodeException> { Codec.decode(byteArrayOf(0x43, 0x02)) }
        assertEquals(Body.Synced(null), Codec.decode(byteArrayOf(0x43, 0x02), 2).body)
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
        "OK" -> Body.Ok
        "ERROR" -> Body.Error(f.int("code"))
        "INFO" -> Body.Info(f.int("version"), f.str("firmware"))
        "SYNCED" -> Body.Synced(if (f.containsKey("news")) f.int("news") else null)
        "QUEUED" -> Body.Queued(f.long("id"))
        "MADE" -> Body.Made(f.gid("group"))
        "SELF" -> Body.Self(f.addr("address"), f.int("role"), f.str("region"), f.int("power"), f.long("time"))
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
