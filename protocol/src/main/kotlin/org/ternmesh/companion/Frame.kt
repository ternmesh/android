// The companion protocol's frames, version 6: draft/companion.md in ternmesh/spec.
//
// Nothing here touches Bluetooth or a screen. It builds frames and reads them, and the tests hold
// it to the specification's vectors.
//
// Numbers are Kotlin's signed types, wide enough for the field: Int for u8, i8, u16, i16 and i32,
// Long for u32. Building a frame checks each fits.
package org.ternmesh.companion

/** The protocol's numbers, as the specification's Parameters give them. */
object Companion {
    /** The version this client speaks. Version 5 is this without cards, version 4 is version 5 without positions, version 3 is version 4 without updates, version 2 is version 3 without `SYNCED`'s `news`, version 1 is version 2 without groups, and version 0 is version 1 without `END_SESSION` and `ASKED`. */
    const val VERSION = 6
    const val MAX_FRAME = 180
    const val TEXT_MAX = 128
    const val NAME_MAX = 31
    const val REGION_MAX = 15
    const val FIRMWARE_MAX = 31
    const val BOARD_MAX = 31
    const val RELEASE_MAX = 31

    /** The name a node's cards carry, and a card's. */
    const val CARD_NAME_MAX = 31

    /** The `data` an `UPDATE_DATA` carries, all but the last: the longest that fits a frame. */
    const val UPDATE_CHUNK = 172

    /** A position's `altitude` when it has none. */
    const val NO_ALTITUDE = -32768

    /** The finest precision a position is shared at; 0 is none, or sharing off. */
    const val PRECISION_MAX = 24

    /** A SHA-256 digest's length. */
    const val DIGEST = 32

    /** How long a client waits for an answer. */
    const val ANSWER_WAIT_MS = 5_000L

    /** The longest a client goes without a request. */
    const val IDLE_MS = 20_000L

    /** How long a node on a serial port waits for a request before it takes the client for gone. */
    const val LAPSE_MS = 60_000L

    /** How long a byte stream may stall mid-frame before what is held is text. */
    const val GAP_MS = 500L

    /** How many of its last messages a node matches a `SEND`'s `ref` against. */
    const val REFS = 16

    /** The least time between two `ASKED`s for one address, or two of `NEIGHBOUR`, `AIRTIME` or `POWER`. */
    const val QUIET_MS = 10_000L

    /** The GATT service a node offers, and its two characteristics. */
    const val SERVICE = "7a280001-eb17-4c1c-889b-1741dd50ff40"
    const val TO_NODE = "7a280002-eb17-4c1c-889b-1741dd50ff40"
    const val FROM_NODE = "7a280003-eb17-4c1c-889b-1741dd50ff40"

    /** The least ATT MTU a client asks for: a 180-byte frame and the 3 bytes ATT adds. */
    const val MIN_MTU = 183

    fun isRequest(type: Int) = type in 0x01..0x3F
    fun isAnswer(type: Int) = type in 0x40..0x7F
    fun isNews(type: Int) = type in 0x80..0xBF

    /** The least version that defines the frame type [type]. */
    fun since(type: Int): Int = when (type) {
        0x1A, 0x89 -> 1
        in 0x20..0x25, 0x45, in 0x8A..0x8D -> 2
        in 0x30..0x32, 0x46 -> 4
        in 0x33..0x35, in 0x8E..0x91 -> 5
        0x92, 0x93 -> 6
        else -> 0
    }

    /** The least version that defines `SET`'s setting [setting]. */
    fun settingSince(setting: Int): Int = if (setting in 5..6) 6 else 0
}

/** A node's address: an Ed25519 public key. */
class Address(bytes: ByteArray) {
    private val bytes = bytes.copyOf()

    init {
        require(bytes.size == LENGTH) { "an address is $LENGTH bytes, not ${bytes.size}" }
    }

    fun toByteArray(): ByteArray = bytes.copyOf()

