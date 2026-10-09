// Join codes against the specification's vectors (src/test/resources/vectors/groups.json, a copy of
// vectors/groups.json in ternmesh/spec): its join_codes and bad_join_codes, as a client that reads
// a code to say which group it is for; and companion.json's group_ids, the id it says.
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

class JoinCodeVectorsTest {
    private val v = groupVectors

    @Test
    fun groupIdsFromTheirSecrets() {
        val cases = vectors.list("group_ids")
        assertTrue(cases.isNotEmpty())
        for (c in cases) assertEquals(c.gid("group"), JoinCode.groupId(c.bytes("group_secret")))
    }

    @Test
    fun everyJoinCodeReadsAsItsGroupAndNameHoweverItIsWritten() {
        val cases = v.list("join_codes")
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val code = JoinCode(JoinCode.groupId(c.bytes("group_secret")), c.str("name"))
            assertEquals(code, JoinCode.read(c.str("link")), c.str("link"))
            assertTrue(c.str("link").length in 52..Companion.LINK_MAX)
            for (r in c.getValue("reads").jsonArray) assertEquals(code, JoinCode.read(r.jsonPrimitive.content), r.toString())
        }
    }

    @Test
    fun anythingElseIsNoJoinCode() {
        for (c in v.list("bad_join_codes")) assertNull(JoinCode.read(c.str("link")), c.str("why"))
        assertNull(JoinCode.read(""))
        assertNull(JoinCode.read(JoinCode.LINK))
        assertNull(JoinCode.read(v.list("join_codes")[0].str("link") + " "))
        // A long s is S to a Unicode case mapping, and nothing to ASCII's.
        assertNotNull(JoinCode.read("https://ternmesh.org/g#ytcmjrgeytcmjrgeytcmjrgeyququ"))
        assertNull(JoinCode.read("httpſ://ternmesh.org/g#ytcmjrgeytcmjrgeytcmjrgeyququ"))
    }
}

/** The vectors: CI sets the property to run against the specification's own, as they are on main. */
private val groupVectors: JsonObject by lazy {
    val text = System.getProperty("tern.groups.vectors")?.let { File(it).readText() }
        ?: JoinCodeVectorsTest::class.java.getResource("/vectors/groups.json")!!.readText()
    Json.parseToJsonElement(text).jsonObject
}
