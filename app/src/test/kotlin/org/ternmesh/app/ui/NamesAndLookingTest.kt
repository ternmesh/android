// Names in first contact (version 19) and asking for cards (version 20), in the app's own words: a
// name a node gave itself is shown as its claim, never in place of the user's, and kept with the
// ASKED it came in; LOOK is offered only to a node that defines it, and its ERROR 16 is said.
package org.ternmesh.app.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.ternmesh.app.node.NodeState
import org.ternmesh.app.node.askedEntry
import org.ternmesh.app.node.askedOf
import org.ternmesh.companion.Address
import org.ternmesh.companion.Body
import org.ternmesh.companion.ErrorCode
import org.ternmesh.companion.Records

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NamesAndLookingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    // The vectors' second address: RFC 8032's second Ed25519 public key.
    private val bob = Address.fromHex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")!!

    @Test
    fun aContactsOwnNameIsShownAsItsClaimOnlyWhereTheUserGaveNone() {
        val claimed = Body.Contact(bob, 1, "", 0, 0, "Robert at the ridge")
        assertEquals("“Robert at the ridge” · 3d4017c3…", contactName(claimed))
        assertEquals("Bob", contactName(claimed.copy(name = "Bob")))
        assertEquals("3d4017c3…", contactName(claimed.copy(says = "")))
        assertEquals("3d4017c3…", contactName(Body.Contact(bob, 1, "", 0, 0)))
        val records = Records().apply { apply(claimed) }
        assertEquals("“Robert at the ridge” · 3d4017c3…", nodeName(context, records, bob))
    }

    @Test
    fun anAskedIsKeptWithItsNameWhateverTheNameHoldsAndOneKeptBeforeNamesStillReads() {
        for (asked in listOf(
            Body.Asked(bob, 1, "Carol"),
            Body.Asked(bob, 2, "a, b: c"),
            Body.Asked(bob, 1, ""),
            Body.Asked(bob, 1),
        )) {
            val entry = askedEntry(asked)
            assertFalse(entry.substringAfter(':').substringAfter(':').contains(','))
            assertEquals(asked, askedOf(entry))
        }
        assertEquals(Body.Asked(bob, 1), askedOf("$bob:1"))
        assertNull(askedOf("$bob:1:!!"))
        assertNull(askedOf("nothing"))
    }

    @Test
    fun lookIsOfferedFromVersion20AndTooSoonIsSaid() {
        assertFalse(canLook(NodeState(version = 19)))
        assertFalse(canLook(NodeState()))
        assertTrue(canLook(NodeState(version = 20)))
        assertTrue(errorText(context, ErrorCode.TOO_SOON).contains("ten minutes"))
        assertTrue(context.getString(org.ternmesh.app.R.string.look_explained).contains("nothing of yours"))
    }
}
