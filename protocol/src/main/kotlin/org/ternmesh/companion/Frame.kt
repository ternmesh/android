// The companion protocol's frames, version 20: draft/companion.md in ternmesh/spec.
//
// Nothing here touches Bluetooth or a screen. It builds frames and reads them, and the tests hold
// it to the specification's vectors.
//
// Numbers are Kotlin's signed types, wide enough for the field: Int for u8, i8, u16, i16 and i32,
// Long for u32. Building a frame checks each fits.
package org.ternmesh.companion

/** The protocol's numbers, as the specification's Parameters give them. */
object Companion {
    /** The version this client speaks. Version 19 is this without asking for cards (`LOOK` and `ERROR` 16), version 18 is version 19 without names in first contact (`ASKED`'s `name` and `CONTACT`'s `says`), version 17 is version 18 without how messages went and traces (`TRACE`, `ROUTE` and `TRACED`), version 16 is version 17 without managing (`MANAGE`, `OWNER`, `RESTART`, `SET` 10, `MANAGED`, `OWNER_CHANGED`, `CONTACT`'s `owner` and `SELF`'s `locked`), version 15 is version 16 without forgetting (`FORGET`, `FORGET_GROUP`, `FORGOTTEN` and `GROUP_FORGOTTEN`), version 14 is version 15 without sleeping (`SET` 8 and 9, `SLEEP`, and `CONTACT`'s `wake`), version 13 is this with routing ids made from the address itself, not from the key blinded from it (RoutingIds), version 12 is version 13 without signed images (`UPDATE_BEGIN`'s `signature`) and `WIPE`, version 11 is version 12 without updates by the bootloader (`UPDATE_BOOTLOADER` and `INFO`'s `update`), version 10 is version 11 without `SELF`'s `pairing`, version 9 is version 10 without the most (`SET` 3's -128 and `SELF`'s `most`), version 8 is version 9 without turning the node off, version 7 is version 8 without the node's own fix, version 6 is version 7 without join codes, version 5 is version 6 without cards, version 4 is version 5 without positions, version 3 is version 4 without updates, version 2 is version 3 without `SYNCED`'s `news`, version 1 is version 2 without groups, and version 0 is version 1 without `END_SESSION` and `ASKED`. */
    const val VERSION = 20
    /** `SET` 3's power that asks for the most the node may transmit at, which it works out (version 10). */
    const val POWER_MOST = -128
    const val MAX_FRAME = 180
    const val TEXT_MAX = 128
    const val NAME_MAX = 31
    const val REGION_MAX = 15
    const val FIRMWARE_MAX = 31
    const val BOARD_MAX = 31
    const val RELEASE_MAX = 31

    /** A join code's link, the longest: a group whose name is 31 bytes. */
    const val LINK_MAX = 102

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

    /** An image's signature's length, Ed25519's (version 13). */
    const val SIGNATURE = 64

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

    /** The fewest `FORGOTTEN` and `GROUP_FORGOTTEN` records a node keeps (version 16). */
    const val FORGOTTEN_KEPT = 16

    /** A `MANAGE`'s `request`, and a `MANAGED`'s `answer`, at most (version 17). */
    const val REQUEST_MAX = 128
    const val ANSWER_MAX = 128

    /** The owners a node holds, at most (version 17). */
    const val OWNERS = 4

    /** How long a client waits for an answer over the mesh, once its request is delivered (version 17). */
    const val MESH_ANSWER_WAIT_MS = 120_000L

    /** How long a `SET` over the mesh is on trial after it is answered, before the node takes it back unless an owner asks it something more. */
    const val TRIAL_MS = 900_000L

    /** A `TRACED`'s `entries`, at most: [TRACE_ENTRY] bytes for each, sixteen each way (version 18). */
    const val TRACE_ENTRIES_MAX = 160

    /** One entry of a trace: a routing id, 4 bytes, and the SNR its node heard the frame at, an `i8` in quarters of a dB. */
    const val TRACE_ENTRY = 5

