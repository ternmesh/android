// Records kept on disk, and the conversations the app reads out of them.
package org.ternmesh.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecordsFileTest {
    private val alice = Address(ByteArray(32) { 0xA1.toByte() })
    private val bob = Address(ByteArray(32) { 0xB0.toByte() })
    private val hikers = GroupId(ByteArray(8) { 0x48 })

    private fun received(id: Long, from: Address, read: Boolean = false) =
        Body.Message(id, from, 1_700_000_000, if (read) 1 else 0, MessageState.RECEIVED, 0, 0, "hi $id")

    private fun groupReceived(id: Long) =
        Body.GroupMessage(id, hikers, 0x1234, 1_700_000_000, 0, MessageState.RECEIVED, 0, 0, "all $id")

    /** Every news frame of the specification's frames that is a record, as the node would send them. */
    private fun fromVectors(): Records = Records().also { r ->
        for (f in vectors.list("frames")) {
            val body = Codec.decode(f.bytes("frame")).body
            if (Companion.isNews(body.type)) r.apply(body)
        }
    }

    @Test
    fun keepsEveryRecord() {
        val r = fromVectors().apply {
            apply(received(40, alice))
            apply(Body.Group(hikers, "Hikers"))
            syncedVersion = 2
            missedSince = 0xFFFF_FFF0L
        }
        assertEquals(r, RecordsFile.decode(RecordsFile.encode(r)))
    }

    @Test
    fun nothingSyncedOrMissedIsKeptToo() {
        val r = fromVectors()
        val back = RecordsFile.decode(RecordsFile.encode(r))
        assertNull(back.syncedVersion)
        assertNull(back.missedSince)
        assertEquals(r, back)
    }

    /** What cannot be read whole starts again from nothing, and a sync from 0 refills it. */
    @Test
    fun unreadableIsEmpty() {
        val good = RecordsFile.encode(Records().apply { apply(received(3, alice)); syncedVersion = 3 })
        val empty = Records()
        assertEquals(empty, RecordsFile.decode(ByteArray(0)))
        assertEquals(empty, RecordsFile.decode(good.copyOf(good.size - 1)), "cut short")
        assertEquals(empty, RecordsFile.decode(good.copyOf().also { it[0] = 'X'.code.toByte() }), "not this format")
        assertEquals(empty, RecordsFile.decode(good.copyOf().also { it[4] = 2 }), "a later format")
        val notARecord = Codec.encode(Frame(0, Body.Ok))
        assertEquals(empty, RecordsFile.decode(good + byteArrayOf(notARecord.size.toByte()) + notARecord), "an answer is no record")
    }

    @Test
    fun conversationsNewestFirstWithEmptyGroupsLast() {
        val r = Records().apply {
            apply(received(1, alice))
            apply(received(2, bob, read = true))
            apply(Body.Group(hikers, "Hikers"))
            apply(Body.Group(GroupId(ByteArray(8)), ""))
            apply(Body.Contact(bob, 1, "Bob"))
        }
        val c = Conversations.of(r)
        assertEquals(listOf("Bob", Conversations.short(alice.toString()), "00000000…", "Hikers"), c.map { it.name })
        assertEquals(listOf(0, 1, 0, 0), c.map { it.unread })
    }

    /** A READ stops short of the first unread item in another conversation, which it would mark too. */
    @Test
    fun readReachesNoFurtherThanOtherConversations() {
        val r = Records().apply {
            apply(received(1, alice))
            apply(received(2, bob))
            apply(groupReceived(3))
            apply(received(4, alice))
        }
        val a = Peer.Contact(alice)
        assertEquals(1, Conversations.readThrough(r, a), "4 would mark Bob's 2 and the group's 3 read")
        assertNull(Conversations.readThrough(r, Peer.Contact(bob)), "Alice's 1 comes before Bob's 2")
        r.apply(r.items.getValue(1).let { (it as Body.Message).copy(flags = 1) })
        assertEquals(2, Conversations.readThrough(r, Peer.Contact(bob)))
        assertNull(Conversations.readThrough(r, Peer.Group(GroupId(ByteArray(8)))), "nothing unread there")
        r.apply((r.items.getValue(2) as Body.Message).copy(flags = 1))
        r.apply((r.items.getValue(3) as Body.GroupMessage).copy(flags = 1))
        assertEquals(4, Conversations.readThrough(r, a))
    }
}
