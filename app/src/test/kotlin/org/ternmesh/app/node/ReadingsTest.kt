// The battery and airtime the app keeps over time: what is kept, what goes, and the file.
package org.ternmesh.app.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ternmesh.app.ui.ChartPoint
import org.ternmesh.app.ui.inSpan
import org.ternmesh.app.ui.periodText
import org.ternmesh.app.ui.readingsWaited
import org.ternmesh.companion.Body

class ReadingsTest {
    private val t0 = 1_790_000_000_000L

    @Test
    fun aChangeIsKeptAtOnceAndTheSameValueOnlyAfterAWhile() {
        var r = Readings().with(Body.Power(4100, 90, 0), t0)
        r = r.with(Body.Power(4100, 90, 0), t0 + 10_000)
        assertEquals("the same, soon after, is not kept again", 1, r.power.size)
        r = r.with(Body.Power(4080, 89, 0), t0 + 20_000)
        assertEquals(2, r.power.size)
        r = r.with(Body.Power(4080, 89, 0), t0 + 20_000 + Readings.EVERY_MS)
        assertEquals("the same, a while after, is", 3, r.power.size)
    }

    @Test
    fun otherNewsChangesNothing() {
        val r = Readings().with(Body.Power(4100, 90, 0), t0)
        assertSame(r, r.with(Body.NeighbourGone(1), t0 + 1))
    }

    @Test
    fun anEndKeepsTheLastAgainOnceAndTheNextReadingAfterIsKept() {
        var r = Readings().with(Body.Power(4100, 90, 0), t0).with(Body.Airtime(3600, 36_000, 1_000, 0), t0)
        r = r.ended(t0 + 60_000)
        assertEquals(PowerReading(t0 + 60_000, 4100, 90, 0, end = true), r.power.last())
        assertTrue(r.airtime.last().end)
        assertSame("a second end adds nothing", r, r.ended(t0 + 70_000))
        r = r.with(Body.Power(4100, 90, 0), t0 + 80_000)
        assertEquals("the same value after an end starts a new stretch", 3, r.power.size)
    }

    @Test
    fun readingsOlderThanAWeekGo() {
        var r = Readings().with(Body.Power(4100, 90, 0), t0)
        r = r.with(Body.Power(4000, 80, 0), t0 + Readings.KEPT_MS + 1)
        assertEquals(listOf(80), r.power.map { it.percent })
    }

    @Test
    fun theFileKeepsEverything() {
        val r = Readings()
            .with(Body.Power(4100, 255, 3), t0)
            .with(Body.Airtime(0, 0, 0, 0), t0 + 1)
            .with(Body.Airtime(3600, 36_000, 12_345, 2_000), t0 + 2)
            .ended(t0 + 3)
        assertEquals(r, Readings.decode(Readings.encode(r)))
        assertEquals(Readings(), Readings.decode("something else".toByteArray()))
        val damaged = String(Readings.encode(r)) + "p x y z\na 1 2\n"
        assertEquals(r, Readings.decode(damaged.toByteArray()))
    }

    @Test
    fun aChartTakesTheValueHeldAtItsStart() {
        val points = listOf(ChartPoint(t0, 90f, false), ChartPoint(t0 + 100, 80f, false), ChartPoint(t0 + 200, 70f, false))
        assertEquals(listOf(ChartPoint(t0 + 150, 80f, false), ChartPoint(t0 + 200, 70f, false)), inSpan(points, t0 + 150))
        val ended = listOf(ChartPoint(t0, 90f, false), ChartPoint(t0 + 100, 90f, true), ChartPoint(t0 + 300, 70f, false))
        assertEquals("a value that ended before the start holds nothing", listOf(ChartPoint(t0 + 300, 70f, false)), inSpan(ended, t0 + 150))
    }

    @Test
    fun waitsAreCountedOncePerRun() {
        val a = listOf(
            AirtimeReading(t0, 3600, 36_000, 35_000, 500),
            AirtimeReading(t0 + 1, 3600, 36_000, 35_500, 300),
            AirtimeReading(t0 + 2, 3600, 36_000, 20_000, 0),
            AirtimeReading(t0 + 3, 3600, 36_000, 36_000, 900),
        )
        assertEquals(2, readingsWaited(a, t0))
        assertEquals(1, readingsWaited(a, t0 + 2))
    }

    @Test
    fun periodsAreSaidBriefly() {
        assertEquals("1 h", periodText(3600))
        assertEquals("10 min", periodText(600))
        assertEquals("90 s", periodText(90))
    }
}
