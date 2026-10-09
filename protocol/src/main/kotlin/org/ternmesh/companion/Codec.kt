// Building and reading frames, field by field: big-endian numbers, signed ones in two's complement,
// 32-byte addresses and digests, and text and bytes as a length byte then the bytes.
package org.ternmesh.companion

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Why a frame could not be read. */
enum class Unreadable {
    /** Shorter than a type and a sequence number: not answered at all. */
    SHORT,

    /** A type, or a setting, this version does not define. */
    UNDEFINED,

    /** Shorter than its fields, a string or bytes too long, a string not UTF-8, or longer than a frame may be. */
    MALFORMED,
}

class DecodeException(val reason: Unreadable) : Exception(reason.name)

object Codec {
    /**
     * The frame's bytes, as one Bluetooth write or notification carries them. Throws
     * IllegalArgumentException for a number that does not fit its field, text longer than its field
     * allows, or a frame longer than [Companion.MAX_FRAME]: these are the caller's mistakes, not the
     * wire's.
     */
    fun encode(frame: Frame): ByteArray {
        val w = Writer()
        val b = frame.body
        w.u8(b.type)
        w.u8(frame.seq)
        when (b) {
            is Body.Hello -> w.u8(b.version)
            is Body.Sync -> w.u32(b.after)
            Body.Ping, Body.Ok -> {}
            is Body.Synced -> b.news?.let { w.u8(it) }
            is Body.SetTime -> w.u32(b.time)
            is Body.Set -> {
                w.u8(b.setting.number)
                when (val s = b.setting) {
                    is Setting.Region -> w.str(s.name, Companion.REGION_MAX)
                    is Setting.Role -> w.u8(s.role)
                    is Setting.Power -> w.i8(s.dbm)
                    is Setting.Passkey -> w.u32(s.passkey)
                }
            }
            is Body.Send -> {
                w.u32(b.ref)
                w.addr(b.to)
                w.str(b.text, Companion.TEXT_MAX)
            }
            is Body.Read -> w.u32(b.through)
            is Body.SaveContact -> {
                w.addr(b.address)
                w.str(b.name, Companion.NAME_MAX)
            }
            is Body.RemoveContact -> w.addr(b.address)
            is Body.EndSession -> w.addr(b.address)
            is Body.MakeGroup -> w.str(b.name, Companion.NAME_MAX)
            is Body.LeaveGroup -> w.gid(b.group)
            is Body.NameGroup -> {
                w.gid(b.group)
                w.str(b.name, Companion.NAME_MAX)
            }
            is Body.SendGroup -> {
                w.u32(b.ref)
                w.gid(b.group)
                w.str(b.text, Companion.TEXT_MAX)
            }
            is Body.SendInvite -> {
                w.gid(b.group)
                w.addr(b.to)
            }
            is Body.Join -> w.u32(b.id)
            is Body.UpdateBegin -> {
                w.u32(b.size)
                w.raw(b.digest)
            }
            is Body.UpdateData -> {
                w.u32(b.offset)
                w.bytes(b.data, Companion.UPDATE_CHUNK)
            }
            Body.UpdateEnd -> {}
            is Body.SetPosition -> {
                w.i32(b.lat)
                w.i32(b.lon)
                w.i16(b.altitude)
                w.u16(b.accuracy)
                w.u16(b.age)
            }
            is Body.Share -> {
                w.addr(b.contact)
                w.sharing(b.precision, b.fields, b.interval, b.minutes)
            }
            is Body.ShareGroup -> {
                w.gid(b.group)
                w.sharing(b.precision, b.fields, b.interval, b.minutes)
            }
            is Body.Error -> w.u8(b.code)
            is Body.Info -> {
                w.u8(b.version)
                w.str(b.firmware, Companion.FIRMWARE_MAX)
                // Both or neither: the two are one addition, of version 4.
                require((b.board == null) == (b.release == null)) { "INFO has board and release, or neither" }
                if (b.board != null && b.release != null) {
                    w.str(b.board, Companion.BOARD_MAX)
                    w.str(b.release, Companion.RELEASE_MAX)
                }
            }
            is Body.Queued -> w.u32(b.id)
            is Body.Made -> w.gid(b.group)
            is Body.Updating -> w.u32(b.offset)
            is Body.Self -> {
                w.addr(b.address)
                w.u8(b.role)
                w.str(b.region, Companion.REGION_MAX)
                w.i8(b.power)
                w.u32(b.time)
            }
            is Body.Contact -> {
                w.addr(b.address)
                w.u8(b.session)
                w.str(b.name, Companion.NAME_MAX)
            }
            is Body.ContactGone -> w.addr(b.address)
            is Body.Message -> {
                w.u32(b.id)
                w.addr(b.contact)
                w.u32(b.time)
                w.u8(b.flags)
                w.u8(b.state)
                w.u8(b.reason)
                w.u16(b.wait)
                w.str(b.text, Companion.TEXT_MAX)
            }
            is Body.State -> {
                w.u32(b.id)
                w.u8(b.state)
                w.u8(b.reason)
                w.u16(b.wait)
            }
            is Body.Neighbour -> {
                w.u32(b.routingId)
                w.u8(b.role)
                w.i8(b.snrQuarterDb)
                w.u16(b.heard)
            }
            is Body.NeighbourGone -> w.u32(b.routingId)
            is Body.Airtime -> {
                w.u32(b.period)
                w.u32(b.allowed)
                w.u32(b.used)
                w.u32(b.wait)
            }
            is Body.Power -> {
                w.u16(b.millivolts)
                w.u8(b.percent)
                w.u8(b.flags)
            }
            is Body.Asked -> {
                w.addr(b.address)
                w.u8(b.why)
            }
            is Body.Group -> {
                w.gid(b.group)
                w.str(b.name, Companion.NAME_MAX)
            }
            is Body.GroupGone -> w.gid(b.group)
            is Body.GroupMessage -> {
                w.u32(b.id)
                w.gid(b.group)
                w.u32(b.from)
                w.u32(b.time)
                w.u8(b.flags)
                w.u8(b.state)
                w.u8(b.reason)
                w.u16(b.wait)
                w.str(b.text, Companion.TEXT_MAX)
            }
            is Body.Invite -> {
                w.u32(b.id)
                w.addr(b.contact)
                w.gid(b.group)
                w.u32(b.time)
                w.u8(b.flags)
                w.u8(b.state)
                w.u8(b.reason)
                w.u16(b.wait)
                w.str(b.name, Companion.NAME_MAX)
            }
            is Body.Position -> {
                w.addr(b.contact)
                w.position(b.precision, b.lat, b.lon, b.altitude, b.accuracy, b.age)
            }
            is Body.GroupPosition -> {
                w.gid(b.group)
                w.u32(b.from)
                w.position(b.precision, b.lat, b.lon, b.altitude, b.accuracy, b.age)
            }
            is Body.Sharing -> {
                w.addr(b.contact)
                w.sharing(b.precision, b.fields, b.interval, b.minutes)
            }
            is Body.GroupSharing -> {
                w.gid(b.group)
                w.sharing(b.precision, b.fields, b.interval, b.minutes)
            }
        }
        val out = w.toByteArray()
        require(out.size <= Companion.MAX_FRAME) { "${b.typeName} is ${out.size} bytes, more than a frame holds" }
        return out
    }

