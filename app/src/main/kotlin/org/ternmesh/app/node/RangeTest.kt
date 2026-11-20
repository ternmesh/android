// A range test: numbered messages, "range #1", "range #2" and on, to one contact every so often,
// each logged with where the phone was when it went and, from the node's records, what became of
// it: delivered or not, and, from a node of version 18, how (ROUTE): the hops and signal of the
// acknowledgement's way back, its round trip, and the neighbour it first went to.
//
// What became of each ping is read from the records whenever it is asked for, not copied: a
// ROUTE or a STATE that comes later, even after the link dropped and came back, is in the next
// row. Nothing here touches Android; the repository runs its clock and sends what it says.
package org.ternmesh.app.node

import org.ternmesh.companion.Address
import org.ternmesh.companion.Body
import org.ternmesh.companion.MessageState
import org.ternmesh.companion.Records
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Where the phone was: degrees north and east, and how exact, in metres, null for unknown; [age] seconds old. */
data class PhoneFix(val lat: Double, val lon: Double, val accuracy: Float?, val age: Long)

/** Why a ping did not reach the node as a message. */
enum class Unsent {
    /** The node was not connected and synced when it was due. */
    NOT_CONNECTED,

    /** The node refused it: no room, most often. */
    REFUSED,

    /** The node did not answer, or the link dropped first: it may have gone all the same, and is matched by its text once a sync shows it. */
    UNANSWERED,
}

/**
 * One ping: its number [n], when it was due on the phone's clock ([at], milliseconds since 1970),
 * the [fix] the phone had then, and the message it became, by [id], once the node said or a sync
 * showed it. [after] is the greatest `id` the node held when it went, for that match.
 */
data class RangePing(
    val n: Int,
    val at: Long,
    val text: String,
    val fix: PhoneFix?,
    val after: Long,
    val id: Long? = null,
    val unsent: Unsent? = null,
)

/** A ping as the records leave it now: its message's state and route, null for none yet. */
data class RangeRow(val ping: RangePing, val state: Int?, val route: Body.Route?) {
    val delivered: Boolean get() = state == MessageState.DELIVERED
}

/**
 * The test itself, while its screen is open: to the contact [to], one ping each [intervalMs]. The
 * first goes at once.
 */
class RangeTest(val to: Address, val intervalMs: Long, val startedAt: Long) {
    init {
        require(intervalMs in MIN_INTERVAL_MS..MAX_INTERVAL_MS) { "a ping every 30 to 60 s, not $intervalMs ms" }
    }

    private val list = mutableListOf<RangePing>()

    val pings: List<RangePing> get() = list.toList()

    /** When the next ping is due, on the phone's clock. */
    val nextAt: Long get() = startedAt + list.size * intervalMs

    /** The next ping, due now: logged at once, with [fix], and [unsent] if it cannot go. */
    fun next(at: Long, fix: PhoneFix?, after: Long, unsent: Unsent? = null): RangePing {
        val n = list.size + 1
        return RangePing(n, at, "$TEXT$n", fix, after, unsent = unsent).also { list += it }
    }

    /** The node took ping [n] as the message [id]. */
    fun queued(n: Int, id: Long) = change(n) { it.copy(id = id, unsent = null) }

    /** Ping [n] did not reach the node as a message, for [why]. */
    fun unsent(n: Int, why: Unsent) = change(n) { if (it.id != null) it else it.copy(unsent = why) }

    /**
     * Pings the node did not answer for, matched against what it holds: a message to [to] with the
     * ping's text, past what it held when the ping went, is the ping, and it went.
     */
    fun resolve(records: Records): Boolean {
        var changed = false
        for ((i, p) in list.withIndex()) {
            if (p.id != null || p.unsent != Unsent.UNANSWERED) continue
            val match = records.items.values.firstOrNull {
                it is Body.Message && it.id > p.after && it.contact == to && it.state != MessageState.RECEIVED && it.text == p.text
            } ?: continue
            list[i] = p.copy(id = match.id, unsent = null)
            changed = true
        }
        return changed
    }

