// Images' signatures against the specification's vectors (companion.json's release_key,
// image_signature and signatures), and the release keys this app ships with.
package org.ternmesh.companion

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignaturesTest {
    private val v = vectors

    @Test
    fun everySignatureCase() {
        val cases = v.list("signatures")
        assertTrue(cases.size >= 7)
        for (c in cases) {
            val why = c.str("why")
            val digest = c.bytes("digest")
            assertEquals(c.str("message"), Hex.encode(Signatures.message(digest)), why)
            val key = c.bytes("release_key")
            val signature = c.bytes("signature")
            val verifies = c.getValue("verifies").toString() == "true"
            assertEquals(verifies, Signatures.verifies(digest, signature, listOf(key)), why)
            // Among other keys, the same.
            assertEquals(verifies, Signatures.verifies(digest, signature, listOf(ByteArray(32), key, ByteArray(32) { 7 })), "$why, among others")
            assertEquals(
                if (signature.isEmpty()) Signed.UNSIGNED else if (verifies) Signed.VERIFIED else Signed.NOT_VERIFIED,
                Signatures.check(digest, signature, listOf(key)),
                why,
            )
        }
    }

    @Test
    fun theImagesOwnSignature() {
        val digest = MessageDigest.getInstance("SHA-256").digest(v.bytes("image"))
        assertEquals(Signed.VERIFIED, Signatures.check(digest, v.bytes("image_signature"), listOf(testKey)))
        // The app's own keys do not hold the test key, so the vectors' image is not the release's.
        assertEquals(Signed.NOT_VERIFIED, Signatures.check(digest, v.bytes("image_signature")))
        assertEquals(Signed.UNSIGNED, Signatures.check(digest, null))
        assertFalse(Signatures.verifies(digest, v.bytes("image_signature"), emptyList()))
    }

    /** The shipped list is release key 1 alone (ternmesh/firmware#120), and never the published test key. */
    @Test
    fun theReleaseKeysAreKey1AndNotTheTestKey() {
        assertEquals(listOf("09800d79c2c3d0cab7f1710a18cd50d6f0e39c57d9cfe395b371195d4942f88d"), ReleaseKeys.RELEASE_KEYS)
        assertTrue(ReleaseKeys.keys.none { it.contentEquals(testKey) })
        assertTrue(Hex.encode(testKey) !in ReleaseKeys.RELEASE_KEYS)
        for (k in ReleaseKeys.keys) {
            assertEquals(32, k.size)
            // A valid point, of the prime order: a key that is not verifies nothing.
            val p = Ed25519Point.decode(k)!!
            assertTrue(!p.isNeutral && p.times(Ed25519Point.L).isNeutral)
            assertContentEquals(k, p.encode())
        }
    }

    /** Which images are offered to which nodes: unsigned ones never to a node of 13 updated over the link. */
    @Test
    fun whatIsOfferedToWhichNode() {
        for (v in listOf(4, 11, 12, 13, 14)) {
            for (way in listOf(UpdateBy.LINK, UpdateBy.BOOTLOADER)) {
                assertEquals(Install.OFFER, install(Signed.VERIFIED, v, way), "$v $way")
                assertEquals(Install.WARN, install(Signed.NOT_VERIFIED, v, way), "$v $way")
                val unsigned = if (v >= 13 && way == UpdateBy.LINK) Install.REFUSE else Install.WARN
                assertEquals(unsigned, install(Signed.UNSIGNED, v, way), "$v $way")
            }
        }
        assertEquals(Install.WARN, install(Signed.UNSIGNED, null, UpdateBy.LINK), "a node that has not said")
    }
}
