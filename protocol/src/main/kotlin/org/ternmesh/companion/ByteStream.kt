// Frames on a byte stream (USB serial, TCP): a magic, a length, the frame and a CRC, with the
// node's console text between them. Bluetooth needs none of this: each write and notification is
// one frame already.
package org.ternmesh.companion

object ByteStream {
    private const val MAGIC_0 = 0xF5
    private const val MAGIC_1 = 0x54

    /** CRC-16/IBM-3740: over "123456789" it is 0x29B1. */
    fun crc16(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in from until to) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    /** A frame as it goes on a byte stream: magic, length, the frame, and a CRC of the last two. */
    fun wrap(frame: ByteArray): ByteArray {
        val out = ByteArray(frame.size + 6)
        out[0] = MAGIC_0.toByte()
        out[1] = MAGIC_1.toByte()
        out[2] = (frame.size shr 8).toByte()
        out[3] = frame.size.toByte()
        frame.copyInto(out, 4)
        val crc = crc16(out, 2, 4 + frame.size)
        out[4 + frame.size] = (crc shr 8).toByte()
        out[5 + frame.size] = crc.toByte()
        return out
    }
}

/** What a byte stream holds: a frame, or a run of bytes that is not one. */
sealed interface StreamItem {
    class Frame(val bytes: ByteArray) : StreamItem {
        override fun equals(other: Any?) = other is Frame && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
        override fun toString() = "Frame(${Hex.encode(bytes)})"
    }

    class Text(val bytes: ByteArray) : StreamItem {
        override fun equals(other: Any?) = other is Text && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
        override fun toString() = "Text(${Hex.encode(bytes)})"
    }
}

/**
 * Finds frames in a byte stream, and the text between them: a node's console shares the port.
 * [push] returns what the bytes so far complete, in order; what may yet be the start of a frame is
 * held ([pending]) until more arrives, or until [stale] says nothing more is coming.
 */
class StreamReader {
    private var held = ByteArray(0)

    val pending: ByteArray get() = held.copyOf()

    fun push(data: ByteArray): List<StreamItem> {
        held += data
        return take()
    }

    /** After [Companion.GAP_MS] with nothing more: what is held is not the start of a frame after all. */
    fun stale(): List<StreamItem> {
        if (held.isEmpty()) return emptyList()
        val first = held[0]
        held = held.copyOfRange(1, held.size)
        val rest = take().toMutableList()
        val head = rest.firstOrNull()
        if (head is StreamItem.Text) {
            rest[0] = StreamItem.Text(byteArrayOf(first) + head.bytes)
            return rest
        }
        return listOf(StreamItem.Text(byteArrayOf(first))) + rest
    }

    private fun take(): List<StreamItem> {
        val items = mutableListOf<StreamItem>()
        val text = java.io.ByteArrayOutputStream()
        fun flush() {
            if (text.size() > 0) {
                items += StreamItem.Text(text.toByteArray())
                text.reset()
            }
        }
        val b = held
        fun at(i: Int) = b[i].toInt() and 0xFF
        var i = 0
        while (i < b.size) {
            if (at(i) != 0xF5) {
                text.write(at(i++))
                continue
            }
            // What follows may be a frame: wait for as much as it takes to tell.
            if (i + 1 >= b.size) break
            if (at(i + 1) != 0x54) {
                text.write(at(i++))
                continue
            }
            if (i + 4 > b.size) break
            val length = at(i + 2) shl 8 or at(i + 3)
            if (length < 2 || length > Companion.MAX_FRAME) {
                text.write(at(i++))
                continue
            }
            if (i + 6 + length > b.size) break
            val crc = at(i + 4 + length) shl 8 or at(i + 5 + length)
            if (ByteStream.crc16(b, i + 2, i + 4 + length) != crc) {
                text.write(at(i++))
                continue
            }
            flush()
            items += StreamItem.Frame(b.copyOfRange(i + 4, i + 4 + length))
            i += 6 + length
        }
        flush()
        held = b.copyOfRange(i, b.size)
        return items
    }
}