    private fun change(n: Int, how: (RangePing) -> RangePing) {
        val i = list.indexOfFirst { it.n == n }
        if (i >= 0) list[i] = how(list[i])
    }

    companion object {
        const val TEXT = "range #"
        const val MIN_INTERVAL_MS = 30_000L
        const val MAX_INTERVAL_MS = 60_000L
        const val DEFAULT_INTERVAL_MS = 30_000L
    }
}

/** Each of [pings] as [records] leave it now. */
fun rangeRows(pings: List<RangePing>, records: Records): List<RangeRow> = pings.map { p ->
    RangeRow(p, p.id?.let { records.items[it]?.state }, p.id?.let { records.routes[it] })
}

/** How many of [rows] were delivered, of those sent. */
fun rangeTally(rows: List<RangeRow>): Pair<Int, Int> = rows.count { it.delivered } to rows.count { it.ping.id != null }

/**
 * [rows] as CSV, one line each after a header, for a spreadsheet or a map: times in ISO 8601 in
 * [zone], positions in degrees, SNR in dB, RSSI in dBm and the round trip in seconds; a field
 * with nothing to say is empty. [name] names a routing id, or gives null for none.
 */
fun rangeCsv(rows: List<RangeRow>, to: Address, name: (Long) -> String?, zone: ZoneId = ZoneId.systemDefault()): String {
    val time = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(zone)
    val out = StringBuilder()
    out.append(
        "n,time,lat,lon,accuracy_m,fix_age_s,to,message_id,result,hops,snr_db,rssi_dbm,round_trip_s,tries,via_id,via\n",
    )
    for (r in rows) {
        val p = r.ping
        val route = r.route
        // Hops, signal and round trip are the acknowledgement's: nothing until it is delivered.
        val back = route?.takeIf { r.delivered && it.hops > 0 }
        val fields = listOf(
            p.n.toString(),
            time.format(Instant.ofEpochMilli(p.at)),
            p.fix?.let { "%.7f".format(Locale.ROOT, it.lat) }.orEmpty(),
            p.fix?.let { "%.7f".format(Locale.ROOT, it.lon) }.orEmpty(),
            p.fix?.accuracy?.let { "%.0f".format(Locale.ROOT, it) }.orEmpty(),
            p.fix?.age?.toString().orEmpty(),
            to.toString().take(8),
            p.id?.toString().orEmpty(),
            rangeResult(r),
            back?.hops?.toString().orEmpty(),
            back?.let { "%.2f".format(Locale.ROOT, it.snr / 4.0) }.orEmpty(),
            back?.rssi?.toString().orEmpty(),
            route?.took?.takeIf { it > 0 }?.let { "%.3f".format(Locale.ROOT, it / 1000.0) }.orEmpty(),
            route?.goes?.takeIf { it > 0 }?.toString().orEmpty(),
            route?.via?.takeIf { it != 0L }?.let { "%08x".format(it) }.orEmpty(),
            route?.via?.takeIf { it != 0L }?.let(name).orEmpty(),
        )
        fields.joinTo(out, ",") { csvField(it) }
        out.append('\n')
    }
    return out.toString()
}

/** What became of a ping, in one word a spreadsheet can filter on. */
fun rangeResult(r: RangeRow): String = when {
    r.ping.unsent == Unsent.NOT_CONNECTED -> "not_connected"
    r.ping.unsent == Unsent.REFUSED -> "refused"
    r.ping.unsent == Unsent.UNANSWERED -> "unanswered"
    r.ping.id == null -> "asking"
    else -> when (r.state) {
        MessageState.DELIVERED -> "delivered"
        MessageState.NOT_DELIVERED -> "not_delivered"
        MessageState.SENT -> "sent"
        MessageState.WAITING -> "waiting"
        else -> "unknown"
    }
}

/** A field quoted only when it has to be: a comma, a quote or a line break in it. */
private fun csvField(s: String): String =
    if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