    /** The least time between two asks for cards: a `LOOK` sooner after the last is answered `ERROR` 16 (version 20; draft/cards.md, Asking). */
    const val ASK_GAP_MS = 600_000L

    /** The least time between two `ASKED`s for one address, or two of `NEIGHBOUR`, `AIRTIME` or `POWER`, or two `FIX`es for a fix that moved. */
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
        0x26, 0x27, 0x47 -> 7
        0x94 -> 8
        0x06 -> 9
        0x36 -> 12
        0x37 -> 13
        0x95 -> 15
        0x12, 0x28, 0x96, 0x97 -> 16
        0x13, 0x14, 0x38, 0x98, 0x99 -> 17
        0x15, 0x9A, 0x9B -> 18
        0x16 -> 20
        else -> 0
    }

    /** The least version that defines `SET`'s setting [setting]. */
    fun settingSince(setting: Int): Int = when (setting) {
        5, 6 -> 6
        7 -> 8
        8, 9 -> 15
        10 -> 17
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

/**
 * A group's id, which the node works out from the group's secret. No frame carries the secret but a
 * join code's, in `LINK` and `JOIN_LINK`, which a client passes on and does not keep.
 */
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

    /**
     * To `UPDATE_END`: not an image this node runs, or not the one its digest names, and the update
     * is discarded. To `UPDATE_BEGIN` (version 13): the image is not signed by a release key the
     * node holds, and any update under way is left as it was.
     */
    const val NOT_AN_IMAGE = 11

    /** Not a contact: `SHARE` or `OWNER` names an address the node does not hold as one. */
    const val NOT_A_CONTACT = 12

    /** Not over the mesh: a request a managed node does not act on when its owner asks over the mesh (version 17). */
    const val NOT_OVER_THE_MESH = 13

    /** Locked: the node's Bluetooth is locked, and it acts on nothing over it but `HELLO` and `PING` (version 17). */
    const val LOCKED = 14

    /**
     * Not an owner: a managed node answers a request over the mesh from one that is not among its
     * owners (a draft of managing after version 17). It may still say nothing, as it limits how
     * often it answers so; and a node of version 17 always says nothing.
     */
    const val NOT_AN_OWNER = 15

    /** Too soon: `LOOK` within `ASK_GAP` of the node's last ask for cards (version 20). Ask again later. */
    const val TOO_SOON = 16
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

    /**
     * 0 to 999999, or [DEFAULT] for the node's default: a new one on its screen for each pairing on
     * a node with a screen, 123456 on one without.
     */
    data class Passkey(val passkey: Long) : Setting {
        override val number get() = 4

        companion object {
            const val DEFAULT = 0xFFFF_FFFFL

            /** The passkey a node without a screen pairs with until one is set. */
            const val SCREENLESS_DEFAULT = 123_456L
        }
    }

    /** 1 to send cards, 0 to send none. Only ever what the user asked for. */
    data class Cards(val cards: Int) : Setting { override val number get() = 5 }

    /** The name the node's cards carry, the one name it puts on the air in clear; empty for none. Only ever what the user asked for. */
    data class CardName(val name: String) : Setting { override val number get() = 6 }

    /** 1 to run the node's satellite receiver, 0 to turn it off. Only ever what the user asked for. */
    data class Receiver(val on: Int) : Setting { override val number get() = 7 }

    /**
     * How long the node, as a leaf, lets pass between its polls when it sleeps, in seconds: 0 not
     * to sleep, or [Sleeping.WAKE_MIN] to [Sleeping.WAKE_MAX] (version 15). Only ever what the user
     * asked for.
     */
    data class Wake(val seconds: Int) : Setting { override val number get() = 8 }

    /** How long a sleeping node listens after each poll, in seconds, [Sleeping.LISTEN_MIN] to 255 (version 15). */
    data class Listen(val seconds: Int) : Setting { override val number get() = 9 }

    /**
     * 1 to lock the node's Bluetooth, 0 to unlock it (version 17): locked, it acts on nothing a
     * client sends over Bluetooth but `HELLO` and `PING`, and only USB or one of its owners over the
     * mesh can unlock it. Only ever what the user asked for, after being told so.
     */
    data class Lock(val locked: Int) : Setting { override val number get() = 10 }
}

