// New firmware for a node whose bootloader takes it, as "By the bootloader" in draft/companion.md
// has it: an nRF52840 board's, which speaks Nordic Semiconductor's legacy DFU over Bluetooth LE
// (the DFU service of the nRF5 SDK 11), as Adafruit's bootloader and OTAFIX do. Such a node has room
// for one image, not two, so `UPDATE_BOOTLOADER` restarts it into the bootloader, and the bootloader
// takes the image. tools/ble_dfu.py in ternmesh/firmware is the same client in Python; this one
// keeps its steps, its waits and its pace.
//
// The exchange, on the service's control point (written with a response, answering by notification)
// and its packet characteristic (written without one):
//
//   control  START_DFU, application          01 04
//   packet   sizes: SoftDevice, bootloader, application, u32 each, little-endian
//            <- 10 01 01                      once the bootloader has erased the application
//   control  INIT 02 00, packet the init packet, control INIT 02 01
//            <- 10 02 01
//   control  receipts every 10 packets       08 0a 00
//   control  RECEIVE                          03
//   packet   the image, 20 bytes at a time    <- 11 and the bytes received, every 10 packets
//            <- 10 03 01                      after the last
//   control  VALIDATE                         04  <- 10 04 01: its CRC is the init packet's
//   control  ACTIVATE_AND_RESET               05  the bootloader starts it
//
// A transfer that fails or is given up on leaves the bootloader waiting, advertising again, so a
// retry works; it writes only the application's flash, so the node keeps everything else it holds.
//
// Like the connection, it does no I/O and keeps no time of its own: a [DfuLink] does the writing and
// hands it notifications, and the caller calls tick() at [LegacyDfu.nextDeadline], which may move
// earlier on any callback; a caller that does not look again after each can tick every second or so
// as well, and a step is given up on that much late.
package org.ternmesh.companion

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/**
 * A DFU package, as `adafruit-nrfutil dfu genpkg` makes it: a zip of `manifest.json`, which names
 * the application's init packet (`dat_file`) and its image (`bin_file`), and the two files.
 */
class DfuPackage(init: ByteArray, image: ByteArray) {
    internal val initData = init.copyOf()
    internal val imageData = image.copyOf()

    /** The init packet: what the bootloader checks the image against. */
    val init: ByteArray get() = initData.copyOf()

    /** The application. */
    val image: ByteArray get() = imageData.copyOf()

    val imageSize: Int get() = imageData.size

    init {
        require(init.isNotEmpty()) { "an empty init packet" }
        require(image.isNotEmpty()) { "an empty application" }
    }

    companion object {
        /** Reads a package from the zip's bytes, or throws IllegalArgumentException if it is not one with an application. */
        fun read(zip: ByteArray): DfuPackage {
            val files = mutableMapOf<String, ByteArray>()
            try {
                ZipInputStream(ByteArrayInputStream(zip)).use { z ->
                    var total = 0L
                    while (true) {
                        val e = z.nextEntry ?: break
                        if (e.isDirectory) continue
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val n = z.read(buffer)
                            if (n < 0) break
                            total += n
                            // A zip that unpacks to more than any image is not a package.
                            require(total <= Manifest.IMAGE_MAX) { "more than ${Manifest.IMAGE_MAX} bytes unpacked" }
                            out.write(buffer, 0, n)
                        }
                        files[e.name] = out.toByteArray()
                    }
                }
            } catch (e: IOException) {
                throw IllegalArgumentException("not a zip: ${e.message}")
            }
            val manifest = files["manifest.json"] ?: throw IllegalArgumentException("no manifest.json")
            val root = JsonText.parse(String(manifest, Charsets.UTF_8)) as? Map<*, *>
            val app = ((root?.get("manifest") as? Map<*, *>)?.get("application") as? Map<*, *>)
                ?: throw IllegalArgumentException("no application in it")
            fun file(key: String): ByteArray {
                val name = app[key] as? String ?: throw IllegalArgumentException("no $key")
                return files[name] ?: throw IllegalArgumentException("no $name, which $key names")
            }
            return DfuPackage(file("dat_file"), file("bin_file"))
        }
    }
}

/**
 * The bootloader's two characteristics, on a GATT link to it whose control point has notifications
 * turned on. Everything is called, and calls back, on one thread.
 */
interface DfuLink {
    /** Writes [value] to the control point, with a response; [done] says whether it was written. */
    fun writeControl(value: ByteArray, done: (Boolean) -> Unit)