    /**
     * The routing id routing knows the node by, as draft/routing.md works it out from the address: a
     * neighbour, and the writer of a group message, are known to a client only by theirs.
     */
    val routingId: Long by lazy {
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        sha.update("tern routing id".toByteArray(Charsets.US_ASCII))
        val h = sha.digest(bytes)
        (0 until 8).map { i -> (0 until 4).fold(0L) { n, j -> (n shl 8) or (h[4 * i + j].toLong() and 0xFF) } }
            .first { it != 0L && it != 0xFFFF_FFFFL }
    }

    override fun equals(other: Any?) = other is Address && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = Hex.encode(bytes)

    companion object {
        const val LENGTH = 32

        /** The address written as hex, or null if it is not one. */
        fun fromHex(text: String): Address? = Hex.decode(text)?.takeIf { it.size == LENGTH }?.let(::Address)
    }
}

/** A group's id, which the node works out from the group's secret. A client never holds the secret: no frame carries it. */
class GroupId(bytes: ByteArray) {
    private val bytes = bytes.copyOf()

    init {
        require(bytes.size == LENGTH) { "a group id is $LENGTH bytes, not ${bytes.size}" }
    }

    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?) = other is GroupId && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = Hex.encode(bytes)

    companion object {
        const val LENGTH = 8

        /** The id written as hex, or null if it is not one. */
        fun fromHex(text: String): GroupId? = Hex.decode(text)?.takeIf { it.size == LENGTH }?.let(::GroupId)
    }
}

/** An `ERROR`'s code. A code this client does not know is a refusal all the same. */
object ErrorCode {
    /** A request, or a setting, the node's version does not define. */
    const val UNDEFINED = 1
    const val MALFORMED = 2

    /** A value the node refuses: a region it does not have, a power it cannot send at, empty text. */
    const val REFUSED = 3

    /** Not a valid address, or the node's own. */
    const val BAD_ADDRESS = 4

    /** The node cannot hold another contact, message or group. */
    const val NO_ROOM = 5

    /** `HELLO` first: on a connection that had one, the node has taken the client for gone. */
    const val HELLO_FIRST = 6

    /** The Bluetooth link's MTU is too small. */
    const val MTU = 7

    /** Not now: the node is finishing something else. */
    const val NOT_NOW = 8

    /** A group the node is not in, or an invite it does not hold. */
    const val NOT_HELD = 9

    /** Not where the update is: none under way, or not at that offset. `UPDATE_BEGIN` again says where. */
    const val NOT_THERE = 10

    /** Not an image this node runs, or not the one its digest names: the update is discarded. */
    const val NOT_AN_IMAGE = 11

    /** Not a contact: `SHARE` names an address the node does not hold as one. */
    const val NOT_A_CONTACT = 12
}

/**
 * A message, group message or invite: the three records that share the node's count of `id`s, and
 * a message's states.
 */
sealed interface Item {
    val id: Long
    val flags: Int
    val state: Int
    val reason: Int
    val wait: Int

    /** Received, and not yet marked read. */
    val isUnread get() = state == MessageState.RECEIVED && flags and 1 == 0

    /** Where it is now: `STATE` replaces these three fields and no others. */
    fun with(s: Body.State): Item
}

/** A frame: its sequence number and what it says. The type byte follows from the body. */
data class Frame(val seq: Int, val body: Body)

/** One of `SET`'s settings, with its value. */
sealed interface Setting {
    val number: Int

    /** The least version that defines this setting. */
    val since: Int get() = Companion.settingSince(number)

    data class Region(val name: String) : Setting { override val number get() = 1 }

    /** 0 a leaf, 1 a relay. */
    data class Role(val role: Int) : Setting { override val number get() = 2 }

    /** The most the node transmits at, in dBm. */
    data class Power(val dbm: Int) : Setting { override val number get() = 3 }

