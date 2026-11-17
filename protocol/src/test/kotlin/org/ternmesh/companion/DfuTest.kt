// The legacy DFU client against a bootloader played by hand, and the package it sends.
package org.ternmesh.companion

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DfuTest {
    private val init = ByteArray(14) { (0xA0 + it).toByte() }

    /** 1003 bytes: 51 packets, the last of 3 bytes, and receipts after the 10th to the 50th. */
    private val image = ByteArray(1003) { (it * 7).toByte() }

    @Test
    fun aWholeTransfer() {
        val boot = FakeBootloader(eraseMs = 30_000)
        val dfu = boot.dfu(DfuPackage(init, image))
        val seen = mutableListOf<DfuState>()
        dfu.onChange = { seen += it.state }
        dfu.start()
        boot.run(dfu)

        assertEquals(DfuState.Done, dfu.state)
        assertEquals(
            listOf("0104", "0200", "0201", "080a00", "03", "04", "05"),
            boot.control.map(Hex::encode),
        )
        val sizes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putInt(0).putInt(1003).array()
        assertContentEquals(sizes, boot.sizes)
        assertContentEquals(init, boot.init.toByteArray())
        assertContentEquals(image, boot.image.toByteArray())
        assertTrue(boot.activated)
        assertTrue(boot.packets.all { it.size <= LegacyDfu.PACKET_BYTES })
        assertEquals(51, boot.packetTimes.size)
        assertTrue(boot.packetTimes.zipWithNext().all { (a, b) -> b - a >= LegacyDfu.PACE_MS }, "paced")
        assertTrue(boot.packetTimes.first() >= 30_000, "not before the erase is done")

        assertEquals(listOf(DfuState.Erasing, DfuState.Initialising), seen.take(2))
        val sending = seen.filterIsInstance<DfuState.Sending>()
        assertEquals(DfuState.Sending(0, 1003), sending.first())
        assertEquals(DfuState.Sending(1003, 1003), sending.last())
        assertEquals(sending.map { it.sent }.sorted(), sending.map { it.sent }, "progress only goes forward")
        assertEquals(listOf(DfuState.Validating, DfuState.Done), seen.takeLast(2))
    }

    /** An erase that takes minutes is waited for, up to 240 seconds, and then given up on. */
    @Test
    fun theEraseIsWaitedForUpTo240Seconds() {
        val slow = FakeBootloader(eraseMs = 200_000)
        val ok = slow.dfu(DfuPackage(init, image)).also { it.start() }
        slow.run(ok)
        assertEquals(DfuState.Done, ok.state)

        val stuck = FakeBootloader(eraseMs = 300_000)
        val dfu = stuck.dfu(DfuPackage(init, image)).also { it.start() }
        stuck.run(dfu)
        assertEquals(DfuState.Failed(DfuFailure.NoAnswer, LegacyDfu.START), dfu.state)
        assertEquals(LegacyDfu.ERASE_MS, stuck.time)
    }

    /** A step the bootloader refuses ends the transfer there: nothing more is written. */
    @Test
    fun aRefusedStep() {
        val boot = FakeBootloader(initStatus = 5)
        val dfu = boot.dfu(DfuPackage(init, image)).also { it.start() }
        boot.run(dfu)
        assertEquals(DfuState.Failed(DfuFailure.Refused(5), LegacyDfu.INIT), dfu.state)
        assertEquals(listOf("0104", "0200", "0201"), boot.control.map(Hex::encode))
        assertEquals(0, boot.packetTimes.size)
    }

    /** A START refused for "invalid state", a session given up on mid-way, is ended with RESET, so the next try starts afresh. */
    @Test
    fun aStartRefusedForInvalidStateIsReset() {
        val boot = FakeBootloader(startStatus = LegacyDfu.INVALID_STATE)
        val dfu = boot.dfu(DfuPackage(init, image)).also { it.start() }
        boot.run(dfu)
        assertEquals(DfuState.Failed(DfuFailure.Refused(LegacyDfu.INVALID_STATE), LegacyDfu.START), dfu.state)
        assertEquals(listOf("0104", "06"), boot.control.map(Hex::encode))
    }

    /** A failure in the image, said in place of a receipt, stops it. */
    @Test
    fun anOperationFailedInTheImage() {
        val boot = FakeBootloader(failAfterPackets = 20)
        val dfu = boot.dfu(DfuPackage(init, image)).also { it.start() }
        boot.run(dfu)
        assertEquals(DfuState.Failed(DfuFailure.Refused(6), LegacyDfu.RECEIVE), dfu.state)
        assertEquals(20, boot.packetTimes.size)
    }

    /** After every tenth packet but the last, nothing more goes until the receipt for it comes. */
    @Test
    fun theReceiptIsTheFlowControl() {
        val boot = FakeBootloader(holdReceipts = true)
        val dfu = boot.dfu(DfuPackage(init, image)).also { it.start() }
        boot.run(dfu, until = 5_000)
        assertEquals(10, boot.packetTimes.size, "ten, then waiting")
        assertEquals(DfuState.Sending(200, 1003), dfu.state)

        boot.releaseReceipt()
        boot.run(dfu, until = boot.time + 1_000)
        assertEquals(20, boot.packetTimes.size, "ten more, then waiting again")

        boot.holdReceipts = false
        boot.releaseReceipt()
        boot.run(dfu)
        assertEquals(DfuState.Done, dfu.state)
        assertContentEquals(image, boot.image.toByteArray())
    }

    /** A receipt that never comes is waited for 10 seconds. */
    @Test
    fun aMissingReceiptTimesOut() {
        val boot = FakeBootloader(holdReceipts = true)
        val dfu = boot.dfu(DfuPackage(init, image)).also { it.start() }
        boot.run(dfu)
        assertEquals(DfuState.Failed(DfuFailure.NoAnswer, LegacyDfu.RECEIVE), dfu.state)
        assertEquals(10, boot.packetTimes.size)
        assertEquals(boot.packetTimes.last() + LegacyDfu.PACE_MS + LegacyDfu.STEP_MS, boot.time)
    }

    /** The link dropping mid-way stops it; dropping as ACTIVATE_AND_RESET goes is the bootloader resetting, which is success. */
    @Test
    fun theLinkDropping() {
        val mid = FakeBootloader()
        val dfu = mid.dfu(DfuPackage(init, image)).also { it.start() }
        mid.run(dfu, until = 1_200)
        mid.link.onLost()
        assertEquals(DfuState.Failed(DfuFailure.Lost, LegacyDfu.RECEIVE), dfu.state)

        val end = FakeBootloader(dropOnActivate = true)
        val done = end.dfu(DfuPackage(init, image)).also { it.start() }
        end.run(done)
        assertEquals(DfuState.Done, done.state)
    }

    /** Cancelled, it writes nothing more. */
    @Test
    fun cancelled() {
        val boot = FakeBootloader()
        val dfu = boot.dfu(DfuPackage(init, image)).also { it.start() }
        boot.run(dfu, until = 1_200)
        val sent = boot.packetTimes.size
        assertTrue(sent in 1..50, "part of the way")
        dfu.cancel()
        boot.run(dfu)
        assertEquals(DfuState.Failed(DfuFailure.Cancelled, LegacyDfu.RECEIVE), dfu.state)
        assertEquals(sent, boot.packetTimes.size)
    }

    @Test
    fun aPackageAsAdafruitNrfutilMakesIt() {
        val manifest = """
            {"manifest": {"application": {"bin_file": "zephyr.bin", "dat_file": "zephyr.dat",
              "init_packet_data": {"application_version": 4294967295, "device_revision": 65535, "device_type": 82,
                "firmware_crc16": 1234, "softdevice_req": [65534]}}, "dfu_version": 0.5}}
        """.trimIndent()
        val pkg = DfuPackage.read(zip("manifest.json" to manifest.toByteArray(), "zephyr.dat" to init, "zephyr.bin" to image))
        assertContentEquals(init, pkg.init)
        assertContentEquals(image, pkg.image)

        assertFailsWith<IllegalArgumentException> { DfuPackage.read(byteArrayOf(1, 2, 3)) }
        assertFailsWith<IllegalArgumentException> { DfuPackage.read(zip("zephyr.bin" to image)) }
        assertFailsWith<IllegalArgumentException> {
            DfuPackage.read(zip("manifest.json" to """{"manifest": {"softdevice": {}}}""".toByteArray()))
        }
        assertFailsWith<IllegalArgumentException> {
            DfuPackage.read(zip("manifest.json" to manifest.toByteArray(), "zephyr.dat" to init))
        }
    }

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }
}

