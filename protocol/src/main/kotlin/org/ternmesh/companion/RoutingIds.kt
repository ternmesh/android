// Routing ids, as draft/routing.md in ternmesh/spec works them out from an address, and which one a
// client matches a node's routing ids against.
//
// A node of version 14 or later knows every node by an id made from its routing key B, a key
// blinded from the address A: h = SHA-512("tern v0 blind" || A || u32be(0)) mod L, B = [h]A, and the
// id from SHA-256("tern routing id" || B). A node of version 13 or earlier made the id from A itself.
// A client is told only ids, in NEIGHBOUR, GROUP_MESSAGE and GROUP_POSITION, and finds whose one is
// by working out the id of each address it knows, the way the node's version does.
//
// The blinding needs one scalar multiplication on the Ed25519 curve, which neither the JDK nor
// Android offers on a point of one's own. It is done here with java.math.BigInteger, by the curve's
// textbook formulas (RFC 8032, sections 5.1.3 and 5.1.4). Everything it handles is public, an
// address and a hash of it, so it need not run in constant time, and it does not.
package org.ternmesh.companion

import java.math.BigInteger
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object RoutingIds {
    /** The first version whose routing ids are made from the blinded key. */
    const val BLINDED_SINCE = 14

    /**
     * The routing id a node speaking [version] knows [address] by: the blinded one from version 14,
     * the address's own before it, and that also when the version is not known yet. Null for an
     * address that has no routing id under blinding (one not of the curve's prime order), which
     * matches no node.
     */
    fun of(address: Address, version: Int?): Long? =
        if (version != null && version >= BLINDED_SINCE) blinded(address) else plain(address)

    /** SHA-256("tern routing id" || A)'s id: what a node of version 13 or earlier knows A by. */
    fun plain(address: Address): Long = idOf(address.toByteArray())!!

    /** The id from the routing key blinded from [address], worked out once per address and kept. */
    fun blinded(address: Address): Long? =
        cache.getOrPut(address) { Optional(key(address)?.let(::idOf)) }.value

    /** The routing key B = [h]A, 32 bytes, or null for an address that is not a valid one. */
    fun key(address: Address): ByteArray? {
        val a = address.toByteArray()
        val point = Ed25519Point.decode(a) ?: return null
        // Of the prime order L: not the neutral point, nor one with a part of small order.
        if (point.isNeutral || !point.times(Ed25519Point.L).isNeutral) return null
        val h = blind(a)
        if (h.signum() == 0) return null
        return point.times(h).encode()
    }

    /** h = SHA-512("tern v0 blind" || A || u32be(0)), read as a little-endian number, mod L. */
    fun blind(address: ByteArray): BigInteger {
        val sha = MessageDigest.getInstance("SHA-512")
        sha.update(BLIND_LABEL)
        sha.update(address)
        sha.update(byteArrayOf(0, 0, 0, 0)) // the epoch, 0: ids do not rotate
        return Ed25519Point.littleEndian(sha.digest()).mod(Ed25519Point.L)
    }

    /** The first of SHA-256("tern routing id" || key)'s eight words that is neither 0 nor 0xFFFFFFFF. */
    private fun idOf(key: ByteArray): Long? {
        val sha = MessageDigest.getInstance("SHA-256")
        sha.update(ID_LABEL)
        val h = sha.digest(key)
        return (0 until 8).map { i -> (0 until 4).fold(0L) { n, j -> (n shl 8) or (h[4 * i + j].toLong() and 0xFF) } }
            .firstOrNull { it != 0L && it != 0xFFFF_FFFFL }
    }

    private val BLIND_LABEL = "tern v0 blind".toByteArray(Charsets.US_ASCII)
    private val ID_LABEL = "tern routing id".toByteArray(Charsets.US_ASCII)

    /** Null is a result too: an address with no id is not worked out twice. */
    private class Optional(val value: Long?)

    /** Contacts' and cards' addresses: a few hundred at most, each 32 bytes and a number. */
    private val cache = ConcurrentHashMap<Address, Optional>()
}

/**
 * A point of the Ed25519 curve, -x² + y² = 1 + d·x²·y² over the integers mod p, in extended
 * coordinates (X : Y : Z : T) with x = X/Z, y = Y/Z and x·y = T/Z. Not constant time: for public
 * points and scalars only.
 */