    /** 0 to 999999, or [RANDOM] for a random one each time. */
    data class Passkey(val passkey: Long) : Setting {
        override val number get() = 4

        companion object {
            const val RANDOM = 0xFFFF_FFFFL
        }
    }

    /** 1 to send cards, 0 to send none. Only ever what the user asked for. */
    data class Cards(val cards: Int) : Setting { override val number get() = 5 }

    /** The name the node's cards carry, the one name it puts on the air in clear; empty for none. Only ever what the user asked for. */
    data class CardName(val name: String) : Setting { override val number get() = 6 }
}

/** A message's state. */
object MessageState {
    const val WAITING = 0
    const val SENT = 1
    const val DELIVERED = 2
    const val NOT_DELIVERED = 3
    const val RECEIVED = 4
}

/** What a frame says: every frame of version 6. [since] is the least version that defines it. */
sealed class Body(val type: Int, val typeName: String) {
    /** The least version that defines this frame: a client sends no request the node's version does not define, and reads no frame the version both ends speak does not. */
    open val since: Int get() = Companion.since(type)

    // Requests, sent by the client.
    data class Hello(val version: Int) : Body(0x01, "HELLO")
    data class Sync(val after: Long) : Body(0x02, "SYNC")
    data object Ping : Body(0x03, "PING")
    data class SetTime(val time: Long) : Body(0x04, "SET_TIME")
    data class Set(val setting: Setting) : Body(0x05, "SET") {
        /** The setting's: a node answers one the client's version does not define as it does a request it does not. */
        override val since: Int get() = setting.since
    }
    data class Send(val ref: Long, val to: Address, val text: String) : Body(0x10, "SEND")
    data class Read(val through: Long) : Body(0x11, "READ")
    data class SaveContact(val address: Address, val name: String) : Body(0x18, "SAVE_CONTACT")
    data class RemoveContact(val address: Address) : Body(0x19, "REMOVE_CONTACT")
    data class EndSession(val address: Address) : Body(0x1A, "END_SESSION")
    data class MakeGroup(val name: String) : Body(0x20, "MAKE_GROUP")
    data class LeaveGroup(val group: GroupId) : Body(0x21, "LEAVE_GROUP")
    data class NameGroup(val group: GroupId, val name: String) : Body(0x22, "NAME_GROUP")
    data class SendGroup(val ref: Long, val group: GroupId, val text: String) : Body(0x23, "SEND_GROUP")
    data class SendInvite(val group: GroupId, val to: Address) : Body(0x24, "SEND_INVITE")
    data class Join(val id: Long) : Body(0x25, "JOIN")

    /** An image of [size] bytes, whose SHA-256 is [digest], follows. */
    class UpdateBegin(val size: Long, digest: ByteArray) : Body(0x30, "UPDATE_BEGIN") {
        private val bytes = digest.copyOf()

        init {
            require(digest.size == Companion.DIGEST) { "a digest is ${Companion.DIGEST} bytes, not ${digest.size}" }
        }

        val digest: ByteArray get() = bytes.copyOf()

        override fun equals(other: Any?) = other is UpdateBegin && size == other.size && bytes.contentEquals(other.bytes)
        override fun hashCode() = 31 * size.hashCode() + bytes.contentHashCode()
        override fun toString() = "UpdateBegin(size=$size, digest=${Hex.encode(bytes)})"
    }

    /** The image's bytes from [offset]. */
    class UpdateData(val offset: Long, data: ByteArray) : Body(0x31, "UPDATE_DATA") {
        private val bytes = data.copyOf()

        val data: ByteArray get() = bytes.copyOf()

        override fun equals(other: Any?) = other is UpdateData && offset == other.offset && bytes.contentEquals(other.bytes)
        override fun hashCode() = 31 * offset.hashCode() + bytes.contentHashCode()
        override fun toString() = "UpdateData(offset=$offset, ${bytes.size} bytes)"
    }

