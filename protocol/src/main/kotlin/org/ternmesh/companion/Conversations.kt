// The records as a person reads them: one conversation per address and per group, and how far a
// READ may reach without marking another conversation's messages read.
package org.ternmesh.companion

/** Who a conversation is with. */
sealed interface Peer {
    data class Contact(val address: Address) : Peer

    data class Group(val group: GroupId) : Peer
}

/** One conversation: its items in the order the node gave them ids. */
data class Conversation(val peer: Peer, val name: String, val items: List<Item>) {
    val unread: Int get() = items.count { it.isUnread }
    val last: Item? get() = items.lastOrNull()
}

object Conversations {
    /** Who [item] belongs with: a message and an invite with their address, a group message with its group. */
    fun peerOf(item: Item): Peer = when (item) {
        is Body.Message -> Peer.Contact(item.contact)
        is Body.Invite -> Peer.Contact(item.contact)
        is Body.GroupMessage -> Peer.Group(item.group)
        else -> error("not an item")
    }

    /**
     * Every conversation with an item in it, and every group held, newest first; groups with
     * nothing in them yet come last, by name.
     */
    fun of(records: Records): List<Conversation> {
        val byPeer = records.ordered.groupBy(::peerOf)
        val peers = byPeer.keys + records.groups.keys.map { Peer.Group(it) }
        return peers.map { Conversation(it, name(records, it), byPeer[it].orEmpty()) }
            .sortedWith(compareByDescending<Conversation> { it.last?.id ?: -1L }.thenBy { it.name })
    }

    fun of(records: Records, peer: Peer): Conversation =
        Conversation(peer, name(records, peer), records.ordered.filter { peerOf(it) == peer })

    /**
     * The contact's or group's name, or the start of the address or id where there is none. A
     * contact the user gave no name is shown by the name it gave itself, where it gave one, as [claim].
     */
    fun name(records: Records, peer: Peer): String = when (peer) {
        is Peer.Contact -> records.contacts[peer.address]?.let { c -> c.name.ifEmpty { null } ?: claim(c) } ?: short(peer.address.toString())
        is Peer.Group -> records.groups[peer.group]?.name?.takeIf { it.isNotEmpty() } ?: short(peer.group.toString())
    }

    /**
     * The name of the contact whose address has [routingId], or null for none or one with no name.
     * A group message's routing id is what its writer claimed: the name is who it claims to be.
     */
    fun nameOf(records: Records, routingId: Long): String? =
        contactOf(records, routingId)?.name?.takeIf { it.isNotEmpty() }

    /** The contact whose address has [routingId], as the node makes routing ids, or null for none. */
    fun contactOf(records: Records, routingId: Long): Body.Contact? =
        records.contacts.values.firstOrNull { records.routingIdOf(it.address) == routingId }

    /**
     * The card held from the address that has [routingId], to name a neighbour no contact names: null
     * when a contact has that routing id, whose name is the user's own where a card's is only a claim;
     * null for no card; and null when two cards held have it, which cannot be told apart by it.
     */
    fun cardOf(records: Records, routingId: Long): Body.Card? {
        if (contactOf(records, routingId) != null) return null
        return records.cards.values.filter { records.routingIdOf(it.address) == routingId }.singleOrNull()
    }

    /** The first 8 hex digits, to tell addresses apart at a glance. Not a check that one is who it claims. */
    fun short(hex: String) = hex.take(8) + "…"

    /**
     * The name [contact] gave itself in first contact (`CONTACT`'s `says`, version 19), as its claim:
     * in quotes, with the start of its address, so that it is not taken for a name the user gave it
     * (draft/first-contact.md, Names). Null where it gave none.
     */
    fun claim(contact: Body.Contact): String? =
        contact.says?.takeIf { it.isNotEmpty() }?.let { "“$it” · ${short(contact.address.toString())}" }

    /**
     * The `through` to send a `READ` with once [peer]'s conversation has been seen, or null for none.
     * A `READ` marks every received item up to `through`, in every conversation, so it stops short
     * of the first unread item elsewhere: an item here past that stays unread until that one is read.
     */
    fun readThrough(records: Records, peer: Peer): Long? = readThrough(records, mapOf(peer to Long.MAX_VALUE))

    /**
     * The `through` to send a `READ` with, given the conversations the user has seen, each up to the
     * greatest id it held when seen: as far up the unread items as each is in a conversation seen
     * that far. A conversation seen while another's unread item came first is read once that one is.
     */
    fun readThrough(records: Records, seen: Map<Peer, Long>): Long? {
        var through: Long? = null
        for (item in records.ordered) {
            if (!item.isUnread) continue
            val upTo = seen[peerOf(item)] ?: break
            if (item.id > upTo) break
            through = item.id
        }
        return through
    }

    /**
     * Whether the node holds a message the user wrote to [peer] with [text], under an id past
     * [after]. A send the node never answered for may have reached it all the same; once a sync
     * shows it did, it is not sent again. The node knows a `SEND`'s `ref` only among its last
     * [Companion.REFS] messages, so this, not the `ref`, keeps a late retry from sending twice.
     */
    fun holdsSent(records: Records, peer: Peer, text: String, after: Long): Boolean = records.items.values.any { item ->
        item.id > after && item.state != MessageState.RECEIVED && peerOf(item) == peer && when (item) {
            is Body.Message -> item.text == text
            is Body.GroupMessage -> item.text == text
            else -> false
        }
    }
}