    /** Writes [value] to the packet characteristic, without a response; [done] once the phone has sent it, so that the next may follow. */
    fun writePacket(value: ByteArray, done: (Boolean) -> Unit)

    /** Set by [LegacyDfu.start]: called with each notification from the control point. */
    var onNotification: (ByteArray) -> Unit

    /** Set by [LegacyDfu.start]: called if the link drops. */
    var onLost: () -> Unit
}

/** Where a transfer to the bootloader is. */
sealed interface DfuState {
    data object Idle : DfuState

    /** `START_DFU` sent: the bootloader erases the old application, which takes tens of seconds. */
    data object Erasing : DfuState

    /** The init packet. */
    data object Initialising : DfuState

    /** The image: [sent] of [size] bytes. */
    data class Sending(val sent: Long, val size: Long) : DfuState

    /** `VALIDATE`: the bootloader checks the image against the init packet. */
    data object Validating : DfuState

    /** `ACTIVATE_AND_RESET` sent: the bootloader starts the new firmware, and the node comes back running it. */
    data object Done : DfuState

    /** It stopped, in the step whose control op is [op] (1 erasing, 2 the init packet, 3 the image, 4 validating). */
    data class Failed(val why: DfuFailure, val op: Int) : DfuState
}

sealed interface DfuFailure {
    /** The bootloader answered the step with this status: 2 invalid state, 3 not supported, 4 too large, 5 CRC error, 6 operation failed. */
    data class Refused(val status: Int) : DfuFailure

    /** No answer, or no receipt, in time. */
    data object NoAnswer : DfuFailure

    /** The link dropped, or a write failed. */
    data object Lost : DfuFailure

    data object Cancelled : DfuFailure
}

/** Sends [pkg] to a bootloader over [link]. [now] is a clock in milliseconds that only goes forward. */
class LegacyDfu(private val pkg: DfuPackage, private val link: DfuLink, private val now: () -> Long) {
    var state: DfuState = DfuState.Idle
        private set

    /** Called on each change of [state]: each packet of the image is one, for its progress. */
    var onChange: (LegacyDfu) -> Unit = {}

    /** It ended, one way or the other. */
    val isFinished: Boolean get() = state == DfuState.Done || state is DfuState.Failed

    /** When tick() next has something to do: a step overdue, or the next packet due. Null when nothing waits on time. */
    val nextDeadline: Long?
        get() = listOfNotNull(deadline, paceUntil).minOrNull()

    private sealed interface Step {
        /** Says where the transfer is: [state], in the step whose control op is [op]. */
        class Show(val state: DfuState, val op: Int) : Step
        class Control(val value: ByteArray) : Step
        class Packet(val value: ByteArray) : Step

        /** Waits for the response to [op], `10 op status`, for [waitMs]. */
        class Answer(val op: Int, val waitMs: Long) : Step
        data object Image : Step
        data object Activate : Step
    }

    private val steps = ArrayDeque<Step>()
    private var step: Step? = null
    private var begun = false

    /** The outcome of the write in flight, once it is known; [writes] counts them, so a late one is told apart. */
    private var written: Boolean? = null
    private var writes = 0

    /** Notifications not yet looked at, oldest first: a step's answer may come before its last write is done. */
    private val received = ArrayDeque<ByteArray>()

    /** When the step under way is given up on. */
    private var deadline: Long? = null

    /** The image: no packet goes before this. */
    private var paceUntil: Long? = null
    private var sent = 0
    private var packets = 0
    private var inFlight = false
    private var receiptDue = false

    /** The control op of the step under way, for a failure to name. */
    private var op = START
    private var pumping = false

