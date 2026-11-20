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
import org.ternmesh.companion.ErrorCode
import org.ternmesh.companion.Item
import org.ternmesh.companion.ManageStep
import org.ternmesh.companion.MessageState
import org.ternmesh.companion.Outcome
import org.ternmesh.companion.Records
import org.ternmesh.companion.Setting

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
    15 -> context.getString(R.string.error_15)
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

/** What a request to [node], managed over the mesh, waits for now: [step], from its message's `STATE`. */
fun manageStepText(context: Context, step: ManageStep, node: String): String = when (step) {
    ManageStep.Starting -> context.getString(R.string.manage_step_starting)
    ManageStep.Queued -> context.getString(R.string.manage_step_waiting)
    ManageStep.FirstContact -> context.getString(R.string.manage_step_first_contact, node)
    ManageStep.FindingRoute -> context.getString(R.string.manage_step_route)
    is ManageStep.Airtime -> {
        // As a message waiting for the same is worded.
        val reason = when (step.reason) {
            3 -> R.string.reason_3
            4 -> R.string.reason_4
            else -> R.string.reason_5
        }
        val base = context.getString(R.string.waiting_for, context.getString(reason))
        if (step.wait > 0) base + " · " + context.getString(R.string.about_seconds, step.wait) else base
    }
    ManageStep.Waiting -> context.getString(R.string.manage_step_waiting)
    ManageStep.Sent -> context.getString(R.string.manage_step_sent, node)
    ManageStep.Delivered -> context.getString(R.string.manage_step_delivered, node)
    is ManageStep.Held -> context.getString(R.string.manage_step_held, node, minutes(step.wake))
    ManageStep.MaybeAsleep -> context.getString(R.string.manage_step_maybe_asleep, node)
    ManageStep.NotDelivered -> context.getString(R.string.manage_step_not_delivered)
}

/** That [node] sleeps [wake] seconds between wakes, and so may take that long: null for a node not known to sleep. */
fun manageAsleepText(context: Context, node: String, wake: Int?): String? =
    wake?.takeIf { it > 0 }?.let {
        context.getString(R.string.manage_asleep, node.replaceFirstChar { c -> c.uppercase() }, minutes(it))
    }

/** Seconds as whole minutes, rounded up: a wake of 330 s is every 6 min. */
private fun minutes(seconds: Int) = (seconds + 59) / 60

/**
 * What the service's notification, and the banner on the tabs, say of a node managed over the mesh
 * while a request to it is on its way: checking whether this node owns it, or waiting for its
 * answer. Null when nothing is on its way.
 */
fun managingText(context: Context, state: NodeState): String? {
    val m = state.managed ?: return null
    if (m.asking == null && m.settleUntil == null) return null
    val name = nodeName(context, state.records, m.address)
    return if (m.checking) context.getString(R.string.checking_owner, name) else context.getString(R.string.managing_waiting, name)
}

/** What a check of whether this node owns [name] came to, for a notification: [owner] if it answered, else [outcome]. */
fun checkResultText(context: Context, name: String, owner: Boolean?, outcome: Outcome?): String? = when {
    owner == true -> context.getString(R.string.owner_yes_tap, name)
    owner == false || (outcome is Outcome.Refused && outcome.code == ErrorCode.NOT_AN_OWNER) -> context.getString(R.string.owner_no, name)
    outcome == Outcome.NoAnswer -> context.getString(R.string.owner_no_answer, name)
    outcome != null && outcome != Outcome.Closed -> context.getString(R.string.manage_failed, name.replaceFirstChar { it.uppercase() }, outcomeText(context, outcome))
    else -> null
}

/** What a request the user made of [name] over the mesh came to, for a notification once they have left the Manage screen. */
fun manageDoneText(context: Context, name: String, body: Body, outcome: Outcome): String? {
    if (outcome == Outcome.Closed) return null
    if (outcome !is Outcome.Answered) {
        return when {
            outcome is Outcome.Refused && outcome.code == ErrorCode.NOT_AN_OWNER -> context.getString(R.string.manage_not_owner, name)
            outcome == Outcome.NoAnswer -> context.getString(R.string.owner_no_answer, name)
            else -> context.getString(R.string.manage_failed, name.replaceFirstChar { it.uppercase() }, outcomeText(context, outcome))
        }
    }
    return when (body) {
        is Body.Set -> context.getString(
            R.string.managed_set,
            context.getString(
                when (body.setting) {
                    is Setting.Role -> R.string.managed_set_role
                    is Setting.Power -> R.string.managed_set_power
                    is Setting.Cards -> R.string.managed_set_cards
                    is Setting.CardName -> R.string.managed_set_card_name
                    is Setting.Receiver -> R.string.managed_set_receiver
                    is Setting.Wake, is Setting.Listen -> R.string.managed_set_sleep
                    is Setting.Lock -> R.string.managed_set_lock
                    else -> R.string.managed_set_other
                },
            ),
            name,
        )
        is Body.Owner -> context.getString(R.string.managed_owners, name)
        Body.Restart -> context.getString(R.string.managed_restart, name.replaceFirstChar { it.uppercase() })
        else -> context.getString(R.string.managed_answered, name.replaceFirstChar { it.uppercase() })
    }
}