    /**
     * Reads a frame by [version], the one both ends speak, or throws [DecodeException]. Bytes after
     * the fields that version defines are ignored, as the specification requires: that is how a
     * later version adds a field.
     */
    fun decode(bytes: ByteArray, version: Int = Companion.VERSION): Frame {
        if (bytes.size < 2) throw DecodeException(Unreadable.SHORT)
        if (bytes.size > Companion.MAX_FRAME) throw DecodeException(Unreadable.MALFORMED)
        // A type the version spoken does not define is undefined however its fields read.
        if (Companion.since(bytes[0].toInt() and 0xFF) > version) throw DecodeException(Unreadable.UNDEFINED)
        val r = Reader(bytes)
        val type = r.u8()
        val seq = r.u8()
        val body = when (type) {
            0x01 -> Body.Hello(r.u8())
            0x02 -> Body.Sync(r.u32())
            0x03 -> Body.Ping
            0x04 -> Body.SetTime(r.u32())
            0x05 -> Body.Set(
                when (r.u8()) {
                    1 -> Setting.Region(r.str(Companion.REGION_MAX))
                    2 -> Setting.Role(r.u8())
                    3 -> Setting.Power(r.i8())
                    4 -> Setting.Passkey(r.u32())
                    else -> throw DecodeException(Unreadable.UNDEFINED)
                },
            )
            0x10 -> Body.Send(r.u32(), r.addr(), r.str(Companion.TEXT_MAX))
            0x11 -> Body.Read(r.u32())
            0x18 -> Body.SaveContact(r.addr(), r.str(Companion.NAME_MAX))
            0x19 -> Body.RemoveContact(r.addr())
            0x1A -> Body.EndSession(r.addr())
            0x20 -> Body.MakeGroup(r.str(Companion.NAME_MAX))
            0x21 -> Body.LeaveGroup(r.gid())
            0x22 -> Body.NameGroup(r.gid(), r.str(Companion.NAME_MAX))
            0x23 -> Body.SendGroup(r.u32(), r.gid(), r.str(Companion.TEXT_MAX))
            0x24 -> Body.SendInvite(r.gid(), r.addr())
            0x25 -> Body.Join(r.u32())
            0x30 -> Body.UpdateBegin(r.u32(), r.raw(Companion.DIGEST))
            0x31 -> Body.UpdateData(r.u32(), r.bytes(Companion.UPDATE_CHUNK))
            0x32 -> Body.UpdateEnd
            0x33 -> Body.SetPosition(r.i32(), r.i32(), r.i16(), r.u16(), r.u16())
            0x34 -> Body.Share(r.addr(), r.u8(), r.u8(), r.u16(), r.u16())
            0x35 -> Body.ShareGroup(r.gid(), r.u8(), r.u8(), r.u16(), r.u16())
            0x40 -> Body.Ok
            0x41 -> Body.Error(r.u8())
            0x42 -> {
                val v = r.u8()
                val firmware = r.str(Companion.FIRMWARE_MAX)
                // Before the node has said its version the client's is all a reader has; INFO says
                // the node's, and the two fields are there only if both speak 4.
                if (minOf(v, version) >= 4) {
                    Body.Info(v, firmware, r.str(Companion.BOARD_MAX), r.str(Companion.RELEASE_MAX))
                } else {
                    Body.Info(v, firmware)
                }
            }
            0x43 -> Body.Synced(if (version >= 3) r.u8() else null)
            0x44 -> Body.Queued(r.u32())
            0x45 -> Body.Made(r.gid())
            0x46 -> Body.Updating(r.u32())
            0x80 -> Body.Self(r.addr(), r.u8(), r.str(Companion.REGION_MAX), r.i8(), r.u32())
            0x81 -> Body.Contact(r.addr(), r.u8(), r.str(Companion.NAME_MAX))
            0x82 -> Body.ContactGone(r.addr())
            0x83 -> Body.Message(
                r.u32(), r.addr(), r.u32(), r.u8(), r.u8(), r.u8(), r.u16(), r.str(Companion.TEXT_MAX),
            )
            0x84 -> Body.State(r.u32(), r.u8(), r.u8(), r.u16())
            0x85 -> Body.Neighbour(r.u32(), r.u8(), r.i8(), r.u16())
            0x86 -> Body.NeighbourGone(r.u32())
            0x87 -> Body.Airtime(r.u32(), r.u32(), r.u32(), r.u32())
            0x88 -> Body.Power(r.u16(), r.u8(), r.u8())
            0x89 -> Body.Asked(r.addr(), r.u8())
            0x8A -> Body.Group(r.gid(), r.str(Companion.NAME_MAX))
            0x8B -> Body.GroupGone(r.gid())
            0x8C -> Body.GroupMessage(
                r.u32(), r.gid(), r.u32(), r.u32(), r.u8(), r.u8(), r.u8(), r.u16(), r.str(Companion.TEXT_MAX),
            )
            0x8D -> Body.Invite(
                r.u32(), r.addr(), r.gid(), r.u32(), r.u8(), r.u8(), r.u8(), r.u16(), r.str(Companion.NAME_MAX),
            )
            0x8E -> Body.Position(r.addr(), r.u8(), r.i32(), r.i32(), r.i16(), r.u8(), r.u32())
            0x8F -> Body.GroupPosition(r.gid(), r.u32(), r.u8(), r.i32(), r.i32(), r.i16(), r.u8(), r.u32())
            0x90 -> Body.Sharing(r.addr(), r.u8(), r.u8(), r.u16(), r.u16())
            0x91 -> Body.GroupSharing(r.gid(), r.u8(), r.u8(), r.u16(), r.u16())
            else -> throw DecodeException(Unreadable.UNDEFINED)
        }
        return Frame(seq, body)
    }