/** Sleeping leaves' numbers (draft/sleeping.md), and `SLEEP`'s `state` (version 15). */
object Sleeping {
    const val WAKE_MIN = 300
    const val WAKE_MAX = 3600
    const val LISTEN_MIN = 2
    const val LISTEN_MAX = 255

    /** It listens all the time: not set to sleep, or a relay. */
    const val AWAKE = 0

    /** It sleeps, kept by `keeper`. */
    const val ASLEEP = 1

    /** It is to sleep, but has no relay it can take as keeper yet. */
    const val WAITING = 2

    /** It is to sleep, but the relays it could take did not answer its polls; it tries `keeper` again in `retry` seconds. */
    const val RESTING = 3
}

/** A `FIX`'s `receiver`: what the node's satellite receiver is doing. */
object ReceiverState {
    /** The node has no receiver. */
    const val NONE = 0
    const val OFF = 1
    const val SEARCHING = 2
    const val FIXED = 3
}

/** A `FIX`'s `source`: where the node's fix came from. */
object FixSource {
    /** The node has no fix. */
    const val NONE = 0
    const val RECEIVER = 1
    const val CLIENT = 2
}

/** `SELF`'s `pairing`: how the node is paired. */
object Pairing {
    /** It shows a new passkey on its screen for each pairing. */
    const val SCREEN = 0

    /** It has no screen and pairs with the default passkey, 123456, until the user sets one. */
    const val DEFAULT = 1

    /** It pairs with a passkey set with `SET` 4. */
    const val SET = 2
}

/** `INFO`'s `update`: how a client gives the node new firmware (version 12). */
object UpdateBy {
    /** A client cannot: the node is flashed by cable. */
    const val NONE = 0

    /** Over this protocol: `UPDATE_BEGIN`, `UPDATE_DATA` and `UPDATE_END`. */
    const val LINK = 1

    /** By the node's bootloader, which `UPDATE_BOOTLOADER` restarts it into. */
    const val BOOTLOADER = 2
}

/** A message's state. */
object MessageState {
    const val WAITING = 0
    const val SENT = 1
    const val DELIVERED = 2
    const val NOT_DELIVERED = 3
    const val RECEIVED = 4
}

/** `TRACED`'s `result`: how a trace ended (version 18). */
object TraceResult {
    /** Answered by the node traced to. */
    const val ANSWERED = 0

    /** Answered by the keeper of the node traced to, a leaf that sleeps: it got as far as where frames for it wait. */
    const val KEEPER = 1

    /** Answered by a relay with no route to the node traced to: it got as far as that relay. */
    const val NO_ROUTE = 2

    /** Not answered in time. */
    const val NOT_ANSWERED = 3

    /** Not begun: the node found no route. */
    const val NOT_BEGUN = 4
}

/** One entry of a trace: a node that passed it on or answered it, by [routingId], and how well it heard the node before, in quarters of a dB. */
data class TraceHop(val routingId: Long, val snrQuarterDb: Int)

