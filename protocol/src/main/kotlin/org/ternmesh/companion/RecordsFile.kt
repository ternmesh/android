// Records on disk, so the app's next run asks the node only for what is new. Each record is kept as
// the frame that carried it, which the codec already builds and reads; the Apple app keeps the same
// format.
//
// Positions, sharing and cards are not kept. Each carries a count (`age`, `minutes`, `heard`) as of
// when it was sent, which a file would leave standing as the time passed, and every sync sends all
// three whole, so the next connection has them again at once. A file with them in is read all the
// same.
//
// A `SELF` is kept as it came: without `cards` and `card_name` from a connection that spoke version
// 5 or earlier, and read back so.
//
//   "TRNR", the format (1), syncedVersion (0xFF for none), 1 if missedSince is set, missedSince as a
//   big-endian u32; then, to the end, one byte n and n bytes of a frame of seq 0, for each record.
package org.ternmesh.companion

import java.io.ByteArrayOutputStream

object RecordsFile {
    private val MAGIC = "TRNR".toByteArray(Charsets.US_ASCII)
    private const val FORMAT = 1
    private const val HEADER = 11

    fun encode(records: Records): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(FORMAT)
        out.write(records.syncedVersion ?: 0xFF)
        val missed = records.missedSince
        out.write(if (missed == null) 0 else 1)
        val m = missed ?: 0
        for (shift in intArrayOf(24, 16, 8, 0)) out.write((m shr shift).toInt() and 0xFF)
        val bodies = listOfNotNull(records.self) + records.contacts.values + records.groups.values +
            records.ordered.map { it as Body } + records.neighbours.values +
            listOfNotNull(records.airtime, records.power)
        for (body in bodies) {
            val frame = Codec.encode(Frame(0, body))
            out.write(frame.size)
            out.write(frame)
        }
        return out.toByteArray()
    }

    /**
     * The records [bytes] hold. Anything this cannot read whole gives empty records: the next sync,
     * from 0, then asks the node for everything again.
     */
    fun decode(bytes: ByteArray): Records {
        if (bytes.size < HEADER || !bytes.copyOfRange(0, 4).contentEquals(MAGIC) || bytes[4].toInt() != FORMAT) {
            return Records()
        }
        // A synced version this client does not speak, or a flag neither 0 nor 1, is a spoiled file
        // too: trusting it would sync from where it says, past what it lost.
        val synced = bytes[5].toInt() and 0xFF
        if (synced != 0xFF && synced > Companion.VERSION || (bytes[6].toInt() and 0xFF) > 1) return Records()
        val records = Records()
        var at = HEADER
        while (at < bytes.size) {
            val n = bytes[at].toInt() and 0xFF
            if (at + 1 + n > bytes.size) return Records()
            val body = try {
                record(bytes.copyOfRange(at + 1, at + 1 + n))
            } catch (e: DecodeException) {
                return Records()
            }
            if (!Companion.isNews(body.type) || body is Body.Asked || body is Body.State || body.typeName.endsWith("_GONE")) {
                return Records()
            }
            records.apply(body)
            at += 1 + n
        }
        records.syncedVersion = if (synced == 0xFF) null else synced
        records.missedSince = if (bytes[6].toInt() == 0) null else
            (7 until 11).fold(0L) { v, i -> v shl 8 or (bytes[i].toLong() and 0xFF) }
        return records
    }

    /** One record's frame, read as this client's version reads it; a `SELF` that is not one of version 6 as version 5's, which is all that differs between the versions' records. */
    private fun record(frame: ByteArray): Body = try {
        Codec.decode(frame).body
    } catch (e: DecodeException) {
        if (frame.firstOrNull() != 0x80.toByte()) throw e
        Codec.decode(frame, 5).body
    }
}
