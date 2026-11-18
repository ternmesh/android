// The release keys: the Ed25519 public keys of whoever publishes Tern's firmware, which an image's
// signature must verify with before this app sends it to a node (draft/companion.md, "Signed
// images"). The same list as the firmware's ports/node/release_keys.h, which is what a node of
// version 13 or later checks an update with, and the site's.
//
// To add a key: one line in RELEASE_KEYS below, its 64 hex digits as
// `python3 tools/release_key.py public KEYFILE` in ternmesh/firmware prints them, with a comment
// saying when it was added. Keep the old one listed until no release signed with it is offered.
// The specification's published test key (vectors/companion.json's release_key) never goes here:
// anyone can sign with it. The tests use it on their own.
package org.ternmesh.companion

object ReleaseKeys {
    val RELEASE_KEYS: List<String> = listOf(
        // Release key 1, added 2026-11-18 (ternmesh/firmware#120).
        "09800d79c2c3d0cab7f1710a18cd50d6f0e39c57d9cfe395b371195d4942f88d",
    )

    /** The keys, as bytes. */
    val keys: List<ByteArray> get() = RELEASE_KEYS.map { Hex.decode(it)!! }
}
