// Sharing against the specification's vectors (src/test/resources/vectors/sharing.json, a copy of
// vectors/sharing.json in ternmesh/spec): its conformance section, but for the not-contacts cases,
// which the node refuses (a contact it is given must be a valid address) and so are tested there.
package org.ternmesh.companion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharingVectorsTest {
    private val v = sharingVectors

    @Test
    fun casesShownAndRead() {
        val cases = v.list("cases")
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val a = c.addr("address")
            assertEquals(c.str("text"), Sharing.text(a))
            assertEquals(c.str("link"), Sharing.link(a))
            assertTrue(Sharing.link(a).endsWith(c.str("base32")))
            assertEquals(c.str("short_code"), Sharing.shortCode(a))
            assertEquals(a, Sharing.read(Sharing.grouped(a)))
            for (r in c.getValue("reads").jsonArray) assertEquals(a, Sharing.read(r.jsonPrimitive.content), r.toString())
        }
    }

    @Test
    fun refusedReadsNothing() {
        for (r in v.getValue("refused").jsonArray) assertNull(Sharing.read(r.jsonPrimitive.content), r.toString())
    }

    @Test
    fun notContactsStillRead() {
        for (c in v.list("not_contacts")) assertNotNull(Sharing.read(c.str("link")), c.str("reason"))
    }

    @Test
    fun shortCodeForms() {
        for (c in v.list("short_code_forms")) assertEquals(c.str("text"), Sharing.format(c.long("value")))
    }
}

/** The vectors: CI sets the property to run against the specification's own, as they are on main. */
private val sharingVectors: JsonObject by lazy {
    val text = System.getProperty("tern.sharing.vectors")?.let { File(it).readText() }
        ?: SharingVectorsTest::class.java.getResource("/vectors/sharing.json")!!.readText()
    Json.parseToJsonElement(text).jsonObject
}
