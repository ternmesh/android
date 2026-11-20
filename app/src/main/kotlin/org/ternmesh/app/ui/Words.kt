// The protocol's numbers in the user's words.
package org.ternmesh.app.ui

import android.content.Context
import android.text.format.DateUtils
import org.ternmesh.app.R
import org.ternmesh.app.link.LinkFailure
import org.ternmesh.app.node.BootloaderStep
import org.ternmesh.app.node.FirmwareUpdate
import org.ternmesh.app.node.NodeState
import org.ternmesh.app.node.Phase
import org.ternmesh.app.node.Problem
import org.ternmesh.app.node.percent
import org.ternmesh.companion.Address
import org.ternmesh.companion.Body
import org.ternmesh.companion.Conversations
import org.ternmesh.companion.DfuFailure
import org.ternmesh.companion.Item
import org.ternmesh.companion.MessageState
import org.ternmesh.companion.Outcome
import org.ternmesh.companion.Records

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
        Phase.OFF -> R.string.phase_off
        Phase.BOOTLOADER -> R.string.phase_bootloader
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
    10 -> context.getString(R.string.error_10)
    11 -> context.getString(R.string.error_11)
    12 -> context.getString(R.string.error_12)
    13 -> context.getString(R.string.error_13)
    14 -> context.getString(R.string.error_14)
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

/** What the service's notification says of an update under way, or null for none. */
fun updateNotificationText(context: Context, state: NodeState): String? = when (val u = state.update) {
    is FirmwareUpdate.Downloading -> context.getString(R.string.update_notification_downloading, percent(u))
    is FirmwareUpdate.Sending ->
        if (u.waiting) context.getString(R.string.update_waiting) else context.getString(R.string.update_notification_sending, percent(u))
    is FirmwareUpdate.Restarting -> context.getString(R.string.update_restarting)
    is FirmwareUpdate.Bootloader -> when (u.step) {
        BootloaderStep.ERASING -> context.getString(R.string.update_notification_erasing)
        BootloaderStep.SENDING -> context.getString(R.string.update_notification_sending, percent(u) ?: 0)
        else -> context.getString(R.string.update_notification_bootloader)
    }
    else -> null
}

/** How a transfer to the bootloader stopped. */
fun dfuText(context: Context, why: DfuFailure?): String = when (why) {
    is DfuFailure.Refused -> context.getString(
        R.string.dfu_refused,
        when (why.status) {
            2 -> context.getString(R.string.dfu_status_2)
            3 -> context.getString(R.string.dfu_status_3)
            4 -> context.getString(R.string.dfu_status_4)
            5 -> context.getString(R.string.dfu_status_5)
            6 -> context.getString(R.string.dfu_status_6)
            else -> context.getString(R.string.dfu_status_other, why.status)
        },
    )
    DfuFailure.NoAnswer -> context.getString(R.string.dfu_no_answer)
    DfuFailure.Cancelled -> context.getString(R.string.dfu_cancelled)
    DfuFailure.Lost, null -> context.getString(R.string.dfu_lost)
}

/** What the app calls the node at [address]: the user's name for the contact, "your node" for its own, or its short code. */
fun nodeName(context: Context, records: Records, address: Address): String = when {
    address == records.self?.address -> context.getString(R.string.your_node)
    else -> records.contacts[address]?.name?.ifEmpty { null } ?: Conversations.short(address.toString())
}

/** An `OWNER_CHANGED` in a sentence: who was made an owner of which node, or is no longer one, and how. */
fun ownerChangedText(context: Context, records: Records, notice: Body.OwnerChanged): String {
    val who = nodeName(context, records, notice.contact).replaceFirstChar { it.uppercase() }
    val node = nodeName(context, records, notice.from)
    val id = when {
        notice.owner == 1 && notice.by == 0 -> R.string.owner_made_at_node
        notice.owner == 1 -> R.string.owner_made_over_mesh
        notice.by == 0 -> R.string.owner_removed_at_node
        else -> R.string.owner_removed_over_mesh
    }
    return context.getString(id, who, node)
}
