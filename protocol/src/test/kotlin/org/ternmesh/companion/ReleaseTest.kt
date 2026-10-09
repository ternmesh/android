// The site's firmware manifest, and Semantic Versioning's order.
package org.ternmesh.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseTest {
    private val digest = "ab".repeat(32)

    private val manifest = """
        {"release": "0.2.0", "notes": "ignored",
         "images": [
           {"board": "heltec-v3", "region": "EU868", "file": "tern-heltec-v3-eu868-0.2.0-app.bin", "size": 1048576, "sha256": "$digest"},
           {"board": "heltec-v3", "region": "US915", "file": "tern-heltec-v3-us915-0.2.0-app.bin", "size": 1048000, "sha256": "${digest.uppercase()}"},
           {"board": "rak4631", "region": "EU868", "file": "../../etc/passwd", "size": 10, "sha256": "$digest"},
           {"board": "t-echo", "region": "EU868", "file": "t.bin", "size": 1.5, "sha256": "$digest"},
           {"board": "t-beam", "region": "EU868", "file": "t.bin", "size": 10, "sha256": "abc"},
           {"board": "t-deck", "region": "EU868", "file": "t.bin", "size": 3000000000, "sha256": "$digest"},
           "not an image"
         ]}
    """.trimIndent()

    @Test
    fun theImageForABoardAndRegion() {
        val m = Manifest.parse(manifest)
        assertEquals("0.2.0", m.release)
        assertEquals(2, m.images.size, "the images it cannot read are passed over")
        val eu = m.imageFor("HELTEC-V3", "eu868")!!
        assertEquals("https://ternmesh.org/firmware/tern-heltec-v3-eu868-0.2.0-app.bin", eu.url)
        assertEquals(1_048_576, eu.size)
        assertEquals(32, eu.digest.size)
        assertEquals(digest, m.imageFor("heltec-v3", "US915")!!.sha256, "a digest in either case")
        assertNull(m.imageFor("heltec-v3", "AS923"))
        assertNull(m.imageFor("", "EU868"))
        assertNull(m.imageFor("rak4631", "EU868"), "a file outside the firmware's folder is no image")
    }

    @Test
    fun newerUpToDateOrUnknown() {
        val m = Manifest.parse(manifest)
        assertEquals(Offer.NEWER, m.offerTo("0.1.9"))
        assertEquals(Offer.NEWER, m.offerTo("0.2.0-rc.1"))
        assertEquals(Offer.CURRENT, m.offerTo("0.2.0"))
        assertEquals(Offer.CURRENT, m.offerTo("0.2.0+build.7"))
        assertEquals(Offer.CURRENT, m.offerTo("0.10.0"))
        assertEquals(Offer.UNKNOWN, m.offerTo(""))
        assertEquals(Offer.UNKNOWN, m.offerTo(null))
        assertEquals(Offer.UNKNOWN, m.offerTo("v0.1.0"))
    }

    @Test
    fun whatIsNotAManifest() {
        for (text in listOf(
            "", "[]", "{}", """{"release": "0.2", "images": []}""", """{"release": "0.2.0"}""",
            """{"release": "0.2.0", "images": []} x""", """{"release": "0.2.0", "images": [}""",
            """{"release": "0.2.0", "images": [], }""",
        )) {
            assertFailsWith<IllegalArgumentException>(text) { Manifest.parse(text) }
        }
        assertEquals(Manifest("1.0.0", emptyList()), Manifest.parse(""" { "release" : "1.0.0" , "images" : [ ] } """))
    }

    @Test
    fun jsonItReads() {
        assertEquals(
            mapOf("a" to listOf(1L, -2L, 0.5, 1e3, true, false, null), "b" to "x\"\\/\né"),
            JsonText.parse("""{"a": [1, -2, 0.5, 1e3, true, false, null], "b": "x\"\\\/\né"}"""),
        )
        for (bad in listOf("01", "\"\u0001\"", "\"abc", "tru", "{\"a\" 1}", "[1 2]")) {
            assertFailsWith<IllegalArgumentException>(bad) { JsonText.parse(bad) }
        }
    }

    /** The order semver.org gives, lowest first. */
    @Test
    fun semanticVersioningsOrder() {
        val ordered = listOf(
            "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11",
            "1.0.0-rc.1", "1.0.0", "1.0.1", "1.1.0", "2.0.0", "10.0.0",
        ).map { SemVer.parse(it)!! }
        for (i in ordered.indices) for (j in ordered.indices) {
            assertEquals(i.compareTo(j), ordered[i].compareTo(ordered[j]).coerceIn(-1, 1), "${ordered[i]} against ${ordered[j]}")
        }
        assertEquals(SemVer.parse("1.0.0+a"), SemVer.parse("1.0.0+b"))
        for (bad in listOf("1.0", "01.0.0", "1.0.0-01", "1.0.0-", "1.0.0+", "v1.0.0", "1.0.0-a..b", "1.0.0-é")) {
            assertNull(SemVer.parse(bad), bad)
        }
        assertTrue(SemVer.parse("1.0.0-0a")!! > SemVer.parse("1.0.0-999")!!, "a part with a letter after any number")
    }
}
