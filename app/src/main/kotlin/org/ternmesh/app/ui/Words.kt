// The protocol's numbers in the user's words.
package org.ternmesh.app.ui

import android.content.Context
import android.text.format.DateUtils
import org.ternmesh.app.R
import org.ternmesh.app.link.LinkFailure
import org.ternmesh.app.node.NodeState
import org.ternmesh.app.node.Phase
import org.ternmesh.app.node.Problem
import org.ternmesh.companion.Item
import org.ternmesh.companion.MessageState
import org.ternmesh.companion.Outcome

fun phaseText(context: Context, state: NodeState): String = context.getString(
    when (state.phase) {
        Phase.NONE -> R.string.phase_none
        Phase.CONNECTING -> R.string.phase_connecting
        Phase.PAIRING -> R.string.phase_pairing
        Phase.GREETING -> R.string.phase_greeting
        Phase.SYNCING -> R.string.phase_syncing
        Phase.READY -> R.string.phase_ready
        Phase.STOPPED -> R.string.phase_stopped
        Phase.DISCONNECTED -> R.string.phase_disconnected
    },
)

fun problemText(context: Context, problem: Problem): String = when (problem) {
    is Problem.Link -> context.getString(
        when (problem.why) {
            LinkFailure.BLUETOOTH_OFF -> R.string.problem_bluetooth_off
            LinkFailure.NO_SERVICE -> R.string.problem_no_service
            LinkFailure.MTU -> R.string.problem_mtu
            LinkFailure.PAIRING -> R.string.problem_pairing
            LinkFailure.LOST -> R.string.problem_lost
        },
    )
    is Problem.Refused -> context.getString(R.string.problem_refused, errorText(context, problem.code))
    Problem.Gone -> context.getString(R.string.problem_gone)
}

fun errorText(context: Context, code: Int): String = when (code) {
    1 -> context.getString(R.string.error_1)
    2 -> context.getString(R.string.error_2)
    3 -> context.getString(R.string.error_3)
    4 -> context.getString(R.string.error_4)
    5 -> context.getString(R.string.error_5)
    6 -> context.getString(R.string.error_6)
    7 -> context.getString(R.string.error_7)
    8 -> context.getString(R.string.error_8)
    9 -> context.getString(R.string.error_9)
    else -> context.getString(R.string.error_other, code)
}

fun outcomeText(context: Context, outcome: Outcome): String = when (outcome) {
    is Outcome.Answered -> context.getString(R.string.done)
    is Outcome.Refused -> context.getString(R.string.outcome_refused, errorText(context, outcome.code))
    Outcome.Unsupported -> context.getString(R.string.outcome_unsupported)
    Outcome.NoAnswer -> context.getString(R.string.outcome_no_answer)
    Outcome.Closed -> context.getString(R.string.outcome_closed)
    is Outcome.Invalid -> context.getString(R.string.outcome_invalid)
}

/** Where a sent item is, and while it waits, what for. Null for one received. */
fun stateText(context: Context, item: Item): String? = when (item.state) {
    MessageState.WAITING -> {
        val reason = when (item.reason) {
            1 -> R.string.reason_1
            2 -> R.string.reason_2
            3 -> R.string.reason_3
            4 -> R.string.reason_4
            5 -> R.string.reason_5
            else -> null
        }
        val base = reason?.let { context.getString(R.string.waiting_for, context.getString(it)) }
            ?: context.getString(R.string.state_waiting)
        if (item.wait > 0) base + " · " + context.getString(R.string.about_seconds, item.wait) else base
    }
    MessageState.SENT -> context.getString(R.string.state_sent)
    MessageState.DELIVERED -> context.getString(R.string.state_delivered)
    MessageState.NOT_DELIVERED -> context.getString(R.string.state_not_delivered)
    else -> null
}

/** A node's time, seconds since 1970, as the phone shows times; empty for 0, a node with no clock. */
fun timeText(context: Context, seconds: Long): String =
    if (seconds == 0L) "" else DateUtils.formatSameDayTime(
        seconds * 1000, System.currentTimeMillis(), java.text.DateFormat.SHORT, java.text.DateFormat.SHORT,
    ).toString()

/** A span of seconds, roughly: 45 s, 3 min, 2 h. */
fun spanText(seconds: Long): String = when {
    seconds < 90 -> "$seconds s"
    seconds < 90 * 60 -> "${seconds / 60} min"
    else -> "${seconds / 3600} h"
}

/** The UTF-8 bytes of [text], which is what the protocol's limits count. */
fun utf8Length(text: String) = text.toByteArray(Charsets.UTF_8).size
