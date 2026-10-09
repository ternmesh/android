// A group's join code: draft/groups.md#join-codes in ternmesh/spec. The node writes the link and
// reads it; a client reads one only to say which group it is for before the user joins, and keeps
// neither the link nor the secret in it.
package org.ternmesh.companion

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** What a join code says, but for the secret: the group it is for, and what whoever made it calls the group, a suggestion. */
data class JoinCode(val group: GroupId, val name: String) {
    companion object {
        /** The link's start: the code follows it in base32. */
        const val LINK = "HTTPS://TERNMESH.ORG/G#"

        private const val SECRET = 16
        private const val CODE_MIN = SECRET + 2
        private const val CODE_MAX = CODE_MIN + org.ternmesh.companion.Companion.NAME_MAX

        /**
         * The group a join code is for, and its name, or null: the scheme, host and `G` each in
         * either case, and the base32 in either case, where either case is ASCII's, and nothing
         * else, a check that fails or a name that is not UTF-8 included.
         */
        fun read(text: String): JoinCode? {
            if (text.any { it.code > 0x7F } || text.length < LINK.length) return null
            if (Sharing.asciiUpper(text.substring(0, LINK.length)) != LINK) return null
            val code = Sharing.unbase32Any(text.substring(LINK.length)) ?: return null
            try {
                if (code.size !in CODE_MIN..CODE_MAX) return null
                val secret = code.copyOfRange(0, SECRET)
                val raw = code.copyOfRange(CODE_MIN, code.size)
                val sha = MessageDigest.getInstance("SHA-256")
                sha.update("tern group code".toByteArray(Charsets.US_ASCII))
                sha.update(secret)
                val check = sha.digest(raw)
                if (check[0] != code[SECRET] || check[1] != code[SECRET + 1]) return null
                val name = try {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(raw)).toString()
                } catch (e: CharacterCodingException) {
                    return null
                }
                return JoinCode(groupId(secret).also { secret.fill(0) }, name)
            } finally {
                code.fill(0)
            }
        }

        /** A group's id from its secret: Expand(G, "tern v0 group id", 8), HKDF-Expand with SHA-256. */
        internal fun groupId(secret: ByteArray): GroupId {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret, "HmacSHA256"))
            mac.update("tern v0 group id".toByteArray(Charsets.US_ASCII))
            return GroupId(mac.doFinal(byteArrayOf(1)).copyOf(GroupId.LENGTH))
        }
    }
}