    /**
     * The `ERROR` code a node answers a frame it could not read with, or null if it answers
     * nothing: a frame shorter than two bytes, or one whose type is not a request's.
     */
    fun errorCode(bytes: ByteArray, reason: Unreadable): Int? {
        if (bytes.size < 2 || !Companion.isRequest(bytes[0].toInt() and 0xFF)) return null
        return when (reason) {
            Unreadable.SHORT -> null
            Unreadable.UNDEFINED -> 1
            Unreadable.MALFORMED -> 2
        }
    }
}

private class Writer {
    private val out = ByteArrayOutputStream()

    fun u8(v: Int) = unsigned(v, 1)
    fun u16(v: Int) = unsigned(v, 2)
    fun u32(v: Long) {
        require(v in 0..0xFFFF_FFFFL) { "$v does not fit four bytes" }
        for (shift in intArrayOf(24, 16, 8, 0)) out.write((v shr shift).toInt() and 0xFF)
    }

    fun i8(v: Int) {
        require(v in -128..127) { "$v does not fit a signed byte" }
        out.write(v and 0xFF)
    }

    fun i16(v: Int) {
        require(v in Short.MIN_VALUE..Short.MAX_VALUE) { "$v does not fit two signed bytes" }
        out.write(v shr 8 and 0xFF)
        out.write(v and 0xFF)
    }

