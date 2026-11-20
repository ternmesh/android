// What a client says a request over the mesh waits for, from its message's STATE, and whether the
// managed node is known to sleep.
package org.ternmesh.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManageStepTest {
    @Test
    fun eachStateAndReasonHasItsStep() {
        assertEquals(ManageStep.Starting, ManageStep.of(null))
        assertEquals(ManageStep.Waiting, ManageStep.of(MessageState.WAITING, 0, 0))
        assertEquals(ManageStep.FindingRoute, ManageStep.of(MessageState.WAITING, 1, 0))
        assertEquals(ManageStep.FirstContact, ManageStep.of(MessageState.WAITING, 2, 0))
        assertEquals(ManageStep.Airtime(3, 40), ManageStep.of(MessageState.WAITING, 3, 40))
        assertEquals(ManageStep.Airtime(4, 0), ManageStep.of(MessageState.WAITING, 4, 0))
        assertEquals(ManageStep.Airtime(5, 2), ManageStep.of(MessageState.WAITING, 5, 2))
        assertEquals(ManageStep.Waiting, ManageStep.of(MessageState.WAITING, 9, 0), "a reason not known")
        assertEquals(ManageStep.Sent, ManageStep.of(MessageState.SENT))
        assertEquals(ManageStep.Delivered, ManageStep.of(MessageState.DELIVERED))
        assertEquals(ManageStep.NotDelivered, ManageStep.of(MessageState.NOT_DELIVERED))
    }

    /** Its own SLEEP first, as what it does now; then its contact's wake; and nothing guessed. */
    @Test
    fun whetherItSleeps() {
        assertEquals(900, ManageStep.sleeps(Body.Sleep(900, 5, Sleeping.ASLEEP, 7, 0), null))
        assertEquals(900, ManageStep.sleeps(Body.Sleep(900, 5, Sleeping.WAITING, 0, 0), null), "listening for a keeper, but to sleep again")
        assertEquals(0, ManageStep.sleeps(Body.Sleep(900, 5, Sleeping.AWAKE, 0, 0), null), "a relay never sleeps")
        assertEquals(0, ManageStep.sleeps(Body.Sleep(0, 5, Sleeping.AWAKE, 0, 0), 0))
        // What its contact last said, news whenever it changes, before an older sync's.
        assertEquals(600, ManageStep.sleeps(Body.Sleep(0, 5, Sleeping.AWAKE, 0, 0), 600))
        assertEquals(600, ManageStep.sleeps(null, 600))
        assertNull(ManageStep.sleeps(null, 0), "a contact's 0 may be one that said nothing")
        assertNull(ManageStep.sleeps(null, null))
    }

    /** A sleeping node is what it waits for until delivery, not after. */
    @Test
    fun theWakeMattersUntilDelivery() {
        assertTrue(ManageStep.waitsForWake(ManageStep.Sent))
        assertTrue(ManageStep.waitsForWake(ManageStep.FirstContact))
        assertFalse(ManageStep.waitsForWake(ManageStep.Delivered))
        assertFalse(ManageStep.waitsForWake(ManageStep.NotDelivered))
    }

    @Test
    fun theWaitForASleepingNode() {
        assertEquals(Companion.MESH_ANSWER_WAIT_MS, Remote.waitFor(0, 5))
        assertEquals(Companion.MESH_ANSWER_WAIT_MS + 905_000, Remote.waitFor(900, 5))
        assertEquals(600_000L, Remote.waitFor(null, 0), "not known: ten minutes")
    }

    /** Delivered to a sleeping node's keeper, it is held until the node wakes; to one not known to sleep, after 20 s, it may be asleep. */
    @Test
    fun heldForASleepingNode() {
        assertEquals(ManageStep.Held(300), ManageStep.of(MessageState.DELIVERED, wake = 300))
        assertEquals(ManageStep.Held(300), ManageStep.of(MessageState.DELIVERED, wake = 300, sinceDeliveredMs = 0))
        assertEquals(ManageStep.Delivered, ManageStep.of(MessageState.DELIVERED, wake = null, sinceDeliveredMs = 19_999))
        assertEquals(ManageStep.MaybeAsleep, ManageStep.of(MessageState.DELIVERED, wake = null, sinceDeliveredMs = 20_000))
        assertEquals(ManageStep.MaybeAsleep, ManageStep.of(MessageState.DELIVERED, wake = 0, sinceDeliveredMs = 60_000))
        assertEquals(ManageStep.Sent, ManageStep.of(MessageState.SENT, wake = 300, sinceDeliveredMs = 60_000), "only once delivered")
        assertFalse(ManageStep.waitsForWake(ManageStep.Held(300)))
        assertFalse(ManageStep.waitsForWake(ManageStep.MaybeAsleep))
    }

    /**
     * Taken by the app's own node, and no STATE yet: a keeper holding it for a sleeping node says
     * nothing until the node wakes. Known to sleep, it is held; not known, after 20 s it may be asleep.
     */
    @Test
    fun queuedAndNoStateYet() {
        assertEquals(ManageStep.Starting, ManageStep.of(null, wake = 300), "not yet taken by the app's node")
        assertEquals(ManageStep.Held(300), ManageStep.of(null, wake = 300, sinceQueuedMs = 0))
        assertEquals(ManageStep.Queued, ManageStep.of(null, wake = null, sinceQueuedMs = 19_999))
        assertEquals(ManageStep.MaybeAsleep, ManageStep.of(null, wake = null, sinceQueuedMs = 20_000))
        assertEquals(ManageStep.MaybeAsleep, ManageStep.of(null, wake = 0, sinceQueuedMs = 300_000))
        // Once the node says where it is, its STATE is what counts.
        assertEquals(ManageStep.Sent, ManageStep.of(MessageState.SENT, wake = null, sinceQueuedMs = 300_000))
        // A contact's wake, with no sync yet, is enough to know it sleeps.
        assertEquals(ManageStep.Held(300), ManageStep.of(null, wake = ManageStep.sleeps(null, 300), sinceQueuedMs = 1_000))
    }
}