internal class Ed25519Point private constructor(
    private val x: BigInteger,
    private val y: BigInteger,
    private val z: BigInteger,
    private val t: BigInteger,
) {
    val isNeutral: Boolean get() = x.signum() == 0 && y == z

    /** The sum, by the curve's complete addition law (RFC 8032, 5.1.4), which doubles too. */
    operator fun plus(o: Ed25519Point): Ed25519Point {
        val a = (y - x) * (o.y - o.x) % P
        val b = (y + x) * (o.y + o.x) % P
        val c = t * D2 % P * o.t % P
        val d = z * TWO * o.z % P
        val e = b - a
        val f = d - c
        val g = d + c
        val h = b + a
        return Ed25519Point(e * f % P, g * h % P, f * g % P, e * h % P)
    }

    /** [n] times this point, for n ≥ 0, by doubling and adding from the top bit. */
    fun times(n: BigInteger): Ed25519Point {
        require(n.signum() >= 0)
        var r = NEUTRAL
        for (i in n.bitLength() - 1 downTo 0) {
            r += r
            if (n.testBit(i)) r += this
        }
        return r
    }

    /** RFC 8032, 5.1.2: y little-endian, with the sign of x in the top bit. */
    fun encode(): ByteArray {
        val zi = z.modInverse(P)
        val ax = x * zi % P
        val ay = y * zi % P
        val out = ByteArray(32)
        val b = ay.toByteArray() // big-endian, maybe with a leading 0
        for (i in b.indices) {
            val at = b.size - 1 - i
            if (i < 32) out[i] = b[at]
        }
        if (ax.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    override fun equals(other: Any?) =
        other is Ed25519Point && (x * other.z - other.x * z).mod(P).signum() == 0 && (y * other.z - other.y * z).mod(P).signum() == 0

    override fun hashCode() = 0

    companion object {
        val P: BigInteger = BigInteger.ONE.shiftLeft(255) - BigInteger.valueOf(19)

        /** The order of the base point, and of every valid address. */
        val L: BigInteger = BigInteger.ONE.shiftLeft(252) + BigInteger("27742317777372353535851937790883648493")
        private val TWO = BigInteger.valueOf(2)
        private val D: BigInteger = BigInteger.valueOf(-121665).mod(P) * BigInteger.valueOf(121666).modInverse(P) % P
        private val D2 = D * TWO % P
        private val SQRT_M1: BigInteger = TWO.modPow((P - BigInteger.ONE).shiftRight(2), P)

        val NEUTRAL = Ed25519Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

        /** The base point G, whose y is 4/5. */
        val BASE: Ed25519Point = run {
            val by = BigInteger.valueOf(4) * BigInteger.valueOf(5).modInverse(P) % P
            decode(ByteArray(32).also { buf -> for (i in 0 until 32) buf[i] = by.shiftRight(8 * i).toInt().toByte() })!!
        }

        fun littleEndian(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

        /**
         * RFC 8032, 5.1.3: the point [bytes] encode, or null if they encode none: a y not less than
         * p, a y with no x, or x = 0 with the sign bit set.
         */
        fun decode(bytes: ByteArray): Ed25519Point? {
            if (bytes.size != 32) return null
            val sign = bytes[31].toInt() and 0x80 != 0
            val yb = bytes.copyOf().also { it[31] = (it[31].toInt() and 0x7F).toByte() }
            val y = littleEndian(yb)
            if (y >= P) return null
            val yy = y * y % P
            val u = (yy - BigInteger.ONE).mod(P)
            val v = (D * yy + BigInteger.ONE) % P
            // x = (u/v)^((p+3)/8), as u·v³·(u·v⁷)^((p-5)/8), then fixed by √-1 if that squares to -u/v.
            val v3 = v * v % P * v % P
            var x = u * v3 % P * (u * v3 % P * v3 % P * v % P).modPow((P - BigInteger.valueOf(5)).shiftRight(3), P) % P
            val vxx = v * x % P * x % P
            when {
                vxx == u -> {}
                vxx == (P - u) % P -> x = x * SQRT_M1 % P
                else -> return null
            }
            if (x.signum() == 0 && sign) return null
            if (x.testBit(0) != sign) x = P - x
            return Ed25519Point(x, y, BigInteger.ONE, x * y % P)
        }
    }
}

private operator fun BigInteger.plus(o: BigInteger): BigInteger = add(o)
private operator fun BigInteger.minus(o: BigInteger): BigInteger = subtract(o).let { if (it.signum() < 0) it.add(Ed25519Point.P) else it }
private operator fun BigInteger.times(o: BigInteger): BigInteger = multiply(o)
private operator fun BigInteger.rem(o: BigInteger): BigInteger = mod(o)