    /** Connects the link's callbacks and begins: `START_DFU`, and the sizes. */
    fun start() {
        check(state == DfuState.Idle) { "started already" }
        link.onNotification = ::notified
        link.onLost = ::lost
        val init = pkg.initData
        val sizes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putInt(0).putInt(pkg.imageSize).array()
        steps += Step.Show(DfuState.Erasing, START)
        steps += Step.Control(byteArrayOf(START.toByte(), APPLICATION.toByte()))
        steps += Step.Packet(sizes)
        steps += Step.Answer(START, ERASE_MS)
        steps += Step.Show(DfuState.Initialising, INIT)
        steps += Step.Control(byteArrayOf(INIT.toByte(), 0x00))
        for (at in init.indices step PACKET_BYTES) steps += Step.Packet(init.copyOfRange(at, minOf(init.size, at + PACKET_BYTES)))
        steps += Step.Control(byteArrayOf(INIT.toByte(), 0x01))
        steps += Step.Answer(INIT, STEP_MS)
        steps += Step.Show(DfuState.Sending(0, pkg.imageSize.toLong()), RECEIVE)
        steps += Step.Control(byteArrayOf(RECEIPTS.toByte(), EVERY.toByte(), 0x00))
        steps += Step.Control(byteArrayOf(RECEIVE.toByte()))
        steps += Step.Image
        steps += Step.Answer(RECEIVE, RECEIVED_MS)
        steps += Step.Show(DfuState.Validating, VALIDATE)
        steps += Step.Control(byteArrayOf(VALIDATE.toByte()))
        steps += Step.Answer(VALIDATE, STEP_MS)
        steps += Step.Activate
        pump()
    }

    /** Stops sending. The bootloader goes on waiting for an image. */
    fun cancel() {
        if (state == DfuState.Idle || isFinished) return
        fail(DfuFailure.Cancelled)
    }

    /** Runs the timers: the next packet of the image, or a step given up on. */
    fun tick() {
        if (isFinished) return
        val t = now()
        paceUntil?.let { if (t >= it) paceUntil = null }
        val d = deadline
        if (d != null && t >= d) {
            deadline = null
            when (step) {
                // It resets as it answers, and the link may drop before the answer: that is success.
                Step.Activate -> return set(DfuState.Done)
                is Step.Control, is Step.Packet -> return fail(DfuFailure.Lost)
                Step.Image -> return fail(if (inFlight) DfuFailure.Lost else DfuFailure.NoAnswer)
                else -> return fail(DfuFailure.NoAnswer)
            }
        }
        pump()
    }

    private fun notified(value: ByteArray) {
        if (isFinished) return
        received.addLast(value.copyOf())
        pump()
    }

    private fun lost() {
        if (isFinished || state == DfuState.Idle) return
        if (step == Step.Activate) set(DfuState.Done) else fail(DfuFailure.Lost)
    }

    /** Takes the steps as far as they go now. A link that calls back at once is called back into here, and waits its turn. */
    private fun pump() {
        if (pumping) return
        pumping = true
        try {
            while (!isFinished && advance()) Unit
        } finally {
            pumping = false
        }
    }

    /** Moves the step under way on, or the next one; true if it did, and there may be more to do now. */
    private fun advance(): Boolean {
        val s = step ?: steps.removeFirstOrNull()?.also {
            step = it
            begun = false
        } ?: return false
        return when (s) {
            is Step.Show -> {
                op = s.op
                set(s.state)
                next()
            }
            is Step.Control, is Step.Packet -> {
                if (!begun) {
                    begun = true
                    write(s)
                    return true
                }
                val w = written ?: return false
                if (!w) return false.also { fail(DfuFailure.Lost) }
                next()
            }
            is Step.Answer -> {
                if (!begun) {
                    begun = true
                    deadline = now() + s.waitMs
                }
                answer(s.op)
            }
            Step.Image -> image()
            Step.Activate -> {
                if (!begun) {
                    begun = true
                    write(s)
                    return true
                }
                // Written or not: it resets as it answers, and the write may fail as the link drops.
                if (written == null) return false
                set(DfuState.Done)
                false
            }
        }
    }

    /** The response to [stepOp] among what has come: on with the next step, or refused. */
    private fun answer(stepOp: Int): Boolean {
        while (true) {
            val a = received.removeFirstOrNull() ?: return false
            // A receipt here is a late one from the image; anything else is not this step's.
            if (a.size < 3 || a[0].toInt() != RESPONSE || a[1].toInt() != stepOp) continue
            val status = a[2].toInt() and 0xFF
            if (status != SUCCESS) return false.also { refused(stepOp, status) }
            return next()
        }
    }

