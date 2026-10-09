// Records kept on disk, and the conversations the app reads out of them.
package org.ternmesh.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    /** The records a file keeps: all but positions and sharing, which the next sync sends whole. */
    private fun kept(r: Records) = r.copy().apply {
        positions.clear()
        groupPositions.clear()
        sharing.clear()
        groupSharing.clear()
    }

    @Test
    fun keepsEveryRecord() {
        val r = fromVectors().apply {
            apply(received(40, alice))
            apply(Body.Group(hikers, "Hikers"))
            syncedVersion = Companion.VERSION
            missedSince = 0xFFFF_FFF0L
        }
        assertEquals(kept(r), RecordsFile.decode(RecordsFile.encode(r)))
    }

    @Test
    fun nothingSyncedOrMissedIsKeptToo() {
        val r = fromVectors()
        val back = RecordsFile.decode(RecordsFile.encode(r))
        assertNull(back.syncedVersion)
        assertNull(back.missedSince)
        assertEquals(kept(r), back)
    }

    /** Positions and sharing are not kept: their counts would go stale on disk. A file that has them is read all the same. */
    @Test
    fun positionsAndSharingAreNotKept() {
        val r = Records().apply {
            apply(Body.Position(bob, 16, 603_945_922, 52_871_704, Companion.NO_ALTITUDE, 0, 40))
            apply(Body.GroupPosition(hikers, 0x1234, 24, 1, 2, 3, 4, 5))
            apply(Body.Sharing(bob, 20, 3, 900, 60))
            apply(Body.GroupSharing(hikers, 12, 0, 300, 0))
            syncedVersion = Companion.VERSION
        }
        val file = RecordsFile.encode(r)
        assertEquals(Records().apply { syncedVersion = Companion.VERSION }, RecordsFile.decode(file))
        val sharing = Codec.encode(Frame(0, r.sharing.values.single()))
        assertEquals(r.sharing, RecordsFile.decode(file + byteArrayOf(sharing.size.toByte()) + sharing).sharing)
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
        assertEquals(empty, RecordsFile.decode(good.copyOf().also { it[5] = (Companion.VERSION + 1).toByte() }), "a version not spoken")
        assertEquals(empty, RecordsFile.decode(good.copyOf().also { it[6] = 2 }), "a missed flag neither 0 nor 1")
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

    /** An unanswered send that reached the node is found in a sync, and not sent again. */
    @Test
    fun aSendTheNodeHoldsIsFound() {
        val r = Records().apply {
            apply(Body.Message(4, bob, 0, 0, MessageState.SENT, 0, 0, "hello"))
            apply(received(5, alice))
            apply(Body.GroupMessage(6, hikers, 0, 0, 0, MessageState.WAITING, 1, 0, "all"))
        }
        assertTrue(Conversations.holdsSent(r, Peer.Contact(bob), "hello", 3))
        assertFalse(Conversations.holdsSent(r, Peer.Contact(bob), "hello", 4), "written before the send")
        assertFalse(Conversations.holdsSent(r, Peer.Contact(alice), "hello", 3), "to someone else")
        assertFalse(Conversations.holdsSent(r, Peer.Contact(alice), "hi 5", 3), "received, not sent")
        assertTrue(Conversations.holdsSent(r, Peer.Group(hikers), "all", 0))
    }

    /** A conversation seen behind another's unread item is read once that one is. */
    @Test
    fun aConversationSeenWaitsForTheOneBeforeIt() {
        val r = Records().apply {
            apply(received(1, alice))
            apply(received(2, bob))
            apply(received(3, bob))
        }
        val bobSeen = mapOf<Peer, Long>(Peer.Contact(bob) to 3)
        assertNull(Conversations.readThrough(r, bobSeen), "Alice's 1 is unread and unseen")
        assertEquals(3, Conversations.readThrough(r, bobSeen + (Peer.Contact(alice) to 1L)), "both seen: all three")
        assertEquals(2, Conversations.readThrough(r, mapOf(Peer.Contact(alice) to 1L, Peer.Contact(bob) to 2L)), "Bob's 3 came after he was seen")
    }
}
