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
import org.ternmesh.app.node.Managed
import org.ternmesh.app.node.NodeState
import org.ternmesh.companion.Address
import org.ternmesh.companion.Body
import org.ternmesh.companion.ErrorCode
import org.ternmesh.companion.ManageStep
import org.ternmesh.companion.MessageState
import org.ternmesh.companion.Outcome
import org.ternmesh.companion.Records
import org.ternmesh.companion.Setting

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

    /** Delivered to a sleeping node's keeper, it is held until the node wakes; not known to sleep, after 20 s it may be asleep. */
    @Test
    fun heldForASleepingNode() {
        assertEquals("Held until Hilltop wakes (every 5 min)…", manageStepText(context, ManageStep.of(MessageState.DELIVERED, wake = 300), "Hilltop"))
        assertEquals(
            "Waiting for Hilltop. It may be asleep.",
            manageStepText(context, ManageStep.of(MessageState.DELIVERED, sinceDeliveredMs = 25_000), "Hilltop"),
        )
        assertEquals("Delivered: waiting for Hilltop's answer…", manageStepText(context, ManageStep.of(MessageState.DELIVERED, sinceDeliveredMs = 5_000), "Hilltop"))
    }

    /** What a notification says a check came to. */
    @Test
    fun checkNotifications() {
        assertEquals("You're an owner of Hilltop. Tap to manage it.", checkResultText(context, "Hilltop", true, null))
        assertEquals("You're not an owner of Hilltop.", checkResultText(context, "Hilltop", false, Outcome.Refused(ErrorCode.NOT_AN_OWNER)))
        assertEquals("No answer from Hilltop.", checkResultText(context, "Hilltop", null, Outcome.NoAnswer))
        assertNull(checkResultText(context, "Hilltop", null, null))
        assertNull(checkResultText(context, "Hilltop", null, Outcome.Closed))
    }

    /** What a notification says a request came to, once the user has left the Manage screen. */
    @Test
    fun manageNotifications() {
        val ok = Outcome.Answered(Body.Ok)
        assertEquals("Card name set on Hilltop.", manageDoneText(context, "Hilltop", Body.Set(Setting.CardName("Mast")), ok))
        assertEquals("Sleep set on Hilltop.", manageDoneText(context, "Hilltop", Body.Set(Setting.Wake(600)), ok))
        assertEquals("Owners changed on Hilltop.", manageDoneText(context, "Hilltop", Body.Owner(Address(ByteArray(32) { 1 }), 1), ok))
        assertEquals("Hilltop is restarting.", manageDoneText(context, "Hilltop", Body.Restart, ok))
        assertEquals("No answer from Hilltop.", manageDoneText(context, "Hilltop", Body.Set(Setting.Power(14)), Outcome.NoAnswer))
        assertEquals(
            "You're not an owner of Hilltop. Its owner can add you.",
            manageDoneText(context, "Hilltop", Body.Restart, Outcome.Refused(ErrorCode.NOT_AN_OWNER)),
        )
        assertNull(manageDoneText(context, "Hilltop", Body.Restart, Outcome.Closed))
    }

    /** The service's notification, and the banner, while a check or a request is on its way. */
    @Test
    fun whileOnItsWay() {
        val hilltop = Address(ByteArray(32) { 0x3D })
        val records = Records().apply { contacts[hilltop] = Body.Contact(hilltop, 1, "Hilltop") }
        val asking = NodeState(records = records, managed = Managed(hilltop, asking = Body.Hello(17), checking = true))
        assertEquals("Checking whether you own Hilltop…", managingText(context, asking))
        assertEquals("Waiting for Hilltop to answer…", managingText(context, asking.copy(managed = asking.managed!!.copy(checking = false))))
        assertNull(managingText(context, asking.copy(managed = asking.managed!!.copy(asking = null))))
        assertNull(managingText(context, NodeState(records = records)))
    }
}