    /** The image, a packet at a time: paced, and after every [EVERY]th but the last, not on until the receipt for it comes. */
    private fun image(): Boolean {
        val bytes = pkg.imageData
        if (!begun) {
            begun = true
            sent = 0
            packets = 0
            inFlight = false
            receiptDue = false
            paceUntil = null
        }
        if (inFlight) {
            val w = written ?: return false
            inFlight = false
            if (!w) return false.also { fail(DfuFailure.Lost) }
            deadline = null
            sent = minOf(bytes.size, sent + PACKET_BYTES)
            packets++
            // The pace before the progress, so that a caller scheduling its tick on the change sees it.
            if (sent < bytes.size) {
                paceUntil = now() + PACE_MS
                receiptDue = packets % EVERY == 0
            }
            set(DfuState.Sending(sent.toLong(), bytes.size.toLong()))
            if (sent >= bytes.size) return next()
        }
        if (paceUntil != null) return false
        if (receiptDue) {
            if (deadline == null) deadline = now() + STEP_MS
            while (true) {
                val a = received.removeFirstOrNull() ?: return false
                if (a.isEmpty()) continue
                if (a[0].toInt() == RECEIPT) break
                if (a[0].toInt() == RESPONSE && a.size >= 3 && (a[2].toInt() and 0xFF) != SUCCESS) {
                    return false.also { refused(RECEIVE, a[2].toInt() and 0xFF) }
                }
            }
            receiptDue = false
            deadline = null
        }
        inFlight = true
        write(Step.Packet(bytes.copyOfRange(sent, minOf(bytes.size, sent + PACKET_BYTES))))
        return true
    }

    private fun write(s: Step) {
        written = null
        val mine = ++writes
        deadline = now() + WRITE_MS
        val done = { ok: Boolean ->
            if (mine == writes && !isFinished) {
                written = ok
                pump()
            }
        }
        when (s) {
            is Step.Control -> link.writeControl(s.value, done)
            is Step.Packet -> link.writePacket(s.value, done)
            Step.Activate -> link.writeControl(byteArrayOf(ACTIVATE.toByte()), done)
            else -> error("not a write")
        }
    }

    private fun next(): Boolean {
        step = null
        deadline = null
        return true
    }

    private fun refused(stepOp: Int, status: Int) {
        // A START refused for "invalid state" is a bootloader still in an earlier session, given up
        // on mid-way: RESET ends it, and it waits afresh for the retry.
        if (stepOp == START && status == INVALID_STATE) link.writeControl(byteArrayOf(RESET.toByte())) {}
        fail(DfuFailure.Refused(status))
    }

    private fun fail(why: DfuFailure) {
        step = null
        steps.clear()
        deadline = null
        paceUntil = null
        set(DfuState.Failed(why, op))
    }

    private fun set(s: DfuState) {
        state = s
        if (isFinished) {
            deadline = null
            paceUntil = null
        }
        onChange(this)
    }

    companion object {
        /** The bootloader's service, and its control point and packet characteristics. */
        const val SERVICE = "00001530-1212-efde-1523-785feabcd123"
        const val CONTROL = "00001531-1212-efde-1523-785feabcd123"
        const val PACKET = "00001532-1212-efde-1523-785feabcd123"

        const val START = 0x01
        const val INIT = 0x02
        const val RECEIVE = 0x03
        const val VALIDATE = 0x04
        const val ACTIVATE = 0x05
        const val RESET = 0x06
        const val RECEIPTS = 0x08
        const val RESPONSE = 0x10
        const val RECEIPT = 0x11

        /** START_DFU's image type: an application alone. */
        const val APPLICATION = 0x04

        const val SUCCESS = 1
        const val INVALID_STATE = 2

        /** A packet of the image: the least every link carries, whatever its MTU. */
        const val PACKET_BYTES = 20

        /** Packets between receipts: Nordic's own library holds legacy bootloaders to ten. */
        const val EVERY = 10

        /**
         * A gap between packets of the image. A bootloader sent them faster than it writes them to
         * flash stops with "operation failed", as the T1000-E's did with none.
         */
        const val PACE_MS = 10L

        /**
         * START waits for the bootloader to erase the application's flash, page by page between the
         * SoftDevice's radio events: tens of seconds, and on an older bootloader minutes. Given up on
         * too soon, the bootloader is left mid-session, and answers the next START "invalid state".
         */
        const val ERASE_MS = 240_000L

        /** The init packet's answer, each receipt, and VALIDATE's answer. */
        const val STEP_MS = 10_000L

        /** The answer after the image's last packet, once the bootloader has written it all. */
        const val RECEIVED_MS = 30_000L

        /** A write the phone has not finished: the link is as good as gone. */
        const val WRITE_MS = 10_000L
    }
}
