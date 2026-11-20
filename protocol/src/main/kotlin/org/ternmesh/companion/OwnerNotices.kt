// The notices that a node's owners changed (OWNER_CHANGED, version 17), kept on disk until the user
// dismisses them. The node does not hold them, and a sync does not send them again, so a client that
// lost them on a restart would never show them: and the user may not have made the change.
//
// Each is kept as the frame that carried it, as RecordsFile keeps records:
//
//   "TRNO", the format (1); then, to the end, one byte n and n bytes of an OWNER_CHANGED of seq 0,
//   oldest first.
package org.ternmesh.companion

import java.io.ByteArrayOutputStream

object OwnerNotices {
    /** The most kept: the newest, the oldest going first. */
    const val KEPT = 20

    private val MAGIC = "TRNO".toByteArray(Charsets.US_ASCII)
    private const val FORMAT = 1

    /** [held] with [notice] added as the newest: the same notice again moves to the end, and only the last [KEPT] stay. */
    fun add(held: List<Body.OwnerChanged>, notice: Body.OwnerChanged): List<Body.OwnerChanged> =
        (held.filter { it != notice } + notice).takeLast(KEPT)

    fun encode(notices: List<Body.OwnerChanged>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(FORMAT)
        for (n in notices.takeLast(KEPT)) {
            val frame = Codec.encode(Frame(0, n))
            out.write(frame.size)
            out.write(frame)
        }
        return out.toByteArray()
    }

    /** The notices [bytes] hold, oldest first; none from anything this cannot read whole. */
    fun decode(bytes: ByteArray): List<Body.OwnerChanged> {
        if (bytes.size < MAGIC.size + 1 || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) || bytes[MAGIC.size].toInt() != FORMAT) {
            return emptyList()
        }
        val notices = mutableListOf<Body.OwnerChanged>()
        var at = MAGIC.size + 1
        while (at < bytes.size) {
            val n = bytes[at].toInt() and 0xFF
            if (at + 1 + n > bytes.size) return emptyList()
            val body = try {
                Codec.decode(bytes.copyOfRange(at + 1, at + 1 + n)).body
            } catch (e: DecodeException) {
                return emptyList()
            }
            notices += body as? Body.OwnerChanged ?: return emptyList()
            at += 1 + n
        }
        return notices.takeLast(KEPT)
    }
}
