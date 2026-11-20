// The range test's log: numbered pings, what each became from the records, and the CSV it exports.
package org.ternmesh.app.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ternmesh.companion.Address
import org.ternmesh.companion.Body
import org.ternmesh.companion.MessageState
import org.ternmesh.companion.Records
import java.time.ZoneOffset

class RangeTestTest {
    private val bob = Address(ByteArray(32) { 0xB0.toByte() })
    private val carol = Address(ByteArray(32) { 0xCA.toByte() })
    private val start = 1_790_000_000_000L

    private fun sent(id: Long, text: String, state: Int, to: Address = bob) = Body.Message(id, to, 0, 0, state, 0, 0, text)

    @Test
    fun pingsAreNumberedAndDueEachInterval() {
        val t = RangeTest(bob, 30_000, start)
        assertEquals("the first goes at once", start, t.nextAt)
        val first = t.next(start, PhoneFix(45.5, -66.1, 4f, 1), after = 16)
        assertEquals("range #1", first.text)
        assertEquals(start + 30_000, t.nextAt)
        val second = t.next(start + 30_000, null, after = 17, unsent = Unsent.NOT_CONNECTED)
        assertEquals(2, second.n)
        assertEquals(Unsent.NOT_CONNECTED, t.pings[1].unsent)
        assertEquals(start + 60_000, t.nextAt)
        assertThrows(IllegalArgumentException::class.java) { RangeTest(bob, 29_000, start) }
        assertThrows(IllegalArgumentException::class.java) { RangeTest(bob, 61_000, start) }
    }

    @Test
    fun whatEachBecameIsReadFromTheRecords() {
        val t = RangeTest(bob, 30_000, start)
        t.next(start, null, after = 16)
        t.queued(1, 17)
        t.next(start + 30_000, null, after = 17)
        t.queued(2, 18)
        t.next(start + 60_000, null, after = 18)
        t.unsent(3, Unsent.REFUSED)
        val r = Records().apply {
            apply(sent(17, "range #1", MessageState.DELIVERED))
            apply(Body.Route(17, 2, 22, -98, 0x1D2E3F40, 2, 19_640))
            apply(sent(18, "range #2", MessageState.NOT_DELIVERED))
        }
        val rows = rangeRows(t.pings, r)
        assertTrue(rows[0].delivered)
        assertEquals(2, rows[0].route?.hops)
        assertEquals(MessageState.NOT_DELIVERED, rows[1].state)
        assertNull(rows[2].state)
        assertEquals("one delivered of the two that went", 1 to 2, rangeTally(rows))
        assertEquals(listOf("delivered", "not_delivered", "refused"), rows.map(::rangeResult))
    }

    @Test
    fun aPingTheNodeDidNotAnswerForIsFoundByItsText() {
        val t = RangeTest(bob, 30_000, start)
        t.next(start, null, after = 20)
        t.unsent(1, Unsent.UNANSWERED)
        val r = Records().apply {
            // The same text from an earlier test, to another contact, and one received: none is it.
            apply(sent(12, "range #1", MessageState.DELIVERED))
            apply(sent(21, "range #1", MessageState.SENT, to = carol))
            apply(Body.Message(22, bob, 0, 0, MessageState.RECEIVED, 0, 0, "range #1"))
        }
        assertFalse(t.resolve(r))
        r.apply(sent(23, "range #1", MessageState.SENT))
        assertTrue(t.resolve(r))
        assertEquals(23L, t.pings[0].id)
        assertNull(t.pings[0].unsent)
        // An answer that comes after does not undo it.
        t.unsent(1, Unsent.UNANSWERED)
        assertEquals(23L, t.pings[0].id)
    }

    @Test
    fun theCsvHasAHeaderAndALineForEachPing() {
        val t = RangeTest(bob, 45_000, start)
        t.next(start, PhoneFix(45.123456789, -66.5, 4.4f, 2), after = 16)
        t.queued(1, 17)
        t.next(start + 45_000, null, after = 17, unsent = Unsent.NOT_CONNECTED)
        val r = Records().apply {
            apply(sent(17, "range #1", MessageState.DELIVERED))
            apply(Body.Route(17, 2, -38, -112, 0x1D2E3F40, 3, 19_640))
        }
        val csv = rangeCsv(rangeRows(t.pings, r), bob, { if (it == 0x1D2E3F40L) "Hilltop, north" else null }, ZoneOffset.UTC)
        val lines = csv.trimEnd().lines()
        assertEquals("n,time,lat,lon,accuracy_m,fix_age_s,to,message_id,result,hops,snr_db,rssi_dbm,round_trip_s,tries,via_id,via", lines[0])
        assertEquals(
            "1,2026-09-21T14:13:20Z,45.1234568,-66.5000000,4,2,b0b0b0b0,17,delivered,2,-9.50,-112,19.640,3,1d2e3f40,\"Hilltop, north\"",
            lines[1],
        )
        assertEquals("2,2026-09-21T14:14:05Z,,,,,b0b0b0b0,,not_connected,,,,,,,", lines[2])
    }
}
