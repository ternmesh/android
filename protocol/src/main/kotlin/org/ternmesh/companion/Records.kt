// What a client holds of a node: the records its news gave, kept as the specification's "What the
// node holds" says. A record replaces the one before it; STATE changes a message in place; a sync
// is the whole list of contacts, groups and neighbours, but not of messages.
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
    var airtime: Body.Airtime? = null
        private set
    var power: Body.Power? = null
        private set

    /**
     * The version both ends spoke at the last sync that finished, null if none has. A node may hold
     * records a client of an earlier version was never sent, under `id`s below ones it was.
     */
    var syncedVersion: Int? = null

    /** What the sync under way has sent of the three lists a sync gives whole. */
    private var syncing: Seen? = null

    private class Seen {
        val contacts = mutableSetOf<Address>()
        val groups = mutableSetOf<GroupId>()
        val neighbours = mutableSetOf<Long>()
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
            is Body.ContactGone -> contacts.remove(news.address)
            is Body.Group -> {
                groups[news.group] = news
                syncing?.groups?.add(news.group)
            }
            is Body.GroupGone -> groups.remove(news.group)
            is Item -> items[news.id] = news
            is Body.State -> items[news.id]?.let { items[news.id] = it.with(news) }
            is Body.Neighbour -> {
                neighbours[news.routingId] = news
                syncing?.neighbours?.add(news.routingId)
            }
            is Body.NeighbourGone -> neighbours.remove(news.routingId)
            is Body.Airtime -> airtime = news
            is Body.Power -> power = news
            else -> {}
        }
    }

    /**
     * The `after` to sync with. Normally the greatest `id` held. After missed news, one less than the
     * least `id` whose state may have changed unseen, and never more than [missedSince], the greatest
     * `id` held when news was first missed: what was lost may be below records received after it.
     * Speaking a later version to the node than at the last sync, 0, once.
     */
    fun after(version: Int, missedSince: Long? = null): Long {
        val synced = syncedVersion
        if (synced == null || synced < version) return 0
        val floor = missedSince ?: return greatest
        val least = items.values.filter(::mayChange).minOfOrNull { it.id }
        return minOf(floor, least?.let { it - 1 } ?: floor)
    }

    /** The greatest `id` held, 0 for none. */
    val greatest: Long get() = items.keys.maxOrNull() ?: 0

    internal fun beginSync() {
        syncing = Seen()
    }

    /** `SYNCED`: whatever of the three whole lists the sync did not send is gone. Returns false, and changes nothing, for a sync abandoned on the way. */
    internal fun finishSync(version: Int): Boolean {
        val seen = syncing ?: return false
        contacts.keys.retainAll(seen.contacts)
        groups.keys.retainAll(seen.groups)
        neighbours.keys.retainAll(seen.neighbours)
        syncedVersion = version
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
        it.airtime = airtime
        it.power = power
        it.syncedVersion = syncedVersion
    }

    override fun equals(other: Any?) = other is Records && self == other.self && contacts == other.contacts &&
        groups == other.groups && items == other.items && neighbours == other.neighbours &&
        airtime == other.airtime && power == other.power && syncedVersion == other.syncedVersion

    override fun hashCode() = listOf(self, contacts, groups, items, neighbours, airtime, power, syncedVersion).hashCode()
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
