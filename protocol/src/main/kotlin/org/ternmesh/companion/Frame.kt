// The companion protocol's frames, version 3: draft/companion.md in ternmesh/spec.
//
// Nothing here touches Bluetooth or a screen. It builds frames and reads them, and the tests hold
// it to the specification's vectors.
//
// Numbers are Kotlin's signed types, wide enough for the field: Int for u8, i8 and u16, Long for
// u32. Building a frame checks each fits.
package org.ternmesh.companion

/** The protocol's numbers, as the specification's Parameters give them. */
object Companion {
    /** The version this client speaks. Version 2 is this without `SYNCED`'s `news`, version 1 is version 2 without groups, and version 0 is version 1 without `END_SESSION` and `ASKED`. */
    const val VERSION = 3
    const val MAX_FRAME = 180
    const val TEXT_MAX = 128
    const val NAME_MAX = 31
    const val REGION_MAX = 15
    const val FIRMWARE_MAX = 31

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
        else -> 0
    }
}

/** A node's address: an Ed25519 public key. */
class Address(bytes: ByteArray) {
    private val bytes = bytes.copyOf()

    init {
        require(bytes.size == LENGTH) { "an address is $LENGTH bytes, not ${bytes.size}" }
    }

    fun toByteArray(): ByteArray = bytes.copyOf()

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
}

/** A message's state. */
object MessageState {
    const val WAITING = 0
    const val SENT = 1
    const val DELIVERED = 2
    const val NOT_DELIVERED = 3
    const val RECEIVED = 4
}

/** What a frame says: every frame of version 3. [since] is the least version that defines it. */
sealed class Body(val type: Int, val typeName: String) {
    /** The least version that defines this frame: a client sends no request the node's version does not define, and reads no frame the version both ends speak does not. */
    val since: Int get() = Companion.since(type)

    // Requests, sent by the client.
    data class Hello(val version: Int) : Body(0x01, "HELLO")
    data class Sync(val after: Long) : Body(0x02, "SYNC")
    data object Ping : Body(0x03, "PING")
    data class SetTime(val time: Long) : Body(0x04, "SET_TIME")
    data class Set(val setting: Setting) : Body(0x05, "SET")
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

    // Answers, sent by the node with the request's seq.
    data object Ok : Body(0x40, "OK")
    data class Error(val code: Int) : Body(0x41, "ERROR")
    data class Info(val version: Int, val firmware: String) : Body(0x42, "INFO")
    /** The sync is done. [news] is the node's count as it answers, the `seq` of its next news frame; null from a node of version 2 or earlier, whose `SYNCED` has no fields. */
    data class Synced(val news: Int? = null) : Body(0x43, "SYNCED")
    data class Queued(val id: Long) : Body(0x44, "QUEUED")
    data class Made(val group: GroupId) : Body(0x45, "MADE")

    // News, sent by the node with its count as seq.

    /** The node itself. [time] is 0 if it does not know it. */
    data class Self(
        val address: Address,
        val role: Int,
        val region: String,
        val power: Int,
        val time: Long,
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
