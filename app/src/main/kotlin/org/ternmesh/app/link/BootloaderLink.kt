// An nRF52840 node's bootloader, on Android's GATT client: finding it by the legacy DFU service it
// advertises once the node restarts into it, and the link LegacyDfu sends the firmware over, a
// control point written with a response whose notifications are on, and a packet characteristic
// written without one.
//
// As with BleLink, every callback is posted to [handler], so the transfer above it is only ever
// touched from one thread.
package org.ternmesh.app.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.ParcelUuid
import org.ternmesh.companion.DfuLink
import org.ternmesh.companion.LegacyDfu
import java.util.UUID

/**
 * Looks for a bootloader advertising the DFU service, for up to [FIND_MS]. It is matched by its
 * service, not its address: Adafruit's bootloader advertises at the node's address plus one in the
 * last byte, others at the node's own, so one at either is preferred when more than one is heard.
 */
@SuppressLint("MissingPermission") // The app has BLUETOOTH_SCAN from choosing the node.
class BootloaderScan(private val context: Context, private val handler: Handler) {
    private var callback: ScanCallback? = null
    private val heard = mutableMapOf<String, Int>()

    /** Calls [found] once, on the handler's thread, with the bootloader's address, or null if none was heard. */
    fun find(node: String, found: (String?) -> Unit) {
        stop()
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
            ?: return run { handler.post { found(null) } }
        heard.clear()
        var answered = false
        fun answer(address: String?) {
            if (answered) return
            answered = true
            stop()
            found(address)
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address
                val rssi = result.rssi
                handler.post {
                    if (callback !== this) return@post
                    heard[address] = rssi
                    if (isNodesBootloader(node, address)) return@post answer(address)
                    // Another's bootloader, or one at an address of its own: give the node's a moment.
                    if (heard.size == 1) handler.postDelayed({ if (callback === this) answer(strongest()) }, SETTLE_MS)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                handler.post { if (callback === this) answer(null) }
            }
        }
        callback = cb
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(listOf(filter), settings, cb) }.onFailure { return answer(null) }
        handler.postDelayed({ if (callback === cb) answer(strongest()) }, FIND_MS)
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(cb) }
    }

    private fun strongest(): String? = heard.maxByOrNull { it.value }?.key

    companion object {
        val SERVICE: UUID = UUID.fromString(LegacyDfu.SERVICE)

        /** How long the node has to restart into its bootloader and advertise: it flushes what it holds first. */
        const val FIND_MS = 30_000L

        /** After hearing a bootloader at neither of the node's addresses, how long to listen for one that is. */
        private const val SETTLE_MS = 3_000L

        /** Whether [address] is where the bootloader of the node at [node] advertises: the same, or one more in the last byte. */
        fun isNodesBootloader(node: String, address: String): Boolean {
            if (node.equals(address, ignoreCase = true)) return true
            val a = node.split(':')
            val b = address.split(':')
            if (a.size != 6 || b.size != 6 || a.take(5).map(String::uppercase) != b.take(5).map(String::uppercase)) return false
            val last = a[5].toIntOrNull(16) ?: return false
            return b[5].toIntOrNull(16) == (last + 1) and 0xFF
        }
    }
}

/**
 * The GATT link to a bootloader. connect() opens it and turns the control point's notifications
 * on; LegacyDfu then writes one thing at a time and waits for each to be done.
 */
@SuppressLint("MissingPermission") // The app has BLUETOOTH_CONNECT from choosing the node.
class BootloaderLink(private val context: Context, private val handler: Handler) : DfuLink {
    override var onNotification: (ByteArray) -> Unit = {}
    override var onLost: () -> Unit = {}

    private var gatt: BluetoothGatt? = null
    private var control: BluetoothGattCharacteristic? = null
    private var packet: BluetoothGattCharacteristic? = null
    private var opened: ((Boolean) -> Unit)? = null
    private var open = false
    private var pending: ((Boolean) -> Unit)? = null
    private var attempts = 0
    private var address: String? = null

    /** Opens the link to the bootloader at [address]; [ready] is called once, with whether it is ready for the transfer. */
    fun connect(address: String, ready: (Boolean) -> Unit) {
        close()
        this.address = address
        opened = ready
        attempts = 0
        attempt()
    }

    private fun attempt() {
        val address = address ?: return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) return notOpened()
        attempts++
        gatt = adapter.getRemoteDevice(address).connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun close() {
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        control = null
        packet = null
        open = false
        opened = null
        pending = null
    }

    override fun writeControl(value: ByteArray, done: (Boolean) -> Unit) =
        write(control, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, done)

    override fun writePacket(value: ByteArray, done: (Boolean) -> Unit) =
        write(packet, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, done)

    private fun write(c: BluetoothGattCharacteristic?, value: ByteArray, type: Int, done: (Boolean) -> Unit) {
        val g = gatt
        if (g == null || c == null || !open) return run { handler.post { done(false) } }
        pending = done
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.writeType = type
            @Suppress("DEPRECATION")
            c.value = value
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
        if (!started) {
            pending = null
            handler.post { done(false) }
        }
    }

    private fun notOpened() {
        val ready = opened ?: return
        close()
        ready(false)
    }

    private fun on(g: BluetoothGatt, block: () -> Unit) {
        handler.post { if (g === gatt) block() }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = on(g) {
            when (newState) {
                // The packets stay 20 bytes, which fit any MTU; a larger one only spares the stack.
                BluetoothProfile.STATE_CONNECTED -> if (!g.requestMtu(MTU)) g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> when {
                    open -> {
                        open = false
                        pending = null
                        onLost()
                    }
                    // A bootloader just up often refuses the first connection (GATT 133): try again.
                    attempts < ATTEMPTS -> {
                        g.close()
                        gatt = null
                        handler.postDelayed(::attempt, 500)
                    }
                    else -> notOpened()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = on(g) {
            if (!g.discoverServices()) notOpened()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = on(g) {
            val service = g.getService(BootloaderScan.SERVICE)
            val c = service?.getCharacteristic(CONTROL)
            val p = service?.getCharacteristic(PACKET)
            val cccd = c?.getDescriptor(BleLink.CCCD)
            if (status != BluetoothGatt.GATT_SUCCESS || c == null || p == null || cccd == null) return@on notOpened()
            control = c
            packet = p
            if (!g.setCharacteristicNotification(c, true)) return@on notOpened()
            val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            if (!started) notOpened()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = on(g) {
            if (d.uuid != BleLink.CCCD) return@on
            if (status != BluetoothGatt.GATT_SUCCESS) return@on notOpened()
            open = true
            val ready = opened
            opened = null
            ready?.invoke(true)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = on(g) {
            val done = pending ?: return@on
            pending = null
            done(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val v = value.copyOf()
            on(g) { if (c.uuid == CONTROL && open) onNotification(v) }
        }

        @Deprecated("Android 12 and earlier")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            val v = c.value?.copyOf() ?: return
            on(g) { if (c.uuid == CONTROL && open) onNotification(v) }
        }
    }

    companion object {
        val CONTROL: UUID = UUID.fromString(LegacyDfu.CONTROL)
        val PACKET: UUID = UUID.fromString(LegacyDfu.PACKET)

        /** Asked for, not needed: every packet is 20 bytes. */
        const val MTU = 247

        private const val ATTEMPTS = 3
    }
}