    fun i32(v: Int) {
        for (shift in intArrayOf(24, 16, 8, 0)) out.write(v shr shift and 0xFF)
    }

    /** `precision`, `fields`, `interval` and `minutes`: `SHARE`'s, `SHARE_GROUP`'s and their news'. */
    fun sharing(precision: Int, fields: Int, interval: Int, minutes: Int) {
        u8(precision)
        u8(fields)
        u16(interval)
        u16(minutes)
    }

    /** `POSITION`'s and `GROUP_POSITION`'s fields after the sender's. */
    fun position(precision: Int, lat: Int, lon: Int, altitude: Int, accuracy: Int, age: Long) {
        u8(precision)
        i32(lat)
        i32(lon)
        i16(altitude)
        u8(accuracy)
        u32(age)
    }

    fun addr(a: Address) = out.write(a.toByteArray())
    fun gid(g: GroupId) = out.write(g.toByteArray())

    fun str(s: String, limit: Int) {
        val utf8 = s.toByteArray(Charsets.UTF_8)
        require(utf8.size <= limit) { "text of ${utf8.size} bytes, longer than $limit" }
        out.write(utf8.size)
        out.write(utf8)
    }

    fun bytes(b: ByteArray, limit: Int) {
        require(b.size <= limit) { "${b.size} bytes, longer than $limit" }
        out.write(b.size)
        out.write(b)
    }

    fun raw(b: ByteArray) = out.write(b)

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun unsigned(v: Int, size: Int) {
        require(v >= 0 && v < 1 shl (8 * size)) { "$v does not fit $size bytes" }
        for (i in size - 1 downTo 0) out.write((v shr (8 * i)) and 0xFF)
    }
}

private class Reader(private val bytes: ByteArray) {
    private var at = 0

    private fun next(): Int {
        if (at >= bytes.size) throw DecodeException(Unreadable.MALFORMED)
        return bytes[at++].toInt() and 0xFF
    }

    fun u8() = next()
    fun i8() = next().toByte().toInt()
    fun u16() = next() shl 8 or next()
    fun i16() = u16().toShort().toInt()
    fun u32(): Long = (0 until 4).fold(0L) { v, _ -> v shl 8 or next().toLong() }
    fun i32() = u32().toInt()

    fun addr(): Address {
        if (at + Address.LENGTH > bytes.size) throw DecodeException(Unreadable.MALFORMED)
        return Address(bytes.copyOfRange(at, at + Address.LENGTH)).also { at += Address.LENGTH }
    }

    fun gid(): GroupId {
        if (at + GroupId.LENGTH > bytes.size) throw DecodeException(Unreadable.MALFORMED)
        return GroupId(bytes.copyOfRange(at, at + GroupId.LENGTH)).also { at += GroupId.LENGTH }
    }

    fun raw(n: Int): ByteArray {
        if (at + n > bytes.size) throw DecodeException(Unreadable.MALFORMED)
        return bytes.copyOfRange(at, at + n).also { at += n }
    }

    fun bytes(limit: Int): ByteArray {
        val n = next()
        if (n > limit) throw DecodeException(Unreadable.MALFORMED)
        return raw(n)
    }

    fun str(limit: Int): String {
        val n = next()
        if (n > limit || at + n > bytes.size) throw DecodeException(Unreadable.MALFORMED)
        // String(bytes, UTF_8) would replace bad UTF-8; the protocol rejects it.
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes, at, n)).toString()
        } catch (e: CharacterCodingException) {
            throw DecodeException(Unreadable.MALFORMED)
        }
        at += n
        return text
    }
}
