// What a client holds of a node: the records its news gave, kept as the specification's "What the
// node holds" says. A record replaces the one before it; STATE changes a message in place; a
// position or sharing of precision 0 is none; a sync is the whole list of contacts, groups,
// neighbours, positions, sharing and cards, but not of messages.
package org.ternmesh.companion

/**
 * Everything a client has been told of one node. An app keeps it between connections, and on disk
 * between runs, so that the next sync asks only for what is new.
 */
class Records {
    var self: Body.Self? = null
        private set
    val contacts = mutableMapOf<Address, Body.Contact>()
    val groups = mutableMapOf<GroupId, Body.Group>()

    /** Messages, group messages and invites, by `id`. */
    val items = mutableMapOf<Long, Item>()
    val neighbours = mutableMapOf<Long, Body.Neighbour>()

    /** Positions received, from contacts and from routing ids in groups. None of precision 0 is held. */
    val positions = mutableMapOf<Address, Body.Position>()
    val groupPositions = mutableMapOf<Pair<GroupId, Long>, Body.GroupPosition>()

    /** Whom the node shares its position with, and how. Sharing that is off is not held. */
    val sharing = mutableMapOf<Address, Body.Sharing>()
    val groupSharing = mutableMapOf<GroupId, Body.GroupSharing>()

    /** Who is about: the cards the node holds from others, one for each address. A contact removed leaves its card: the node keeps it as long as it would have. */
    val cards = mutableMapOf<Address, Body.Card>()

    var airtime: Body.Airtime? = null
        private set
    var power: Body.Power? = null
        private set

    /**
     * The version both ends spoke at the last sync that finished, null if none has. A node may hold
     * records a client of an earlier version was never sent, under `id`s below ones it was.
     */
    var syncedVersion: Int? = null

    /**
     * The greatest `id` held when news was first missed, until a sync that asked again from it
     * finishes; null when nothing is missed. Kept with the records, not the connection: what was
     * lost may be below records received after it, in this run or the one before.
     */
    var missedSince: Long? = null

    /** What the sync under way has sent of the lists a sync gives whole. */
    private var syncing: Seen? = null

    private class Seen {
        val contacts = mutableSetOf<Address>()
        val groups = mutableSetOf<GroupId>()
        val neighbours = mutableSetOf<Long>()
        val positions = mutableSetOf<Address>()
        val groupPositions = mutableSetOf<Pair<GroupId, Long>>()
        val sharing = mutableSetOf<Address>()
        val groupSharing = mutableSetOf<GroupId>()
        val cards = mutableSetOf<Address>()
    }

    /** The items in the order the node gave them `id`s. */
    val ordered: List<Item> get() = items.values.sortedBy { it.id }

    /** Takes one news frame in. News that is not a record of anything (`ASKED`) changes nothing. */
    fun apply(news: Body) {
        when (news) {
            is Body.Self -> self = news
            is Body.Contact -> {
                contacts[news.address] = news
                syncing?.contacts?.add(news.address)
            }
            // A contact removed or a group left takes its positions and sharing with it, as the node
            // forgets them: no record comes to say so.
            is Body.ContactGone -> {
                contacts.remove(news.address)
                positions.remove(news.address)
                sharing.remove(news.address)
            }
            is Body.Group -> {
                groups[news.group] = news
                syncing?.groups?.add(news.group)
            }
            is Body.GroupGone -> {
                groups.remove(news.group)
                groupPositions.keys.removeAll { it.first == news.group }
                groupSharing.remove(news.group)
            }
            is Item -> items[news.id] = news
            is Body.State -> items[news.id]?.let { items[news.id] = it.with(news) }
            is Body.Neighbour -> {
                neighbours[news.routingId] = news
                syncing?.neighbours?.add(news.routingId)
            }
            is Body.NeighbourGone -> neighbours.remove(news.routingId)
            is Body.Position -> {
                if (news.precision == 0) positions.remove(news.contact) else positions[news.contact] = news
                syncing?.positions?.add(news.contact)
            }
            is Body.GroupPosition -> {
                val key = news.group to news.from
                if (news.precision == 0) groupPositions.remove(key) else groupPositions[key] = news
                syncing?.groupPositions?.add(key)
            }
            is Body.Sharing -> {
                if (news.precision == 0) sharing.remove(news.contact) else sharing[news.contact] = news
                syncing?.sharing?.add(news.contact)
            }
            is Body.GroupSharing -> {
                if (news.precision == 0) groupSharing.remove(news.group) else groupSharing[news.group] = news
                syncing?.groupSharing?.add(news.group)
            }
            is Body.Card -> {
                cards[news.address] = news
                syncing?.cards?.add(news.address)
            }
            is Body.CardGone -> cards.remove(news.address)
            is Body.Airtime -> airtime = news
            is Body.Power -> power = news
            else -> {}
        }
    }