    data object UpdateEnd : Body(0x32, "UPDATE_END")

    /**
     * The client's own position: [lat] and [lon] in 10⁻⁷ degree, north and east positive;
     * [altitude] in metres, [Companion.NO_ALTITUDE] for none; [accuracy] in metres, 0 for none;
     * [age] how many seconds old the fix is.
     */
    data class SetPosition(val lat: Int, val lon: Int, val altitude: Int, val accuracy: Int, val age: Int) :
        Body(0x33, "SET_POSITION")

    /** Sharing with [contact] on, changed, or with [precision] 0 off; [minutes] 0 until turned off. Only ever what the user asked for. */
    data class Share(val contact: Address, val precision: Int, val fields: Int, val interval: Int, val minutes: Int) :
        Body(0x34, "SHARE")

    /** [Share] for a group. */
    data class ShareGroup(val group: GroupId, val precision: Int, val fields: Int, val interval: Int, val minutes: Int) :
        Body(0x35, "SHARE_GROUP")

    // Answers, sent by the node with the request's seq.
    data object Ok : Body(0x40, "OK")
    data class Error(val code: Int) : Body(0x41, "ERROR")
    /**
     * The node's version and software. [board] and [release] are null below version 4, from either
     * end; [board] is empty for a node that cannot be updated over the protocol, [release] for
     * firmware without a version.
     */
    data class Info(
        val version: Int,
        val firmware: String,
        val board: String? = null,
        val release: String? = null,
    ) : Body(0x42, "INFO")
    /** The sync is done. [news] is the node's count as it answers, the `seq` of its next news frame; null from a node of version 2 or earlier, whose `SYNCED` has no fields. */
    data class Synced(val news: Int? = null) : Body(0x43, "SYNCED")
    data class Queued(val id: Long) : Body(0x44, "QUEUED")
    data class Made(val group: GroupId) : Body(0x45, "MADE")

    /** The offset to send an update's image from. */
    data class Updating(val offset: Long) : Body(0x46, "UPDATING")

    // News, sent by the node with its count as seq.

    /**
     * The node itself. [time] is 0 if it does not know it. [cards] is 1 if it sends cards, and
     * [cardName] the name they carry; both are null below version 6, from either end.
     */
    data class Self(
        val address: Address,
        val role: Int,
        val region: String,
        val power: Int,
        val time: Long,
        val cards: Int? = null,
        val cardName: String? = null,
    ) : Body(0x80, "SELF")

    /** An address the user saved, with a name. [session] is 1 if the node shares one with it. */
    data class Contact(val address: Address, val session: Int, val name: String) : Body(0x81, "CONTACT")

    data class ContactGone(val address: Address) : Body(0x82, "CONTACT_GONE")

    /** One message, the whole of it. [flags] bit 0: a received message has been read. */
    data class Message(
        override val id: Long,
        val contact: Address,
        val time: Long,
        override val flags: Int,
        override val state: Int,
        override val reason: Int,
        override val wait: Int,
        val text: String,
    ) : Body(0x83, "MESSAGE"), Item {
        override fun with(s: State) = copy(state = s.state, reason = s.reason, wait = s.wait)
    }

    data class State(val id: Long, val state: Int, val reason: Int, val wait: Int) : Body(0x84, "STATE")

    /** A node this one hears. [snrQuarterDb] is in quarters of a dB; [heard] seconds ago. */
    data class Neighbour(
        val routingId: Long,
        val role: Int,
        val snrQuarterDb: Int,
        val heard: Int,
    ) : Body(0x85, "NEIGHBOUR")

    data class NeighbourGone(val routingId: Long) : Body(0x86, "NEIGHBOUR_GONE")

    /** The region's limit on transmitting: [period] in seconds, the rest in milliseconds. */
    data class Airtime(val period: Long, val allowed: Long, val used: Long, val wait: Long) :
        Body(0x87, "AIRTIME")

