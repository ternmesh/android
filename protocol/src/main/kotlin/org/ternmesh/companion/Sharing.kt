// Sharing an address off the air: draft/sharing.md in ternmesh/spec. The text form, the link a QR
// code holds, reading either back, and the short code two people compare. Nothing here touches the
// network: everything in an address is in its link.
package org.ternmesh.companion

import java.security.MessageDigest

object Sharing {
    /** The link's start: the address follows it in base32. */
    const val LINK = "HTTPS://TERNMESH.ORG/A/"

    private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" // RFC 4648, section 6
    private const val BASE32_LENGTH = 52
    private const val SHORT_CODE_LABEL = "tern short code"
    private const val SHORT_CODE_MOD = 1_000_000_000_000L

    /** The address as a person reads it: 64 hex digits, upper-case. */
    fun text(address: Address): String = address.toString().uppercase()

    /** The text form in groups of eight, which is easier to read and still reads back. */
    fun grouped(address: Address): String = text(address).chunked(8).joinToString(" ")

    /** The link a QR code holds, all of it in the code's alphanumeric set. */
    fun link(address: Address): String = LINK + base32(address.toByteArray())

    /**
     * The address in a link or in the text form, or null for anything else. Either case is
     * ASCII's: a character outside ASCII is refused, even one a case mapping would turn into a
     * letter, so that every address has exactly one link.
     */
    fun read(text: String): Address? {
        if (text.isEmpty() || text.any { it.code > 0x7F }) return null
        if (text.length > LINK.length && asciiUpper(text.substring(0, LINK.length)) == LINK) {
            return unbase32(text.substring(LINK.length))?.let(::Address)
        }
        val digits = text.filter { it != ' ' }
        if (digits.length != Address.LENGTH * 2 || !digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return Address.fromHex(digits)
    }

    /** The twelve digits two people compare, as a number. */
    fun shortCodeValue(address: Address): Long {
        val sha = MessageDigest.getInstance("SHA-256")
        sha.update(SHORT_CODE_LABEL.toByteArray(Charsets.US_ASCII))
        val h = sha.digest(address.toByteArray())
        var n = 0L
        for (i in 0 until 8) n = (n shl 8) or (h[i].toLong() and 0xFF)
        return java.lang.Long.remainderUnsigned(n, SHORT_CODE_MOD)
    }

    /** The short code as it is shown: twelve digits in three groups of four. */
    fun shortCode(address: Address): String = format(shortCodeValue(address))

    /** A code's value written as it is shown, leading zeros kept. */
    fun format(value: Long): String = value.toString().padStart(12, '0').chunked(4).joinToString(" ")

    private fun asciiUpper(s: String) = String(CharArray(s.length) { val c = s[it]; if (c in 'a'..'z') c - 32 else c })

    private fun base32(bytes: ByteArray): String = buildString {
        var n = 0
        var bits = 0
        for (b in bytes) {
            n = ((n shl 8) or (b.toInt() and 0xFF)) and 0xFFF
            bits += 8
            while (bits >= 5) {
                bits -= 5
                append(BASE32[(n shr bits) and 31])
            }
        }
        if (bits > 0) append(BASE32[(n shl (5 - bits)) and 31])
    }

    /** The 32 bytes of canonical base32, either case, or null: wrong length, a character outside
     * the alphabet, or a spare bit set, which would give one address two links. */
    private fun unbase32(text: String): ByteArray? {
        if (text.length != BASE32_LENGTH) return null
        val out = ByteArray(Address.LENGTH)
        var n = 0
        var bits = 0
        var i = 0
        for (c in asciiUpper(text)) {
            val v = BASE32.indexOf(c)
            if (v < 0) return null
            n = ((n shl 5) or v) and 0xFFF
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out[i++] = (n shr bits).toByte()
            }
        }
        // 52 characters are 260 bits: the 256 of the address and four that must be zero.
        if (bits != 4 || n and 0xF != 0) return null
        return out
    }
}