    /**
     * The `after` to sync with. Normally the greatest `id` held. After missed news, one less than the
     * least `id` whose state may have changed unseen, and never more than [missedSince]. Speaking a
     * later version to the node than at the last sync, 0, once.
     */
    fun after(version: Int): Long {
        val synced = syncedVersion
        if (synced == null || synced < version) return 0
        val floor = missedSince ?: return greatest
        val least = items.values.filter(::mayChange).minOfOrNull { it.id }
        return minOf(floor, least?.let { it - 1 } ?: floor)
    }

    /** The greatest `id` held, 0 for none. */
    val greatest: Long get() = items.keys.maxOrNull() ?: 0

    /** News was missed: the next sync reaches back to what is held now, or to where it already did. */
    internal fun missed() {
        missedSince = minOf(missedSince ?: Long.MAX_VALUE, greatest)
    }

    internal fun beginSync() {
        syncing = Seen()
    }

    /** `SYNCED`: whatever of the whole lists the sync did not send is gone, and sharing it did not send is off. Returns false, and changes nothing, for a sync abandoned on the way. */
    internal fun finishSync(version: Int): Boolean {
        val seen = syncing ?: return false
        contacts.keys.retainAll(seen.contacts)
        // A sync of version 1 or earlier sends no groups: it says nothing of whether they are gone.
        if (version >= 2) groups.keys.retainAll(seen.groups)
        neighbours.keys.retainAll(seen.neighbours)
        // Nor one of version 4 or earlier of positions and sharing.
        if (version >= 5) {
            positions.keys.retainAll(seen.positions)
            groupPositions.keys.retainAll(seen.groupPositions)
            sharing.keys.retainAll(seen.sharing)
            groupSharing.keys.retainAll(seen.groupSharing)
        }
        // Nor one of version 5 or earlier of cards.
        if (version >= 6) cards.keys.retainAll(seen.cards)
        syncedVersion = version
        missedSince = null
        syncing = null
        return true
    }

    /** A sync that never finished proves nothing about what is gone. */
    internal fun abandonSync() {
        syncing = null
    }

    /** A copy, so a caller can compare before and after. */
    fun copy(): Records = Records().also {
        it.self = self
        it.contacts += contacts
        it.groups += groups
        it.items += items
        it.neighbours += neighbours
        it.positions += positions
        it.groupPositions += groupPositions
        it.sharing += sharing
        it.groupSharing += groupSharing
        it.cards += cards
        it.airtime = airtime
        it.power = power
        it.syncedVersion = syncedVersion
        it.missedSince = missedSince
    }

    override fun equals(other: Any?) = other is Records && self == other.self && contacts == other.contacts &&
        groups == other.groups && items == other.items && neighbours == other.neighbours &&
        positions == other.positions && groupPositions == other.groupPositions && sharing == other.sharing &&
        groupSharing == other.groupSharing && cards == other.cards && airtime == other.airtime && power == other.power &&
        syncedVersion == other.syncedVersion && missedSince == other.missedSince

    override fun hashCode() = listOf(
        self, contacts, groups, items, neighbours, positions, groupPositions, sharing, groupSharing, cards,
        airtime, power, syncedVersion, missedSince,
    ).hashCode()
}

/**
 * Whether it may yet change: a sync after missed news has to reach back to it. A message that is
 * waiting or sent may be delivered; a received one still unread may be read, on another client. A
 * group message that is sent stays sent, so only a waiting one counts.
 */
private fun mayChange(item: Item) = item.isUnread || when (item) {
    is Body.GroupMessage -> item.state == MessageState.WAITING
    else -> item.state == MessageState.WAITING || item.state == MessageState.SENT
}