/** What a frame says: every frame of version 20. [since] is the least version that defines it. */
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

    /**
     * Turn the node off, which it answers with `OK` before it does: every connection drops, and only
     * its button, reset or power turns it on again. Only ever what the user asked for.
     */
    data object Off : Body(0x06, "OFF")

    data class Send(val ref: Long, val to: Address, val text: String) : Body(0x10, "SEND")
    data class Read(val through: Long) : Body(0x11, "READ")

    /**
     * Forget the conversation with [contact], every message and invite with it whose `id` is
     * [through] or less, on the node and every client of it, for good (version 16). Only ever what
     * the user asked for, with [through] the greatest `id` they were shown in it.
     */
    data class Forget(val contact: Address, val through: Long) : Body(0x12, "FORGET")

    /**
     * Send [request], one companion request frame, to the node at [to] over the mesh, to manage it
     * (version 17). [ref] is as for `SEND`. Answered `QUEUED`; `STATE` news with that `id` says what
     * became of it on the air, and the node's answer comes back as `MANAGED`.
     */
    class Manage(val ref: Long, val to: Address, request: ByteArray) : Body(0x13, "MANAGE") {
        private val bytes = request.copyOf()

        val request: ByteArray get() = bytes.copyOf()

        override fun equals(other: Any?) = other is Manage && ref == other.ref && to == other.to && bytes.contentEquals(other.bytes)
        override fun hashCode() = (31 * ref.hashCode() + to.hashCode()) * 31 + bytes.contentHashCode()
        override fun toString() = "Manage(ref=$ref, to=$to, request=${Hex.encode(bytes)})"
    }

    /**
     * Make the contact [address] one of the node's owners, with [owner] 1, or not one, with 0
     * (version 17). An owner can change the node's settings and who it takes, from anywhere on the
     * mesh: only ever what the user asked for, after being told so.
     */
    data class Owner(val address: Address, val owner: Int) : Body(0x14, "OWNER")

    /**
     * Find the way frames go to the node whose routing id is [to], and back (version 18). [ref] is as
     * for `SEND`. Answered `QUEUED`; `STATE` news with that `id` says what became of it on the air,
     * and `TRACED` how it ended.
     */
    data class Trace(val ref: Long, val to: Long) : Body(0x15, "TRACE")

    /**
     * Ask the nodes in earshot for their cards (version 20; draft/cards.md, Asking). Answered `OK`
     * once the ask waits for the air, or `ERROR` 16 if the node asked less than `ASK_GAP` before.
     * The cards come as `CARD` news, as any card does, within half a minute. Only ever what the
     * user asked for.
     */
    data object Look : Body(0x16, "LOOK")
    data class SaveContact(val address: Address, val name: String) : Body(0x18, "SAVE_CONTACT")
    data class RemoveContact(val address: Address) : Body(0x19, "REMOVE_CONTACT")
    data class EndSession(val address: Address) : Body(0x1A, "END_SESSION")
    data class MakeGroup(val name: String) : Body(0x20, "MAKE_GROUP")
    data class LeaveGroup(val group: GroupId) : Body(0x21, "LEAVE_GROUP")
    data class NameGroup(val group: GroupId, val name: String) : Body(0x22, "NAME_GROUP")
    data class SendGroup(val ref: Long, val group: GroupId, val text: String) : Body(0x23, "SEND_GROUP")
    data class SendInvite(val group: GroupId, val to: Address) : Body(0x24, "SEND_INVITE")
    data class Join(val id: Long) : Body(0x25, "JOIN")

    /** The join code of [group], which the node answers with `LINK`. Only ever what the user asked to see or share. */
    data class GroupLink(val group: GroupId) : Body(0x26, "GROUP_LINK")

    /**
     * Join the group a join code is for, which the node answers with `MADE`. Only ever a code the
     * user asked to join from. [toString] leaves the link out: it is the group's secret.
     */
    class JoinLink(val link: String) : Body(0x27, "JOIN_LINK") {
        override fun equals(other: Any?) = other is JoinLink && link == other.link
        override fun hashCode() = link.hashCode()
        override fun toString() = "JoinLink(…)"
    }

    /** `FORGET` for [group]'s conversation: every group message to it whose `id` is [through] or less (version 16). */
    data class ForgetGroup(val group: GroupId, val through: Long) : Body(0x28, "FORGET_GROUP")

    /**
     * An image of [size] bytes, whose SHA-256 is [digest], follows, signed with [signature]
     * (version 13): 64 bytes, or empty for none. Null below version 13, whose `UPDATE_BEGIN` has no
     * such field.
     */
    class UpdateBegin(val size: Long, digest: ByteArray, signature: ByteArray? = null) : Body(0x30, "UPDATE_BEGIN") {
        private val bytes = digest.copyOf()
        private val sig = signature?.copyOf()

        init {
            require(digest.size == Companion.DIGEST) { "a digest is ${Companion.DIGEST} bytes, not ${digest.size}" }
            require(signature == null || signature.size <= Companion.SIGNATURE) { "a signature is at most ${Companion.SIGNATURE} bytes" }
        }

        val digest: ByteArray get() = bytes.copyOf()
        val signature: ByteArray? get() = sig?.copyOf()

        override fun equals(other: Any?) = other is UpdateBegin && size == other.size && bytes.contentEquals(other.bytes) &&
            (sig?.let { s -> other.sig?.contentEquals(s) ?: false } ?: (other.sig == null))
        override fun hashCode() = 31 * size.hashCode() + bytes.contentHashCode()
        override fun toString() = "UpdateBegin(size=$size, digest=${Hex.encode(bytes)}, signature=${sig?.let(Hex::encode)})"
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
     * Restart into the bootloader's update, which the node answers with `OK` before it does: every
     * connection drops, and the bootloader advertises its own service and speaks its own protocol
     * (version 12). Only for a node whose `INFO` says [UpdateBy.BOOTLOADER].
     */
    data object UpdateBootloader : Body(0x36, "UPDATE_BOOTLOADER")

    /**
     * Erase the node, which it answers with `OK` before it does (version 13): its identity, so it
     * starts again with a new address, its sessions, contacts, groups, messages and settings, and
     * every Bluetooth bond. Only ever what the user asked for, after being told so.
     */
    data object Wipe : Body(0x37, "WIPE")

    /** Restart the node, which it answers with `OK` before it does: every connection drops (version 17). */
    data object Restart : Body(0x38, "RESTART")

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
     * end; [board] is empty for a node a client cannot update (below version 12, for one it cannot
     * update over the protocol), [release] for firmware without a version. [update] is how a client
     * updates it, an [UpdateBy]; null below version 12.
     */
    data class Info(
        val version: Int,
        val firmware: String,
        val board: String? = null,
        val release: String? = null,
        val update: Int? = null,
    ) : Body(0x42, "INFO")
    /** The sync is done. [news] is the node's count as it answers, the `seq` of its next news frame; null from a node of version 2 or earlier, whose `SYNCED` has no fields. */
    data class Synced(val news: Int? = null) : Body(0x43, "SYNCED")
    data class Queued(val id: Long) : Body(0x44, "QUEUED")
    data class Made(val group: GroupId) : Body(0x45, "MADE")

    /** The offset to send an update's image from. */
    data class Updating(val offset: Long) : Body(0x46, "UPDATING")

    /** A group's join code, which `GROUP_LINK` asked for. [toString] leaves it out: it is the group's secret. */
    class Link(val link: String) : Body(0x47, "LINK") {
        override fun equals(other: Any?) = other is Link && link == other.link
        override fun hashCode() = link.hashCode()
        override fun toString() = "Link(…)"
    }

    // News, sent by the node with its count as seq.

    /**
     * The node itself. [time] is 0 if it does not know it. [cards] is 1 if it sends cards, and
     * [cardName] the name they carry; both are null below version 6, from either end. [most] is 1
     * if [power] is the most the node may transmit at, as it works it out, and 0 if it is a power it
     * was given; null below version 10. [pairing] is how the node pairs: [Pairing.SCREEN],
     * [Pairing.DEFAULT] or [Pairing.SET]; null below version 11. [locked] is 1 while the node's
     * Bluetooth is locked; null below version 17.
     */
    data class Self(
        val address: Address,
        val role: Int,
        val region: String,
        val power: Int,
        val time: Long,
        val cards: Int? = null,
        val cardName: String? = null,
        val most: Int? = null,
        val pairing: Int? = null,
        val locked: Int? = null,
    ) : Body(0x80, "SELF")

    /**
     * An address the user saved, with a name. [session] is 1 if the node shares one with it. [wake]
     * is how long the contact said it sleeps between wakes, in seconds, 0 if it does not or said
     * nothing; null below version 15. [owner] is 1 if the contact is one of the node's owners, who
     * may manage it over the mesh; null below version 17. [says] is the name the contact gave itself
     * in the last first contact the node made with it, empty for none; null below version 19. It is
     * the contact's claim, not the user's name for it: shown as such where [name] is empty, offered
     * when the user names it, never put in [name]'s place unless the user says so
     * (draft/first-contact.md, Names).
     */
    data class Contact(
        val address: Address,
        val session: Int,
        val name: String,
        val wake: Int? = null,
        val owner: Int? = null,
        val says: String? = null,
    ) : Body(0x81, "CONTACT")

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
     * contact, 2 if the node has no room for another session. [name] is the name the asking node
     * gave itself in its `message_3`, empty for none; null below version 19. Its claim: shown with
     * the address's short code, and offered as the contact's name (draft/first-contact.md, Names).
     */
    data class Asked(val address: Address, val why: Int, val name: String? = null) : Body(0x89, "ASKED")

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

    /**
     * The node's own fix, and its satellite receiver: [receiver] a [ReceiverState], [source] a
     * [FixSource]. With a source, [lat] and [lon] are in 10⁻⁷ degree, [altitude] metres above the
     * ellipsoid or [Companion.NO_ALTITUDE], [accuracy] metres or 0 for none, [age] seconds as of when
     * sent, and [satellites] how many made a receiver's fix; with none, all are 0.
     */
    data class Fix(
        val receiver: Int,
        val source: Int,
        val lat: Int,
        val lon: Int,
        val altitude: Int,
        val accuracy: Int,
        val age: Long,
        val satellites: Int,
    ) : Body(0x94, "FIX") {
        /** Whether it is a fix at all. */
        val has: Boolean get() = source != FixSource.NONE
    }

    /**
     * Whether the node sleeps (version 15): [wake] and [listen] in seconds as `SET` 8 and 9 set them,
     * [wake] 0 for not to sleep; [state] a [Sleeping] state; [keeper] the keeper's routing id when
     * asleep, the relay it will try again when resting, else 0; [retry] seconds until it does.
     */
    data class Sleep(val wake: Int, val listen: Int, val state: Int, val keeper: Long, val retry: Int) :
        Body(0x95, "SLEEP")

    /**
     * How much of the conversation with [contact] the user has forgotten (version 16): every
     * message and invite with it whose `id` is [through] or less. A client holds none of them once
     * it has this.
     */
    data class Forgotten(val contact: Address, val through: Long) : Body(0x96, "FORGOTTEN")

    /** How much of [group]'s conversation the user has forgotten: every group message to it whose `id` is [through] or less. */
    data class GroupForgotten(val group: GroupId, val through: Long) : Body(0x97, "GROUP_FORGOTTEN")

    /**
     * What the node at [from] answered a `MANAGE` with (version 17): companion frames, each after a
     * `u8` length, which [Managing.unpack] takes apart. Not a record: a sync does not send it again.
     */
    class Managed(val from: Address, answer: ByteArray) : Body(0x98, "MANAGED") {
        private val bytes = answer.copyOf()

        val answer: ByteArray get() = bytes.copyOf()

        override fun equals(other: Any?) = other is Managed && from == other.from && bytes.contentEquals(other.bytes)
        override fun hashCode() = 31 * from.hashCode() + bytes.contentHashCode()
        override fun toString() = "Managed(from=$from, answer=${Hex.encode(bytes)})"
    }

    /**
     * The node at [from] said its owners changed (version 17): [contact] is one now if [owner] is 1,
     * and no longer one if 0; [by] is 0 if that was done at the node, over USB or Bluetooth, and 1
     * if by one of its owners over the mesh. Not a record: a sync does not send it again.
     */
    data class OwnerChanged(val from: Address, val contact: Address, val owner: Int, val by: Int) : Body(0x99, "OWNER_CHANGED")

    /**
     * How the message, group message or invite [id] went, as the node knows it (version 18): a
     * record, which replaces any before it for that [id].
     *
     * [hops] is how many hops the frame that brought it came, the last counted, 1 for straight from
     * its sender, 0 for unknown; for a group message received, the most it can have come, so it came
     * that far or one hop less. [snr] (quarters of a dB) and [rssi] (dBm) are how that frame was
     * heard, 0 when [hops] is. For one sent, [via] is the routing id of the neighbour it last went
     * to, the destination's own when it went straight there, 0 while it has gone nowhere and for
     * anything received or any group message; [goes] how many tries have gone on the air; and,
     * once it is delivered, [hops], [snr] and [rssi] describe the acknowledgement's way back, and
     * [took] how long from its first going on the air until the acknowledgement came, in
     * milliseconds. Until then those four are 0.
     */
    data class Route(
        val id: Long,
        val hops: Int,
        val snr: Int,
        val rssi: Int,
        val via: Long,
        val goes: Int,
        val took: Long,
    ) : Body(0x9A, "ROUTE")

    /**
     * How the trace [id] to [to] ended (version 18): [result], a [TraceResult]. For one answered
     * (0 to 2), [entries] are the answer's, [Companion.TRACE_ENTRY] bytes each, the first [out] the
     * way out, ending with the node that answered, and the rest the way back; [snr] is how well this
     * node heard the answer, in quarters of a dB, and [took] how long from the trace's first going
     * on the air until the answer came, in milliseconds. For 3 and 4 they are 0, and [entries] is
     * empty. Not a record: a sync does not send it again. A trace shows how frames went just then,
     * and any relay on the way can write what it likes in its entry.
     */
    class Traced(
        val id: Long,
        val to: Long,
        val result: Int,
        val out: Int,
        val took: Long,
        val snr: Int,
        entries: ByteArray,
    ) : Body(0x9B, "TRACED") {
        private val bytes = entries.copyOf()

        val entries: ByteArray get() = bytes.copyOf()

        /** Each whole entry, in the order they were added: [wayOut] and then [wayBack]. */
        val hops: List<TraceHop>
            get() = (0 until bytes.size / Companion.TRACE_ENTRY).map { i ->
                val at = i * Companion.TRACE_ENTRY
                val id = (0 until 4).fold(0L) { v, k -> v shl 8 or (bytes[at + k].toLong() and 0xFF) }
                TraceHop(id, bytes[at + 4].toInt())
            }

        /** The nodes that passed the trace on, and last the one that answered it. */
        val wayOut: List<TraceHop> get() = hops.take(out)

        /** The nodes that passed the answer back, before it reached this node. */
        val wayBack: List<TraceHop> get() = hops.drop(out)

        /** Whether something answered it: its destination, its keeper or a relay with no route. */
        val answered: Boolean get() = result <= TraceResult.NO_ROUTE

        override fun equals(other: Any?) = other is Traced && id == other.id && to == other.to && result == other.result &&
            out == other.out && took == other.took && snr == other.snr && bytes.contentEquals(other.bytes)
        override fun hashCode() = listOf(id, to, result, out, took, snr).hashCode() * 31 + bytes.contentHashCode()
        override fun toString() = "Traced(id=$id, to=$to, result=$result, out=$out, took=$took, snr=$snr, entries=${Hex.encode(bytes)})"
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
