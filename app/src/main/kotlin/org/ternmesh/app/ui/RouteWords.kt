// How a message went, and traces (version 18), in the user's words: the line under each message,
// the details behind it, and what a trace found. Routing ids are named as the app knows them.
package org.ternmesh.app.ui

import android.content.Context
import org.ternmesh.app.R
import org.ternmesh.app.node.NodeState
import org.ternmesh.app.node.RangeRow
import org.ternmesh.app.node.TraceRun
import org.ternmesh.app.node.Unsent
import org.ternmesh.companion.Body
import org.ternmesh.companion.Conversations
import org.ternmesh.companion.Item
import org.ternmesh.companion.MessageState
import org.ternmesh.companion.Records
import org.ternmesh.companion.TraceResult

/** Whether the node says how messages went, and traces (version 18). */
fun canTrace(state: NodeState) = (state.version ?: 0) >= 18

/**
 * What the app calls the node whose routing id is [id], or null for none it knows: "your node" for
 * its own, the contact with it, the name the user gave it on this phone, or the name a card held
 * from it claims.
 */
fun routingName(context: Context, records: Records, names: Map<Long, String>, id: Long): String? = when {
    records.self?.address?.let(records::routingIdOf) == id -> context.getString(R.string.your_node)
    else -> Conversations.nameOf(records, id) ?: names[id]
        ?: Conversations.cardOf(records, id)?.name?.ifEmpty { null }?.let { context.getString(R.string.card_claim, it) }
}

/** [routingName], or the id itself, as eight hex digits. */
fun routingLabel(context: Context, records: Records, names: Map<Long, String>, id: Long): String =
    routingName(context, records, names, id) ?: routingText(id)

/** A signal-to-noise ratio in quarters of a dB, in dB: "SNR 6.5 dB". */
fun snrText(context: Context, quarters: Int): String = context.getString(R.string.route_snr, context.getString(R.string.snr_db, quarters / 4.0))

/** Milliseconds as seconds, to a tenth: "3.1 s". */
fun tookText(context: Context, ms: Long): String = context.getString(R.string.seconds_tenths, ms / 1000.0)

/** How many hops: "Direct" for one, else "3 hops". */
private fun hopsText(context: Context, hops: Int): String =
    if (hops == 1) context.getString(R.string.route_direct) else context.resources.getQuantityString(R.plurals.route_hops, hops, hops)

/**
 * The line under a message that says how it went, from its [route]: null with none, or nothing to
 * say yet. Received, how many hops it came and how well the last was heard; a group message came
 * at most that far. Sent, the neighbour it went to and, once delivered, the hops and round trip of
 * the acknowledgement's way back.
 */
fun routeLine(context: Context, records: Records, names: Map<Long, String>, item: Item, route: Body.Route?): String? {
    route ?: return null
    if (item.state == MessageState.RECEIVED) {
        if (route.hops == 0) return null
        val hops = if (item is Body.GroupMessage && route.hops > 1) {
            context.resources.getQuantityString(R.plurals.route_up_to_hops, route.hops, route.hops)
        } else {
            hopsText(context, route.hops)
        }
        return hops + " · " + snrText(context, route.snr)
    }
    if (item is Body.GroupMessage) {
        return route.goes.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.route_went_out, it, it) }
    }
    if (route.via == 0L) return null
    val direct = route.via == destinationOf(records, item)
    val via = routingLabel(context, records, names, route.via)
    val parts = mutableListOf<String>()
    when (item.state) {
        MessageState.DELIVERED -> {
            parts += if (direct) context.getString(R.string.route_delivered_direct) else context.getString(R.string.route_delivered_via, via)
            if (route.hops > 0 && !(direct && route.hops == 1)) parts += context.resources.getQuantityString(R.plurals.route_hops, route.hops, route.hops)
            if (route.took > 0) parts += tookText(context, route.took)
        }
        MessageState.NOT_DELIVERED -> {
            parts += if (direct) context.getString(R.string.route_last_direct) else context.getString(R.string.route_last_via, via)
            if (route.goes > 0) parts += context.resources.getQuantityString(R.plurals.route_tries, route.goes, route.goes)
        }
        else -> {
            parts += if (direct) context.getString(R.string.route_sent_direct) else context.getString(R.string.route_sent_via, via)
            if (route.goes > 1) parts += context.getString(R.string.route_try, route.goes)
        }
    }
    return parts.joinToString(" · ")
}

/** The routing id of where [item] was sent: its contact's. */
private fun destinationOf(records: Records, item: Item): Long? = when (item) {
    is Body.Message -> records.routingIdOf(item.contact)
    is Body.Invite -> records.routingIdOf(item.contact)
    else -> null
}

/**
 * Every field of [route], each as a label and what it says, for the details behind a message, in
 * plain words; and, after them, what to bear in mind reading them.
 */
