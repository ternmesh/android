// A node managed over the mesh, played by hand: what the Remote does when a request is not
// delivered, when the node has restarted and forgotten it, and after a setting the node restarts to
// apply. The specification's connection, in which every request is delivered, is in ConnectionTest.
package org.ternmesh.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteTest {
    private val bob = Address(ByteArray(Address.LENGTH) { 0xB0.toByte() })
    private var time = 0L
    private var nextId = 100L

    /** The `MANAGE`s the client's own node was asked to send, each queued at once. */
    private val sent = mutableListOf<Body.Manage>()
    private val remote = Remote(
        bob,
        manage = { body, then ->
            sent += body as Body.Manage
            then(Outcome.Answered(Body.Queued(nextId++)))
        },
        now = { time },
    )

    /** The last request sent, as the managed node reads it. */
    private fun last(): Frame = Codec.decode(sent.last().request)

    /** The managed node answers the last request with [body]. */
    private fun answer(body: Body, from: Address = bob) {
        val content = Managing.pack(listOf(Codec.encode(Frame(last().seq, body)))).single()
        remote.receive(Body.Managed(from, content.copyOfRange(1, content.size)))
    }

    @Test
    fun aRequestNotDeliveredGoesAgainUpToThreeTimesSpacedApart() {
        var result: Outcome? = null
        remote.submit(Body.Ping) { result = it }
        for (attempt in 1..Remote.TRIES) {
            assertEquals(attempt, sent.size)
            assertTrue(sent.all { it.request.contentEquals(sent[0].request) }, "the same request, under the same seq")
            remote.receive(Body.State(nextId - 1, MessageState.NOT_DELIVERED, 0, 0))
            time += Remote.LOST_GRACE_MS - 1
            remote.tick()
            assertEquals(attempt, sent.size, "not before the wait for a copy that arrived")
            time += 1
            remote.tick()
        }
        assertEquals(Remote.TRIES, sent.size)
        assertEquals(Outcome.NoAnswer, result)
        assertEquals(3, sent.map { it.ref }.toSet().size, "each a new message")
    }

    @Test
    fun anAnswerFromAnotherNodeIsIgnored() {
        var result: Outcome? = null
        remote.submit(Body.Ping) { result = it }
        answer(Body.Ok, from = Address(ByteArray(Address.LENGTH) { 7 }))
        assertNull(result)
        answer(Body.Ok)
        assertEquals(Outcome.Answered(Body.Ok), result)
    }

    @Test
    fun error6SaysHelloAgainAndAsksOnceMore() {
        var result: Outcome? = null
        remote.submit(Body.Ping) { result = it }
        answer(Body.Error(ErrorCode.HELLO_FIRST))
        assertEquals(Body.Hello(Companion.VERSION), last().body)
        answer(Body.Info(Companion.VERSION, "tern", "heltec-v3", "0.2.0", UpdateBy.LINK))
        assertEquals(Body.Ping, last().body)
        assertNull(result)
        answer(Body.Ok)
        assertEquals(Outcome.Answered(Body.Ok), result)
        assertEquals(Companion.VERSION, remote.agreed)
    }

    /** A setting the node applies without restarting is confirmed at once; a role or a power, once the node can answer again. */
    @Test
    fun aSettingOnTrialIsConfirmedOnceTheNodeCanAnswer() {
        remote.submit(Body.Set(Setting.Cards(1)))
        answer(Body.Ok)
        assertEquals(Body.Ping, last().body, "at once")
        answer(Body.Ok)
        assertNull(remote.trialUntil)

        remote.submit(Body.Set(Setting.Role(1)))
        remote.submit(Body.Sync(0))
        answer(Body.Ok)
        assertEquals(Companion.TRIAL_MS, remote.trialUntil)
        assertEquals(Body.Set(Setting.Role(1)), last().body, "nothing goes while the node restarts")
        assertEquals(Remote.SETTLE_MS, remote.nextDeadline)
        time = Remote.SETTLE_MS
        remote.tick()
        assertEquals(Body.Ping, last().body, "the PING first, then what was asked meanwhile")
        answer(Body.Ok)
        assertNull(remote.trialUntil)
        assertEquals(Body.Sync(0), last().body)
    }
}
