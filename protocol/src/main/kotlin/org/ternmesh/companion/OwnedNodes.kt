// The nodes the app's own node is an owner of, as far as the app has learned (version 17): from the
// notices that a node's owners changed, and from what managing a node over the mesh came to. The
// node keeps no such list, and a sync does not say, so the app keeps it on disk beside the records.
//
// It decides what a contact's menu offers: Manage for a node in it, and for any other a check, one
// HELLO, that adds it or not.
//
//   "TRNW", the format (1); then, to the end, the addresses, 32 bytes each.
package org.ternmesh.companion

import java.io.ByteArrayOutputStream

object OwnedNodes {
    /** The most kept: more than anyone owns, and a file that cannot grow without end. */
    const val KEPT = 256

    private val MAGIC = "TRNW".toByteArray(Charsets.US_ASCII)
    private const val FORMAT = 1

    /**
     * [owned] after [notice], which the app's own node, at [self], passed on. A notice whose
     * `contact` is [self] says whether it is now an owner of `from` or no longer one. One about
     * another address still says [self] is an owner: a node tells only its owners, before the
     * change or after it, and [self]'s part did not change. With [self] not yet known, nothing.
     */
    fun afterNotice(owned: Set<Address>, self: Address?, notice: Body.OwnerChanged): Set<Address> = when {
        self == null || notice.from == self -> owned
        notice.contact != self -> owned + notice.from
        notice.owner == 1 -> owned + notice.from
        else -> owned - notice.from
    }

    /**
     * [owned] once the node at [node] answered a request over the mesh: any answer means it holds
     * the app's node as an owner, and `ERROR` 15 that it does not. [owner] is which.
     */
    fun afterAnswer(owned: Set<Address>, node: Address, owner: Boolean): Set<Address> =
        if (owner) owned + node else owned - node

    fun encode(owned: Set<Address>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(FORMAT)
        for (a in owned.sortedBy { it.toString() }.take(KEPT)) out.write(a.toByteArray())
        return out.toByteArray()
    }

    /** The addresses [bytes] hold; none from anything this cannot read whole. */
    fun decode(bytes: ByteArray): Set<Address> {
        val at = MAGIC.size + 1
        if (bytes.size < at || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) || bytes[MAGIC.size].toInt() != FORMAT) {
            return emptySet()
        }
        if ((bytes.size - at) % Address.LENGTH != 0) return emptySet()
        return (at until bytes.size step Address.LENGTH)
            .map { Address(bytes.copyOfRange(it, it + Address.LENGTH)) }
            .take(KEPT)
            .toSet()
    }
}
