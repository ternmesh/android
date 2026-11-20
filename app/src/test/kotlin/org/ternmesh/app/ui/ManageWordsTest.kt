// What the Manage screen, and the check of whether this node is an owner, say a request over the
// mesh waits for, from its message's STATE, in the app's own words.
package org.ternmesh.app.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.ternmesh.companion.ErrorCode
import org.ternmesh.companion.ManageStep
import org.ternmesh.companion.MessageState
import org.ternmesh.companion.Outcome

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ManageWordsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun step(state: Int?, reason: Int = 0, wait: Int = 0) =
        manageStepText(context, ManageStep.of(state, reason, wait), "Hilltop")

    @Test
    fun eachStateSaysWhatItWaitsFor() {
        assertEquals("Making first contact with Hilltop…", step(MessageState.WAITING, 2))
        assertEquals("Finding a route…", step(MessageState.WAITING, 1))
        // The air, as a message waiting for it says.
        assertEquals("Waiting for the region's transmit limit · about 40 s", step(MessageState.WAITING, 3, 40))
        assertEquals("Waiting for the airtime budget", step(MessageState.WAITING, 4))
        assertEquals("Waiting for the radio is busy", step(MessageState.WAITING, 5))
        assertEquals("Waiting to go…", step(MessageState.WAITING, 0))
        assertEquals("Sent: on its way to Hilltop…", step(MessageState.SENT))
        assertEquals("Delivered: waiting for Hilltop's answer…", step(MessageState.DELIVERED))
        assertEquals("Not delivered. Trying again…", step(MessageState.NOT_DELIVERED))
        assertEquals("Handing it to your node…", step(null))
    }

    @Test
    fun aSleepingNodeSaysHowLong() {
        assertEquals("Hilltop is asleep: it wakes every 15 min, so this can take up to 15 min.", manageAsleepText(context, "Hilltop", 900))
        assertEquals("Hilltop is asleep: it wakes every 6 min, so this can take up to 6 min.", manageAsleepText(context, "Hilltop", 330))
        assertNull(manageAsleepText(context, "Hilltop", null))
        assertNull(manageAsleepText(context, "Hilltop", 0))
    }

    @Test
    fun notAnOwnerAndNoAnswer() {
        assertEquals("You're not an owner of Hilltop. Its owner can add you.", manageOutcomeText(context, "Hilltop", Outcome.Refused(ErrorCode.NOT_AN_OWNER)))
        assertEquals(
            "No answer. Hilltop may be asleep or out of reach, or you're not an owner.",
            manageOutcomeText(context, "Hilltop", Outcome.NoAnswer),
        )
        // A code the app does not know stays a plain refusal.
        assertEquals(outcomeText(context, Outcome.Refused(99)), manageOutcomeText(context, "Hilltop", Outcome.Refused(99)))
    }
}
