// The node's own card, one switch with its name under it, and the nodes near that showed none: what
// each control sends, and what the list says of a node it knows only by routing id.
package org.ternmesh.app.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.ternmesh.app.R
import org.ternmesh.companion.Setting

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CardWordsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun savingANameWhileOffTurnsTheCardOn() {
        // What was reported: a name set, the card still off. Saving now sends the name, then cards on.
        assertEquals(listOf(Setting.CardName("Hilltop"), Setting.Cards(1)), cardRequests(on = false, saved = "", typed = " Hilltop ", turnOn = true))
        // The name unchanged: only cards on.
        assertEquals(listOf(Setting.Cards(1)), cardRequests(on = false, saved = "Hilltop", typed = "Hilltop", turnOn = true))
    }

    @Test
    fun savingANameWhileOnSendsOnlyTheName() {
        assertEquals(listOf(Setting.CardName("Ridge")), cardRequests(on = true, saved = "Hilltop", typed = "Ridge", turnOn = true))
        assertEquals(emptyList<Setting>(), cardRequests(on = true, saved = "Hilltop", typed = "Hilltop", turnOn = true))
        // Emptied: the card carries no name.
        assertEquals(listOf(Setting.CardName("")), cardRequests(on = true, saved = "Hilltop", typed = "  ", turnOn = false))
    }

    @Test
    fun theSwitchSaysWhatItDoes() {
        assertEquals("Show my card to nodes nearby", context.getString(R.string.card_on))
        val explained = context.getString(R.string.card_on_explained)
        assertTrue(explained.contains("address"))
        assertTrue(explained.contains("every two hours"))
    }

    @Test
    fun aNodeHeardWithNoCardSaysWhyNothingCanBeDone() {
        val listed = heardOnlyText(context, cards = true, one = false)
        assertTrue(listed.startsWith("Your node hears these, but they haven't shared a card"))
        assertTrue(listed.contains("link or QR code"))
        val tapped = heardOnlyText(context, cards = true, one = true)
        assertTrue(tapped.contains("You can't message or add it from here."))
        // A node too old for cards: waiting for one is no way to meet anyone.
        assertFalse(heardOnlyText(context, cards = false, one = false).contains("card"))
        assertFalse(heardOnlyText(context, cards = false, one = true).contains("card"))
    }
}
