// Managing a node over the mesh against the specification's vectors (src/test/resources/vectors/
// managing.json, a copy of vectors/managing.json in ternmesh/spec): what of its conformance section
// a client does. A client builds the frames a request carries and reads the answers; the rest of it
// (who is answered, what is allowed, trials, locks, notices) is the managed node's.
package org.ternmesh.companion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManagingVectorsTest {
    private val v: JsonObject by lazy {
        val text = System.getProperty("tern.managing.vectors")?.let { File(it).readText() }
            ?: ManagingVectorsTest::class.java.getResource("/vectors/managing.json")!!.readText()
        Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun parametersAreTheProtocols() {
        val p = v.obj("parameters")
        assertEquals(p.int("OWNERS"), Companion.OWNERS)
        assertEquals(p.int("REQUEST_MAX"), Companion.REQUEST_MAX)
        assertEquals(p.int("ANSWER_MAX"), Companion.ANSWER_MAX)
        assertEquals(p.long("MESH_ANSWER_WAIT") * 1000, Companion.MESH_ANSWER_WAIT_MS)
        assertEquals(p.long("TRIAL") * 1000, Companion.TRIAL_MS)
    }

    /** A request's content is its kind and one companion request, which the client builds and reads back. */
    @Test
    fun requests() {
        for (c in v.list("requests")) {
            val why = c.str("why")
            val frame = c.bytes("frame")
            assertEquals(c.str("content"), Hex.encode(Managing.request(frame)), why)
            assertEquals(Hex.encode(frame), Managing.readRequest(c.bytes("content"))?.let(Hex::encode), why)
            // Each is a request a node reads; the longest is padded past its fields to the most a request may be.
            assertTrue(Companion.isRequest(Codec.decode(frame).body.type), why)
        }
    }

    /** Answers pack their frames, each after its length, into as few as fit, and unpack to the same frames. */
    @Test
    fun answers() {
        for (c in v.list("answers")) {
            val why = c.str("why")
            val frames = c.getValue("frames").jsonArray.map { Hex.decode(it.jsonPrimitive.content)!! }
            val contents = c.getValue("contents").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(contents, Managing.pack(frames).map(Hex::encode), why)
            val read = contents.flatMap { content ->
                val bytes = Hex.decode(content)!!
                assertEquals(Managing.ANSWER, bytes[0].toInt())
                assertNotNull(Managing.unpack(bytes.copyOfRange(1, bytes.size)), why)
            }
            assertEquals(frames.map(Hex::encode), read.map(Hex::encode), why)
            for (f in read) assertEquals(Hex.encode(f), Hex.encode(Codec.encode(Codec.decode(f))), why)
        }
    }

    /** What a receiver ignores: a request or an answer of nothing, too long, or cut short; and the notices, which only a node reads. */
    @Test
    fun rejected() {
        var some = 0
        for (c in v.list("rejected")) {
            val content = c.bytes("content")
            when (content[0].toInt()) {
                Managing.REQUEST -> assertNull(Managing.readRequest(content), c.str("why"))
                Managing.ANSWER -> assertNull(Managing.unpack(content.copyOfRange(1, content.size)), c.str("why"))
                else -> continue
            }
            some++
        }
        assertTrue(some >= 6)
        assertFailsWith<IllegalArgumentException> { Managing.request(ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { Managing.request(ByteArray(Companion.REQUEST_MAX + 1)) }
    }

    /** A sync over the mesh, read as a client reads it: the managed node's own state, whole. */
    @Test
    fun syncsReadAsTheNodesState() {
        for (c in v.list("syncs")) {
            val records = Records()
            records.beginSync()
            var synced: Body.Synced? = null
            for (content in c.getValue("answers").jsonArray.map { Hex.decode(it.jsonPrimitive.content)!! }) {
                for (f in Managing.unpack(content.copyOfRange(1, content.size))!!) {
                    when (val body = Codec.decode(f).body) {
                        is Body.Synced -> synced = body
                        else -> records.apply(body)
                    }
                }
            }
            assertNotNull(synced)
            assertTrue(records.finishSync(Companion.VERSION))
            val self = c.obj("self")
            assertEquals(self.addr("address"), records.self?.address)
            assertEquals(self.int("role"), records.self?.role)
            assertEquals(c.addr("owner"), records.contacts.keys.single())
            assertEquals(1, records.contacts.values.single().owner)
            assertEquals(c.list("neighbours").map { it.long("routing_id") }.toSet(), records.neighbours.keys)
        }
    }
}
