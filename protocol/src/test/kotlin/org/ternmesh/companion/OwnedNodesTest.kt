// The nodes the app's node owns, as the app learns them: from the notices its node passes on, and
// from what managing a node over the mesh came to; and kept on disk.
package org.ternmesh.companion

import kotlin.test.Test
import kotlin.test.assertEquals

class OwnedNodesTest {
    private val self = Address(ByteArray(32) { 0x5E })
    private val relay = Address(ByteArray(32) { 0x3D })
    private val other = Address(ByteArray(32) { 0x0B })
    private val carol = Address(ByteArray(32) { 0xCA.toByte() })

    /** A notice that the app's node was made an owner adds the node; one that it no longer is, removes it. */
    @Test
    fun aNoticeAboutItselfAddsOrRemoves() {
        val made = OwnedNodes.afterNotice(emptySet(), self, Body.OwnerChanged(relay, self, 1, 0))
        assertEquals(setOf(relay), made)
        assertEquals(emptySet(), OwnedNodes.afterNotice(made, self, Body.OwnerChanged(relay, self, 0, 1)))
        // Whether over a link or over the mesh, the same.
        assertEquals(setOf(relay), OwnedNodes.afterNotice(emptySet(), self, Body.OwnerChanged(relay, self, 1, 1)))
    }

    /** A notice about another address still comes only to an owner: the app's node is one. */
    @Test
    fun aNoticeAboutAnotherSaysItIsStillAnOwner() {
        assertEquals(setOf(relay), OwnedNodes.afterNotice(emptySet(), self, Body.OwnerChanged(relay, carol, 0, 0)))
        assertEquals(setOf(relay, other), OwnedNodes.afterNotice(setOf(other), self, Body.OwnerChanged(relay, carol, 1, 1)))
    }

    /** Before the app knows its own node's address, or from that node itself, a notice says nothing. */
    @Test
    fun withoutItsOwnAddressNothing() {
        assertEquals(setOf(relay), OwnedNodes.afterNotice(setOf(relay), null, Body.OwnerChanged(relay, self, 0, 0)))
        assertEquals(emptySet(), OwnedNodes.afterNotice(emptySet(), self, Body.OwnerChanged(self, carol, 1, 0)))
    }

    /** Any answer over the mesh makes it an owner; `ERROR` 15, not one. */
    @Test
    fun anAnswerOrError15() {
        val owned = OwnedNodes.afterAnswer(emptySet(), relay, owner = true)
        assertEquals(setOf(relay), owned)
        assertEquals(setOf(relay), OwnedNodes.afterAnswer(owned, relay, owner = true))
        assertEquals(emptySet(), OwnedNodes.afterAnswer(owned, relay, owner = false))
        assertEquals(emptySet(), OwnedNodes.afterAnswer(emptySet(), relay, owner = false))
    }

    /** The set survives being written and read back, as a restart reads it; anything spoilt reads as none. */
    @Test
    fun theSetSurvivesBeingReloaded() {
        val owned = setOf(relay, other, carol)
        assertEquals(owned, OwnedNodes.decode(OwnedNodes.encode(owned)))
        assertEquals(emptySet(), OwnedNodes.decode(OwnedNodes.encode(emptySet())))
        val bytes = OwnedNodes.encode(owned)
        assertEquals(emptySet(), OwnedNodes.decode(bytes.copyOf(bytes.size - 1)))
        assertEquals(emptySet(), OwnedNodes.decode(ByteArray(0)))
        assertEquals(emptySet(), OwnedNodes.decode("TRNO".toByteArray() + byteArrayOf(1)))
    }
}
