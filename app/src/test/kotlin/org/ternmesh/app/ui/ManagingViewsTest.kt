// The check of whether this node owns another, and the banner, drawn from the repository's state
// alone: as an activity made again draws them, with nothing remembered of the one before.
package org.ternmesh.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.ternmesh.app.node.Managed
import org.ternmesh.app.node.NodeState
import org.ternmesh.companion.Address
import org.ternmesh.companion.Body
import org.ternmesh.companion.MessageState
import org.ternmesh.companion.Outcome
import org.ternmesh.companion.Records

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ManagingViewsTest {
    @get:Rule
    val compose = createComposeRule()

    private val hilltop = Address(ByteArray(32) { 0x3D })
    private val records = Records().apply { contacts[hilltop] = Body.Contact(hilltop, 1, "Hilltop") }

    private fun state(m: Managed) = NodeState(records = records, managed = m)

    /** A check on its way, its dialog open: what it waits for, and Cancel; tapping outside only hides it. */
    @Test
    fun aCheckOnItsWayShowsItsStep() {
        var hidden = 0
        var cancelled = 0
        val m = Managed(hilltop, checking = true, dialog = true, asking = Body.Hello(17), attempt = 1, carried = MessageState.WAITING, reason = 2)
        compose.setContent { TernTheme { CheckOwnerDialog(state(m), hilltop, hide = { hidden++ }, cancel = { cancelled++ }, retry = {}, manage = {}) } }
        compose.onNodeWithText("Making first contact with Hilltop…").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, cancelled)
        assertEquals(0, hidden)
    }

    /** Its outcome, when the user comes back: an owner, with Manage. */
    @Test
    fun anOwnerIsOfferedManage() {
        var managed = 0
        val m = Managed(hilltop, checking = true, dialog = true, owner = true)
        compose.setContent { TernTheme { CheckOwnerDialog(state(m), hilltop, hide = {}, cancel = {}, retry = {}, manage = { managed++ }) } }
        compose.onNodeWithText("You're an owner of Hilltop.").assertIsDisplayed()
        compose.onNodeWithText("Manage").performClick()
        assertEquals(1, managed)
    }

    /** No answer: said so, and it can be asked again. */
    @Test
    fun noAnswerCanBeAskedAgain() {
        var retried = 0
        val m = Managed(hilltop, checking = true, dialog = true, failed = Outcome.NoAnswer)
        compose.setContent { TernTheme { CheckOwnerDialog(state(m), hilltop, hide = {}, cancel = {}, retry = { retried++ }, manage = {}) } }
        compose.onNodeWithText("No answer. Hilltop may be asleep or out of reach, or you're not an owner.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retried)
    }

    /** Its dialog closed, a check on its way is a banner, which opens it again. */
    @Test
    fun aHiddenCheckIsABanner() {
        var opened: Managed? = null
        val m = Managed(hilltop, checking = true, dialog = false, asking = Body.Hello(17))
        compose.setContent { TernTheme { ManagingBanner(state(m)) { opened = it } } }
        compose.onNodeWithText("Checking whether you own Hilltop…").performClick()
        assertEquals(m, opened)
    }

    /** And one that ended unseen says how. */
    @Test
    fun anUnseenOutcomeIsABanner() {
        val m = Managed(hilltop, checking = true, dialog = false, failed = Outcome.NoAnswer)
        compose.setContent { TernTheme { ManagingBanner(state(m)) {} } }
        compose.onNodeWithText("No answer from Hilltop.").assertIsDisplayed()
    }

    /**
     * A check of a node whose contact says it sleeps, taken by the app's node and with no STATE yet,
     * as a keeper holding it leaves it: held until it wakes, not still being handed over.
     */
    @Test
    fun aCheckHeldForASleepingNodeSaysSo() {
        val m = Managed(
            hilltop, checking = true, dialog = true, asking = Body.Hello(17), attempt = 1,
            wake = 300, queuedAt = android.os.SystemClock.elapsedRealtime(),
        )
        compose.setContent { TernTheme { CheckOwnerDialog(state(m), hilltop, hide = {}, cancel = {}, retry = {}, manage = {}) } }
        compose.onNodeWithText("Held until Hilltop wakes (every 5 min)…").assertIsDisplayed()
    }

    /** Not yet taken by the app's node: still being handed over. */
    @Test
    fun notYetQueuedIsBeingHandedOver() {
        val m = Managed(hilltop, checking = true, dialog = true, asking = Body.Hello(17), attempt = 1, wake = 300)
        compose.setContent { TernTheme { CheckOwnerDialog(state(m), hilltop, hide = {}, cancel = {}, retry = {}, manage = {}) } }
        compose.onNodeWithText("Handing it to your node…").assertIsDisplayed()
    }
}