fun routeFacts(context: Context, records: Records, names: Map<Long, String>, item: Item, route: Body.Route?): Pair<List<Pair<String, String>>, List<String>> {
    val facts = mutableListOf<Pair<String, String>>()
    val notes = mutableListOf<String>()
    if (route == null) {
        notes += context.getString(R.string.route_none)
        return facts to notes
    }
    fun signal() {
        facts += context.getString(R.string.route_fact_snr) to context.getString(R.string.snr_db, route.snr / 4.0)
        facts += context.getString(R.string.route_fact_rssi) to context.getString(R.string.dbm, route.rssi)
    }
    if (item.state == MessageState.RECEIVED) {
        val group = item is Body.GroupMessage
        facts += context.getString(R.string.route_fact_came) to when {
            route.hops == 0 -> context.getString(R.string.route_unknown)
            route.hops == 1 -> context.getString(R.string.route_came_direct)
            group -> context.resources.getQuantityString(R.plurals.route_came_up_to, route.hops, route.hops, route.hops - 1)
            else -> context.resources.getQuantityString(R.plurals.route_came_relays, route.hops - 1, route.hops, route.hops - 1)
        }
        if (route.hops > 0) signal()
        if (group) notes += context.getString(R.string.route_group_explained)
        notes += context.getString(R.string.route_relays_unnamed)
        return facts to notes
    }
    if (item is Body.GroupMessage) {
        facts += context.getString(R.string.route_fact_went_out) to context.resources.getQuantityString(R.plurals.route_times, route.goes, route.goes)
        return facts to notes
    }
    val dest = destinationOf(records, item)
    facts += context.getString(R.string.route_fact_first) to when {
        route.via == 0L -> context.getString(R.string.route_nowhere_yet)
        route.via == dest -> context.getString(R.string.route_straight_to, routingLabel(context, records, names, route.via))
        else -> context.getString(R.string.route_named_id, routingLabel(context, records, names, route.via), "%08x".format(route.via))
    }
    facts += context.getString(R.string.route_fact_tries) to route.goes.toString()
    if (item.state == MessageState.DELIVERED && route.hops > 0) {
        facts += context.getString(R.string.route_fact_back) to hopsText(context, route.hops)
        signal()
        facts += context.getString(R.string.route_fact_took) to tookText(context, route.took)
        notes += context.getString(R.string.route_back_explained)
    } else {
        notes += context.getString(R.string.route_until_delivered)
    }
    return facts to notes
}

/**
 * How a trace to [target] ended, in a sentence: who answered, or how far it got, or that it did not
 * go. The node that answered is the last on the way out.
 */
fun traceResultText(context: Context, records: Records, names: Map<Long, String>, traced: Body.Traced, target: String): String {
    val answerer = traced.wayOut.lastOrNull()?.let { routingLabel(context, records, names, it.routingId) }
    return when (traced.result) {
        TraceResult.ANSWERED -> context.getString(R.string.trace_answered, target)
        TraceResult.KEEPER -> context.getString(R.string.trace_keeper, answerer ?: context.getString(R.string.trace_a_relay), target)
        TraceResult.NO_ROUTE -> context.getString(R.string.trace_no_route, answerer ?: context.getString(R.string.trace_a_relay), target)
        TraceResult.NOT_ANSWERED -> context.getString(R.string.trace_not_answered, target)
        TraceResult.NOT_BEGUN -> context.getString(R.string.trace_not_begun, target)
        else -> context.getString(R.string.trace_other, traced.result)
    }
}

/** A trace answered, in numbers: "2 hops out · 1 hop back · 3.1 s". */
fun traceSummary(context: Context, traced: Body.Traced): String = listOf(
    context.resources.getQuantityString(R.plurals.trace_hops_out, traced.wayOut.size, traced.wayOut.size),
    context.resources.getQuantityString(R.plurals.trace_hops_back, traced.wayBack.size + 1, traced.wayBack.size + 1),
    tookText(context, traced.took),
).joinToString(" · ")

/** Where a trace is before it ends: asking the node, waiting and what for, or out and waiting for an answer. */
fun traceStepText(context: Context, run: TraceRun): String = when {
    run.id == null -> context.getString(R.string.trace_asking)
    run.state == MessageState.SENT -> context.getString(R.string.trace_sent)
    run.state == MessageState.WAITING && run.reason == 1 -> context.getString(R.string.manage_step_route)
    run.state == MessageState.WAITING && run.reason >= 3 -> {
        val reason = when (run.reason) {
            3 -> R.string.reason_3
            4 -> R.string.reason_4
            else -> R.string.reason_5
        }
        val base = context.getString(R.string.waiting_for, context.getString(reason))
        if (run.wait > 0) base + " · " + context.getString(R.string.about_seconds, run.wait) else base
    }
    else -> context.getString(R.string.trace_queued)
}

/** One ping of a range test, as its log shows it: what became of it, and how, when the node says. */
fun rangeRowText(context: Context, records: Records, names: Map<Long, String>, row: RangeRow): String {
    val p = row.ping
    val route = row.route
    val parts = mutableListOf<String>()
    parts += when {
        p.unsent == Unsent.NOT_CONNECTED -> context.getString(R.string.range_not_connected)
        p.unsent == Unsent.REFUSED -> context.getString(R.string.range_refused)
        p.unsent == Unsent.UNANSWERED -> context.getString(R.string.range_unanswered)
        p.id == null -> context.getString(R.string.range_asking)
        row.state == MessageState.DELIVERED -> context.getString(R.string.state_delivered)
        row.state == MessageState.NOT_DELIVERED -> context.getString(R.string.state_not_delivered)
        row.state == MessageState.SENT -> context.getString(R.string.state_sent)
        else -> context.getString(R.string.state_waiting)
    }
    if (route != null && row.delivered && route.hops > 0) {
        parts += hopsText(context, route.hops)
        parts += snrText(context, route.snr)
        parts += context.getString(R.string.dbm, route.rssi)
        if (route.took > 0) parts += tookText(context, route.took)
    }
    if (route != null && route.via != 0L) parts += context.getString(R.string.range_via, routingLabel(context, records, names, route.via))
    return parts.joinToString(" · ")
}
