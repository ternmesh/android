// An image's signature, as "Signed images" in draft/companion.md has it: Ed25519 (RFC 8032, not
// prehashed) by a release key over the 48 bytes "TERN-FIRMWARE-V1" || SHA-256(image). A client
// checks it before it sends an image either way: a node of version 13 or later that is updated over
// the link checks it again, and a node's bootloader checks none.
package org.ternmesh.companion

import org.bouncycastle.math.ec.rfc8032.Ed25519

/** What an image's signature is to this app. */
enum class Signed {
    /** It verifies with one of the release keys. */
    VERIFIED,

    /** It has one, which verifies with none of the keys this app holds: it may not be the release's. */
    NOT_VERIFIED,

    /** It has none: a release published before releases were signed, or a build of someone's own. */
    UNSIGNED,
}

object Signatures {
    private val PREFIX = "TERN-FIRMWARE-V1".toByteArray(Charsets.US_ASCII)

    /** The 48 bytes signed: the prefix, then the image's SHA-256. */
    fun message(digest: ByteArray): ByteArray {
        require(digest.size == Companion.DIGEST) { "a digest is ${Companion.DIGEST} bytes" }
        return PREFIX + digest
    }

    /**
     * Whether [signature] verifies over [digest] with one of [keys]: 64 bytes, and RFC 8032's
     * verification, which refuses an S not less than the group order, with one of them.
     */
    fun verifies(digest: ByteArray, signature: ByteArray?, keys: List<ByteArray> = ReleaseKeys.keys): Boolean {
        if (signature == null || signature.size != Ed25519.SIGNATURE_SIZE) return false
        val m = message(digest)
        return keys.any { k ->
            k.size == Ed25519.PUBLIC_KEY_SIZE && runCatching { Ed25519.verify(signature, 0, k, 0, m, 0, m.size) }.getOrDefault(false)
        }
    }

    /** [signature], over [digest], as one of [Signed]: null or empty is none. */
    fun check(digest: ByteArray, signature: ByteArray?, keys: List<ByteArray> = ReleaseKeys.keys): Signed = when {
        signature == null || signature.isEmpty() -> Signed.UNSIGNED
        verifies(digest, signature, keys) -> Signed.VERIFIED
        else -> Signed.NOT_VERIFIED
    }
}

/** What a client does with an image, given its signature and the node it is for. */
enum class Install {
    /** Signed by a release key: offered as any update is. */
    OFFER,

    /**
     * Not signed by a key this app holds, or not signed at all: offered only if the user asks after
     * being told it may not be the release's. A node of 12 or earlier checks nothing, and a node's
     * bootloader checks nothing; a node of 13 or later that holds a key this app does not may take it.
     */
    WARN,

    /**
     * Not signed at all, for a node of version 13 or later updated over the link: it takes only
     * signed images, and refuses it at once. A release from before releases were signed is put on
     * such a node over USB.
     */
    REFUSE,
}

/**
 * What to do with an image [signed] so, for a node whose `INFO` says [nodeVersion] and that is
 * updated as [updateBy], an [UpdateBy], says.
 */
fun install(signed: Signed, nodeVersion: Int?, updateBy: Int): Install = when {
    signed == Signed.VERIFIED -> Install.OFFER
    signed == Signed.UNSIGNED && updateBy == UpdateBy.LINK && (nodeVersion ?: 0) >= SIGNED_SINCE -> Install.REFUSE
    else -> Install.WARN
}
