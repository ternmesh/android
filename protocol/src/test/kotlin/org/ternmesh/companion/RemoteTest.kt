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

    /** `ERROR` 15: not an owner. Nothing is tried again, nothing waiting goes, and the app hears it is not one. */
    @Test
    fun error15StopsEverythingAndSaysNotAnOwner() {
        val events = mutableListOf<RemoteEvent>()
        remote.onEvent = { events += it }
        var opened: Outcome? = null
        var asked: Outcome? = null
        remote.open { opened = it }
        remote.submit(Body.Ping) { asked = it }
        assertEquals(listOf<Body>(Body.Hello(Companion.VERSION), Body.Sync(0)), sent.map { Codec.decode(it.request).body })
        // The HELLO's answer: the SYNC that went with it, and the PING that waited, fail with it.
        val content = Managing.pack(listOf(Codec.encode(Frame(Codec.decode(sent[0].request).seq, Body.Error(ErrorCode.NOT_AN_OWNER))))).single()
        remote.receive(Body.Managed(bob, content.copyOfRange(1, content.size)))
        assertEquals(Outcome.Refused(ErrorCode.NOT_AN_OWNER), opened)
        assertEquals(Outcome.Refused(ErrorCode.NOT_AN_OWNER), asked)
        assertEquals(2, sent.size, "nothing more went")
        assertTrue(!remote.busy)
        assertEquals(listOf<RemoteEvent>(RemoteEvent.Owner(false)), events.filterIsInstance<RemoteEvent.Owner>())
        time += Companion.MESH_ANSWER_WAIT_MS * 10
        remote.tick()
        assertEquals(2, sent.size)
    }

    /** Any answer at all, even a refusal, comes only to an owner. */
    @Test
    fun anyAnswerSaysAnOwner() {
        val events = mutableListOf<RemoteEvent>()
        remote.onEvent = { events += it }
        remote.submit(Body.Hello(Companion.VERSION))
        answer(Body.Error(ErrorCode.NOT_OVER_THE_MESH))
        assertEquals(listOf<RemoteEvent>(RemoteEvent.Owner(true)), events.filterIsInstance<RemoteEvent.Owner>())
    }

    /** The message's STATE comes through with what it waits for. */
    @Test
    fun whatItWaitsForIsPassedOn() {
        val events = mutableListOf<RemoteEvent>()
        remote.onEvent = { events += it }
        remote.submit(Body.Ping)
        remote.receive(Body.State(nextId - 1, MessageState.WAITING, 2, 30))
        assertEquals(RemoteEvent.Carried(MessageState.WAITING, 2, 30), events.last())
    }

    /** To a node that sleeps, each wait is longer by a sleep and a listen; a sync saying it is awake makes it the bare wait again. */
    @Test
    fun aSleepingNodeIsWaitedForLonger() {
        remote.wake = 900
        remote.listen = 5
        var result: Outcome? = null
        remote.submit(Body.Ping) { result = it }
        val wait = Companion.MESH_ANSWER_WAIT_MS + 905_000
        assertEquals(wait, remote.answerWaitMs)
        assertEquals(wait, remote.deadline)
        remote.receive(Body.State(nextId - 1, MessageState.SENT, 0, 0))
        time = Companion.MESH_ANSWER_WAIT_MS + 1
        remote.tick()
        assertNull(result, "not given up after the bare two minutes")
        remote.receive(Body.State(nextId - 1, MessageState.DELIVERED, 0, 0))
        assertEquals(time + wait, remote.deadline)
        answer(Body.Ok)
        assertEquals(Outcome.Answered(Body.Ok), result)

        remote.sync()
        answer(Body.Sleep(900, 5, Sleeping.AWAKE, 0, 0))
        answer(Body.Synced(1))
        assertEquals(0, remote.wake)
        assertEquals(Companion.MESH_ANSWER_WAIT_MS, remote.answerWaitMs)
    }

    /** Whether the node sleeps not known, a request waits ten minutes from each step: a keeper may hold it that long. */
    @Test
    fun notKnowingWhetherItSleepsWaitsTenMinutes() {
        assertNull(remote.wake)
        assertEquals(Remote.UNKNOWN_WAIT_MS, remote.answerWaitMs)
        var result: Outcome? = null
        remote.submit(Body.Ping) { result = it }
        remote.receive(Body.State(nextId - 1, MessageState.DELIVERED, 0, 0))
        time = Remote.UNKNOWN_WAIT_MS - 1
        remote.tick()
        assertNull(result)
        time = Remote.UNKNOWN_WAIT_MS
        remote.tick()
        assertEquals(Outcome.NoAnswer, result)
    }

    /** The app's own node taking the request is passed on: it may be all that is heard while a keeper holds it. */
    @Test
    fun queuedIsPassedOn() {
        val events = mutableListOf<RemoteEvent>()
        remote.onEvent = { events += it }
        remote.submit(Body.Hello(Companion.VERSION))
        assertEquals(listOf(RemoteEvent.Trying(1), RemoteEvent.Queued), events)
    }

    /** The managed node answers the request [n]th sent (from 0) with [body]. */
    private fun answerTo(n: Int, body: Body) {
        val content = Managing.pack(listOf(Codec.encode(Frame(Codec.decode(sent[n].request).seq, body)))).single()
        remote.receive(Body.Managed(bob, content.copyOfRange(1, content.size)))
    }

    private val info = Body.Info(Companion.VERSION, "tern", "heltec-v3", "0.2.0", UpdateBy.LINK)

    /** Opening sends HELLO and SYNC at once, to ride one wake; their answers are told apart by seq, in either order. */
    @Test
    fun openingSendsHelloAndSyncTogether() {
        var opened: Outcome? = null
        remote.open { opened = it }
        assertEquals(listOf<Body>(Body.Hello(Companion.VERSION), Body.Sync(0)), sent.map { Codec.decode(it.request).body })
        // The SYNCED first, then the INFO: answers can pass each other.
        answerTo(1, Body.Synced(0))
        assertEquals(Outcome.Answered(Body.Synced(0)), opened)
        answerTo(0, info)
        assertEquals(Companion.VERSION, remote.agreed)
        assertTrue(!remote.busy)
    }

    /** Once the node has answered a HELLO, opening again asks only for its state. */
    @Test
    fun aHelloAnsweredIsNotSaidAgain() {
        remote.submit(Body.Hello(Companion.VERSION))
        answer(info)
        remote.close()
        sent.clear()
        var opened: Outcome? = null
        remote.open { opened = it }
        assertEquals(listOf<Body>(Body.Sync(0)), sent.map { Codec.decode(it.request).body })
        answer(Body.Synced(0))
        assertEquals(Outcome.Answered(Body.Synced(0)), opened)
    }

    /** A SYNC that arrived before the HELLO that went with it is answered ERROR 6: asked again once the HELLO is answered, with no second HELLO. */
    @Test
    fun aSyncAheadOfItsHelloIsAskedAgainAfter() {
        var opened: Outcome? = null
        remote.open { opened = it }
        answerTo(1, Body.Error(ErrorCode.HELLO_FIRST))
        assertEquals(2, sent.size, "nothing more yet: the HELLO is on its way")
        answerTo(0, info)
        assertEquals(3, sent.size)
        assertEquals(Body.Sync(0), last().body)
        answer(Body.Synced(0))
        assertEquals(Outcome.Answered(Body.Synced(0)), opened)
        assertEquals(1, sent.count { Codec.decode(it.request).body is Body.Hello })
    }

    /** Where order matters, one at a time: the PING that confirms a SET goes only once the SET is answered. */
    @Test
    fun aSetAndItsPingGoOneAtATime() {
        remote.submit(Body.Hello(Companion.VERSION))
        answer(info)
        remote.submit(Body.Set(Setting.Cards(1)))
        remote.submit(Body.Sync(0))
        assertEquals(Body.Set(Setting.Cards(1)), last().body)
        assertEquals(2, sent.size)
        answer(Body.Ok)
        assertEquals(Body.Ping, last().body)
        assertEquals(3, sent.size)
    }
}