/**
 * A legacy DFU bootloader played by hand, on a clock of its own: it erases for [eraseMs], answers
 * each step with the status given, and sends a receipt after every n packets of the image, unless
 * it [holdReceipts]. Each write is done as it is made.
 */
internal class FakeBootloader(
    private val eraseMs: Long = 1_000,
    private val startStatus: Int = 1,
    private val initStatus: Int = 1,
    var holdReceipts: Boolean = false,
    private val failAfterPackets: Int? = null,
    private val dropOnActivate: Boolean = false,
) {
    var time = 0L
    val control = mutableListOf<ByteArray>()
    val packets = mutableListOf<ByteArray>()
    val packetTimes = mutableListOf<Long>()
    var sizes: ByteArray? = null
    val init = ByteArrayOutputStream()
    val image = ByteArrayOutputStream()
    var activated = false

    private var expected = 0
    private var mode = ""
    private var every = 0
    private var imagePackets = 0
    private var heldReceipts = 0

    /** Notifications to send, each at its time. */
    private val due = mutableListOf<Pair<Long, ByteArray>>()

    val link = object : DfuLink {
        override var onNotification: (ByteArray) -> Unit = {}
        override var onLost: () -> Unit = {}

        override fun writeControl(value: ByteArray, done: (Boolean) -> Unit) {
            control += value
            when (value[0].toInt()) {
                LegacyDfu.START -> mode = "sizes"
                LegacyDfu.INIT -> if (value[1].toInt() == 0) {
                    mode = "init"
                } else {
                    notify(byteArrayOf(0x10, 0x02, initStatus.toByte()))
                }
                LegacyDfu.RECEIPTS -> every = value[1].toInt()
                LegacyDfu.RECEIVE -> mode = "image"
                LegacyDfu.VALIDATE -> notify(byteArrayOf(0x10, 0x04, if (image.size() == expected) 1 else 5))
                LegacyDfu.ACTIVATE -> {
                    activated = true
                    if (dropOnActivate) return onLost()
                }
            }
            done(true)
        }

        override fun writePacket(value: ByteArray, done: (Boolean) -> Unit) {
            packets += value
            when (mode) {
                "sizes" -> {
                    sizes = value
                    expected = ByteBuffer.wrap(value, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    due += time + eraseMs to byteArrayOf(0x10, 0x01, startStatus.toByte())
                }
                "init" -> init.write(value)
                "image" -> {
                    packetTimes += time
                    image.write(value)
                    imagePackets++
                    when {
                        imagePackets == failAfterPackets -> notify(byteArrayOf(0x10, 0x03, 6))
                        image.size() >= expected -> notify(byteArrayOf(0x10, 0x03, 1))
                        every > 0 && imagePackets % every == 0 -> if (holdReceipts) heldReceipts++ else receipt()
                    }
                }
            }
            done(true)
        }
    }

    fun dfu(pkg: DfuPackage) = LegacyDfu(pkg, link) { time }

    /** Sends a receipt held back. */
    fun releaseReceipt() {
        if (heldReceipts > 0) {
            heldReceipts--
            receipt()
        }
    }

    private fun receipt() {
        val n = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(image.size()).array()
        notify(byteArrayOf(0x11) + n)
    }

    /** A notification, sent after the write that made it is done. */
    private fun notify(value: ByteArray) {
        due += time to value
    }

    /** Runs the clock forward, from one thing to do to the next, until [dfu] ends, nothing is left to do, or [until]. */
    fun run(dfu: LegacyDfu, until: Long = Long.MAX_VALUE) {
        while (!dfu.isFinished) {
            val now = due.filter { it.first <= time }
            if (now.isNotEmpty()) {
                due -= now.toSet()
                now.forEach { link.onNotification(it.second) }
                continue
            }
            val next = listOfNotNull(dfu.nextDeadline, due.minOfOrNull { it.first }).minOrNull() ?: return
            if (next > until) return
            time = maxOf(time, next)
            dfu.tick()
        }
    }
}
