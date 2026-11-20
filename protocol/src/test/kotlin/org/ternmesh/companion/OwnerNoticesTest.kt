// The owner-change notices the app keeps on disk until the user dismisses them.
package org.ternmesh.companion

import kotlin.test.Test
import kotlin.test.assertEquals

class OwnerNoticesTest {
    private val relay = Address(ByteArray(32) { 0x3D })
    private val carol = Address(ByteArray(32) { 0xCA.toByte() })
    private val alice = Address(ByteArray(32) { 0xA1.toByte() })

    /** A notice survives the file being written and read back, in order, as a restart reads it. */
    @Test
    fun aNoticeSurvivesBeingReloaded() {
        val made = Body.OwnerChanged(relay, carol, 1, 0)
        val gone = Body.OwnerChanged(relay, alice, 0, 1)
        val held = OwnerNotices.add(OwnerNotices.add(emptyList(), made), gone)
        assertEquals(listOf(made, gone), OwnerNotices.decode(OwnerNotices.encode(held)))
        // Dismissed, it is gone from what is kept.
        assertEquals(listOf(gone), OwnerNotices.decode(OwnerNotices.encode(held - made)))
        assertEquals(emptyList(), OwnerNotices.decode(OwnerNotices.encode(emptyList())))
    }

    /** Only the last 20 are kept, and the same notice again is one, the newest. */
    @Test
    fun theLastTwentyAreKept() {
        var held = emptyList<Body.OwnerChanged>()
        val all = (0 until 25).map { Body.OwnerChanged(Address(ByteArray(32) { i -> (i + it).toByte() }), carol, it % 2, 0) }
        for (n in all) held = OwnerNotices.add(held, n)
        assertEquals(all.takeLast(OwnerNotices.KEPT), held)
        assertEquals(held, OwnerNotices.decode(OwnerNotices.encode(held)))
        held = OwnerNotices.add(held, all[10])
        assertEquals(OwnerNotices.KEPT, held.size)
        assertEquals(all[10], held.last())
        assertEquals(1, held.count { it == all[10] })
    }

    /** What cannot be read whole is none: a later format, a file cut short, or a frame that is not a notice. */
    @Test
    fun unreadableIsNone() {
        val good = OwnerNotices.encode(listOf(Body.OwnerChanged(relay, carol, 1, 0)))
        assertEquals(emptyList(), OwnerNotices.decode(ByteArray(0)))
        assertEquals(emptyList(), OwnerNotices.decode(good.copyOf(good.size - 1)))
        assertEquals(emptyList(), OwnerNotices.decode(good.copyOf().also { it[4] = 2 }))
        val ok = Codec.encode(Frame(0, Body.Ok))
        assertEquals(emptyList(), OwnerNotices.decode(good + byteArrayOf(ok.size.toByte()) + ok))
    }
}
