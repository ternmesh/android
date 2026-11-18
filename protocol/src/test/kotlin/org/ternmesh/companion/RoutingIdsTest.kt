// Routing ids against the specification's vectors (src/test/resources/vectors/routing.json, a copy
// of vectors/routing.json in ternmesh/spec): its ids, each address's blind, key and id, and its
// no_ids, addresses that have none. And the curve arithmetic under them, against BouncyCastle's.
package org.ternmesh.companion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutingIdsTest {
    private val v = routingVectors

    @Test
    fun idsBlindedAsRoutingWorksThemOut() {
        val ids = v.list("ids")
        assertTrue(ids.isNotEmpty())
        for (c in ids) {
            val address = c.addr("address")
            val hex = c.str("address")
            val blind = RoutingIds.blind(address.toByteArray())
            assertEquals(Ed25519Point.littleEndian(c.bytes("blind")), blind, "h of $hex")
            assertEquals(c.str("key"), RoutingIds.key(address)?.let(Hex::encode), "the key of $hex")
            assertEquals(c.long("id"), RoutingIds.blinded(address), "the id of $hex")
            assertEquals(c.long("id"), RoutingIds.of(address, 14), hex)
            assertEquals(c.long("id"), RoutingIds.of(address, 15), "a later version's too")
            assertNotEquals(c.long("id"), RoutingIds.of(address, 13), "a node of 13 makes it from the address")
            assertEquals(RoutingIds.plain(address), RoutingIds.of(address, 13))
            assertEquals(RoutingIds.plain(address), RoutingIds.of(address, null), "before the version is known")
        }
    }

    @Test
    fun noIdsForWhatIsNotAValidAddress() {
        val none = v.list("no_ids")
        assertTrue(none.isNotEmpty())
        for (c in none) {
            val address = c.addr("address")
            assertNull(RoutingIds.key(address), c.str("why"))
            assertNull(RoutingIds.blinded(address), c.str("why"))
            assertNull(RoutingIds.of(address, Companion.VERSION), c.str("why"))
        }
    }

    /** A records' ids follow the version the node speaks: blinded once it says 14. */
    @Test
    fun recordsMatchByTheNodesVersion() {
        val c = v.list("ids").first()
        val address = c.addr("address")
        val r = Records()
        r.apply(Body.Contact(address, 1, "Alice"))
        assertEquals("Alice", Conversations.nameOf(r, RoutingIds.plain(address)), "no version yet: the address's own")
        r.syncedVersion = 13
        assertEquals("Alice", Conversations.nameOf(r, RoutingIds.plain(address)))
        assertNull(Conversations.nameOf(r, c.long("id")))
        r.speaking = 14
        assertEquals("Alice", Conversations.nameOf(r, c.long("id")))
        assertNull(Conversations.nameOf(r, RoutingIds.plain(address)), "a node of 14 has no such id")
        assertEquals(c.long("id"), r.copy().routingIdOf(address))
    }

    /** [s]G here is BouncyCastle's public key for the same seed: the arithmetic agrees with a library's. */
    @Test
    fun theBasePointTimesASecretIsItsPublicKey() {
        for (i in 0 until 6) {
            val seed = MessageDigest.getInstance("SHA-256").digest("tern routing id test $i".toByteArray())
            val bc = ByteArray(32).also { Ed25519.generatePublicKey(seed, 0, it, 0) }
            val h = MessageDigest.getInstance("SHA-512").digest(seed).copyOf(32)
            h[0] = (h[0].toInt() and 248).toByte()
            h[31] = (h[31].toInt() and 127 or 64).toByte()
            val s = Ed25519Point.littleEndian(h)
            assertContentEquals(bc, Ed25519Point.BASE.times(s).encode(), "seed $i")
            // And it decodes back to the same point.
            assertContentEquals(bc, Ed25519Point.decode(bc)!!.encode())
        }
        assertTrue(Ed25519Point.BASE.times(Ed25519Point.L).isNeutral)
        assertTrue(Ed25519Point.BASE.times(BigInteger.ZERO).isNeutral)
    }
}

/** The vectors: CI sets the property to run against the specification's own, as they are on main. */
private val routingVectors: JsonObject by lazy {
    val text = System.getProperty("tern.routing.vectors")?.let { File(it).readText() }
        ?: RoutingIdsTest::class.java.getResource("/vectors/routing.json")!!.readText()
    Json.parseToJsonElement(text).jsonObject
}
