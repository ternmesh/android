// How a message went, and what a trace found, in the app's own words (version 18).
package org.ternmesh.app.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.ternmesh.app.node.RangePing
import org.ternmesh.app.node.RangeRow
import org.ternmesh.app.node.TraceRun
import org.ternmesh.app.node.Unsent
import org.ternmesh.companion.Address
import org.ternmesh.companion.Body
import org.ternmesh.companion.GroupId
import org.ternmesh.companion.MessageState
import org.ternmesh.companion.Records
import org.ternmesh.companion.TraceResult
import java.util.Locale

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RouteWordsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    // The vectors' first two addresses: RFC 8032's first two Ed25519 public keys.
    private val ada = Address.fromHex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")!!
    private val bob = Address.fromHex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")!!
    private val hut = GroupId(ByteArray(8) { 0x48 })
    private val relay = 0x1D2E3F40L
    private val names = mapOf(relay to "Hilltop")

    private val records = Records().apply {
        apply(Body.Self(ada, 0, "EU868", 14, 0))
        apply(Body.Contact(bob, 1, "Bob"))
        speaking = 18
    }
    private val bobs = records.routingIdOf(bob)!!

    init {
        Locale.setDefault(Locale.US)
    }

    private fun received(state: Int = MessageState.RECEIVED) = Body.Message(18, bob, 0, 0, state, 0, 0, "See you")
    private fun sent(state: Int) = Body.Message(17, bob, 0, 0, state, 0, 0, "Leaving now")
    private fun line(item: org.ternmesh.companion.Item, route: Body.Route?, n: Map<Long, String> = names) =
        routeLine(context, records, n, item, route)

    @Test
    fun receivedDirectOrRelayed() {
        assertEquals("Direct · SNR 6.5 dB", line(received(), Body.Route(18, 1, 26, -71, 0, 0, 0)))
        assertEquals("2 hops · SNR 4.8 dB", line(received(), Body.Route(18, 2, 19, -101, 0, 0, 0)))
        assertEquals("3 hops · SNR -9.5 dB", line(received(), Body.Route(18, 3, -38, -112, 0, 0, 0)))
        assertNull("nothing known", line(received(), Body.Route(18, 0, 0, 0, 0, 0, 0)))
        assertNull("no ROUTE: a node of version 17, or one that kept none", line(received(), null))
    }

    @Test
    fun aGroupMessageCameAtMostThatFar() {
        val group = Body.GroupMessage(19, hut, bobs, 0, 0, MessageState.RECEIVED, 0, 0, "Hut's empty")
        assertEquals("≤2 hops · SNR 4.8 dB", line(group, Body.Route(19, 2, 19, -101, 0, 0, 0)))
        assertEquals("Direct · SNR -2.3 dB", line(group, Body.Route(19, 1, -9, -117, 0, 0, 0)))
        val (facts, notes) = routeFacts(context, records, names, group, Body.Route(19, 2, 19, -101, 0, 0, 0))
        assertEquals("At most 2 hops: 2 or 1", facts.first().second)
        assertTrue(notes.first().startsWith("A group message's writer hides how far off it is"))
        val mine = group.copy(from = 0, state = MessageState.SENT)
        assertEquals("Went out 2 times", line(mine, Body.Route(19, 0, 0, 0, 0, 2, 0)))
    }

    @Test
    fun sentViaARelayOrDirect() {
        assertEquals("Delivered via Hilltop · 2 hops · 19.6 s", line(sent(MessageState.DELIVERED), Body.Route(17, 2, 22, -98, relay, 2, 19_640)))
        assertEquals("Delivered via 1d2e 3f40 · 2 hops · 3.1 s", line(sent(MessageState.DELIVERED), Body.Route(17, 2, 22, -98, relay, 1, 3_120), emptyMap()))
        assertEquals("Delivered direct · 1.2 s", line(sent(MessageState.DELIVERED), Body.Route(17, 1, 30, -60, bobs, 1, 1_200)))
        assertEquals("Delivered direct · 2 hops · 1.2 s", line(sent(MessageState.DELIVERED), Body.Route(17, 2, 30, -60, bobs, 1, 1_200)))
        assertEquals("Sent via Hilltop", line(sent(MessageState.SENT), Body.Route(17, 0, 0, 0, relay, 1, 0)))
        assertEquals("Sent via Hilltop · try 2", line(sent(MessageState.SENT), Body.Route(17, 0, 0, 0, relay, 2, 0)))
        assertEquals("Sent direct", line(sent(MessageState.WAITING), Body.Route(17, 0, 0, 0, bobs, 1, 0)))
        assertEquals("Last tried via Hilltop · 4 tries", line(sent(MessageState.NOT_DELIVERED), Body.Route(17, 0, 0, 0, relay, 4, 0)))
        assertNull("gone nowhere yet", line(sent(MessageState.WAITING), Body.Route(17, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun theDetailsSayEveryField() {
        val (facts, notes) = routeFacts(context, records, names, sent(MessageState.DELIVERED), Body.Route(17, 2, 22, -98, relay, 2, 19_640))
        assertEquals(
            listOf(
                "First went to" to "Hilltop (1d2e3f40)",
                "Tries on the air" to "2",
                "Acknowledgement came back" to "2 hops",
                "Signal-to-noise of the last hop" to "5.5 dB",
                "Signal strength of the last hop" to "-98 dBm",
                "Round trip" to "19.6 s",
            ),
            facts,
        )
        assertTrue(notes.single().startsWith("The hops and signal are the acknowledgement's"))
        val (came, why) = routeFacts(context, records, names, received(), Body.Route(18, 3, -38, -112, 0, 0, 0))
        assertEquals("3 hops: 2 relays passed it on", came.first().second)
        assertTrue(why.last().startsWith("No message names the relays"))
        assertEquals("Straight to Bob", routeFacts(context, records, names, sent(MessageState.SENT), Body.Route(17, 0, 0, 0, bobs, 1, 0)).first.first().second)
    }

    @Test
    fun namesYourNodeContactsRelaysAndIds() {
        assertEquals("your node", routingName(context, records, names, records.routingIdOf(ada)!!))
        assertEquals("Bob", routingName(context, records, names, bobs))
        assertEquals("Hilltop", routingName(context, records, names, relay))
        assertEquals("3031 3233", routingLabel(context, records, names, 0x30313233))
    }

    @Test
    fun aTraceInWords() {
        val entries = byteArrayOf(0x1D, 0x2E, 0x3F, 0x40, 24) + idBytes(bobs) + byteArrayOf(40) + byteArrayOf(0x1D, 0x2E, 0x3F, 0x40, 30)
        val answered = Body.Traced(20, bobs, TraceResult.ANSWERED, 2, 3_120, 26, entries)
        assertEquals("Answered by Bob", traceResultText(context, records, names, answered, "Bob"))
        assertEquals("2 hops out · 2 hops back · 3.1 s", traceSummary(context, answered))
        val keeper = Body.Traced(20, bobs, TraceResult.KEEPER, 1, 900, 26, byteArrayOf(0x1D, 0x2E, 0x3F, 0x40, 24))
        assertEquals(
            "Answered by Hilltop, which keeps Bob's messages while it sleeps: that's as far as they go until it wakes",
            traceResultText(context, records, names, keeper, "Bob"),
        )
        val stuck = Body.Traced(20, bobs, TraceResult.NO_ROUTE, 1, 900, 26, byteArrayOf(0x1D, 0x2E, 0x3F, 0x40, 24))
        assertEquals("Got as far as Hilltop, which has no route to Bob", traceResultText(context, records, names, stuck, "Bob"))
        val none = Body.Traced(21, relay, TraceResult.NOT_ANSWERED, 0, 0, 0, ByteArray(0))
        assertEquals(
            "No answer in time. Hilltop may be out of reach, or a relay on the way may not pass traces on.",
            traceResultText(context, records, names, none, "Hilltop"),
        )
        val never = Body.Traced(22, 0x30313233, TraceResult.NOT_BEGUN, 0, 0, 0, ByteArray(0))
        assertEquals("Not begun: your node has no route to 3031 3233", traceResultText(context, records, names, never, "3031 3233"))
    }

    @Test
    fun whereATraceIs() {
        assertEquals("Handing it to your node…", traceStepText(context, TraceRun(bobs)))
        assertEquals("Finding a route…", traceStepText(context, TraceRun(bobs, 20, MessageState.WAITING, 1)))
        assertEquals("Waiting for the airtime budget · about 12 s", traceStepText(context, TraceRun(bobs, 20, MessageState.WAITING, 4, 12)))
        assertEquals("Out: waiting for the answer…", traceStepText(context, TraceRun(bobs, 20, MessageState.SENT)))
    }

    @Test
    fun aRangePingInWords() {
        val ping = RangePing(3, 0, "range #3", null, 16, id = 17)
        assertEquals(
            "Delivered · 2 hops · SNR 5.5 dB · -98 dBm · 19.6 s · via Hilltop",
            rangeRowText(context, records, names, RangeRow(ping, MessageState.DELIVERED, Body.Route(17, 2, 22, -98, relay, 2, 19_640))),
        )
        assertEquals("Sent · via Hilltop", rangeRowText(context, records, names, RangeRow(ping, MessageState.SENT, Body.Route(17, 0, 0, 0, relay, 1, 0))))
        assertEquals("Delivered", rangeRowText(context, records, names, RangeRow(ping, MessageState.DELIVERED, null)))
        val away = ping.copy(id = null, unsent = Unsent.NOT_CONNECTED)
        assertEquals("Not sent: node not connected", rangeRowText(context, records, names, RangeRow(away, null, null)))
    }

    private fun idBytes(id: Long) = ByteArray(4) { i -> (id shr (24 - 8 * i)).toByte() }
}
