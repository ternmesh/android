// The companion protocol's frames: specification draft 0, draft/companion.md in ternmesh/spec.
//
// Nothing here touches Bluetooth or a screen. It builds frames and reads them, and the tests hold
// it to the specification's vectors.
//
// Numbers are Kotlin's signed types, wide enough for the field: Int for u8, i8 and u16, Long for
// u32. Building a frame checks each fits.
package org.ternmesh.companion

/** The protocol's numbers, as the specification's Parameters give them. */
object Companion {
    const val VERSION = 0
    const val MAX_FRAME = 180
    const val TEXT_MAX = 128
    const val NAME_MAX = 31
    const val REGION_MAX = 15
    const val FIRMWARE_MAX = 31

    /** How long a client waits for an answer. */
    const val ANSWER_WAIT_MS = 5_000L

    /** The longest a client goes without a request. */
    const val IDLE_MS = 20_000L

    /** How long a byte stream may stall mid-frame before what is held is text. */
    const val GAP_MS = 500L

    /** The GATT service a node offers, and its two characteristics. */
    const val SERVICE = "7a280001-eb17-4c1c-889b-1741dd50ff40"
    const val TO_NODE = "7a280002-eb17-4c1c-889b-1741dd50ff40"
    const val FROM_NODE = "7a280003-eb17-4c1c-889b-1741dd50ff40"

    /** The least ATT MTU a client asks for: a 180-byte frame and the 3 bytes ATT adds. */
    const val MIN_MTU = 183

    fun isRequest(type: Int) = type in 0x01..0x3F
    fun isAnswer(type: Int) = type in 0x40..0x7F
    fun isNews(type: Int) = type in 0x80..0xBF
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

/** What a frame says: every frame of version 0. */
sealed class Body(val type: Int, val typeName: String) {
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

    // Answers, sent by the node with the request's seq.
    data object Ok : Body(0x40, "OK")
    data class Error(val code: Int) : Body(0x41, "ERROR")
    data class Info(val version: Int, val firmware: String) : Body(0x42, "INFO")
    data object Synced : Body(0x43, "SYNCED")
    data class Queued(val id: Long) : Body(0x44, "QUEUED")

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
        val id: Long,
        val contact: Address,
        val time: Long,
        val flags: Int,
        val state: Int,
        val reason: Int,
        val wait: Int,
        val text: String,
    ) : Body(0x83, "MESSAGE")

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