    /** [millivolts] 0 if unmeasured, [percent] 255 if unknown; [flags] bit 0 charging, bit 1 external power. */
    data class Power(val millivolts: Int, val percent: Int, val flags: Int) : Body(0x88, "POWER")

    /**
     * The node refused first contact from [address], which proved itself: [why] is 1 if it is not a
     * contact, 2 if the node has no room for another session.
     */
    data class Asked(val address: Address, val why: Int) : Body(0x89, "ASKED")

    /** A group the node holds, with the user's name for it. */
    data class Group(val group: GroupId, val name: String) : Body(0x8A, "GROUP")

    data class GroupGone(val group: GroupId) : Body(0x8B, "GROUP_GONE")

    /**
     * One message written to a group or received from one. [from] is the routing id its writer
     * claimed, 0 for one this node wrote: a claim, not a proof.
     */
    data class GroupMessage(
        override val id: Long,
        val group: GroupId,
        val from: Long,
        val time: Long,
        override val flags: Int,
        override val state: Int,
        override val reason: Int,
        override val wait: Int,
        val text: String,
    ) : Body(0x8C, "GROUP_MESSAGE"), Item {
        override fun with(s: State) = copy(state = s.state, reason = s.reason, wait = s.wait)
    }

    /**
     * An invite to a group, sent to [contact] or received from it, under the name the inviter calls
     * it. It goes as a unicast message does, and has a message's states.
     */
    data class Invite(
        override val id: Long,
        val contact: Address,
        val group: GroupId,
        val time: Long,
        override val flags: Int,
        override val state: Int,
        override val reason: Int,
        override val wait: Int,
        val name: String,
    ) : Body(0x8D, "INVITE"), Item {
        override fun with(s: State) = copy(state = s.state, reason = s.reason, wait = s.wait)
    }

    /**
     * The position the node holds from [contact]: the centre of its cell at [precision], in 10⁻⁷
     * degree; [altitude] [Companion.NO_ALTITUDE] and [accuracy] 0 where it gave none; [age] seconds
     * as of when sent. [precision] 0: the node holds none from it.
     */
    data class Position(
        val contact: Address,
        val precision: Int,
        val lat: Int,
        val lon: Int,
        val altitude: Int,
        val accuracy: Int,
        val age: Long,
    ) : Body(0x8E, "POSITION")

    /** [Position] from a routing id in a group: [from] is what a member claimed. */
    data class GroupPosition(
        val group: GroupId,
        val from: Long,
        val precision: Int,
        val lat: Int,
        val lon: Int,
        val altitude: Int,
        val accuracy: Int,
        val age: Long,
    ) : Body(0x8F, "GROUP_POSITION")

    /**
     * How the node shares its position with [contact]: [precision] 1 to 24, or 0 when off; [fields]
     * bit 0 altitude, bit 1 accuracy; [interval] in seconds; [minutes] left, 0 until turned off.
     */
    data class Sharing(val contact: Address, val precision: Int, val fields: Int, val interval: Int, val minutes: Int) :
        Body(0x90, "SHARING")

    /** [Sharing] with a group. */
    data class GroupSharing(val group: GroupId, val precision: Int, val fields: Int, val interval: Int, val minutes: Int) :
        Body(0x91, "GROUP_SHARING")

    /**
     * A card the node holds from [address]: [name] is what the card carried, its sender's claim and
     * not a name the user gave, and may be empty; [heard] how many seconds ago the node accepted it,
     * as of when sent.
     */
    data class Card(val address: Address, val heard: Long, val name: String) : Body(0x92, "CARD")

    data class CardGone(val address: Address) : Body(0x93, "CARD_GONE")
}

internal object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(DIGITS[v shr 4])
            append(DIGITS[v and 0x0F])
        }
    }

    fun decode(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(text[2 * i], 16)
            val lo = Character.digit(text[2 * i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = (hi shl 4 or lo).toByte()
        }
        return out
    }
}
