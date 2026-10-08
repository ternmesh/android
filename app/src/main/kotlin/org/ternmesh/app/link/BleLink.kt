// The companion protocol's Bluetooth LE link, on Android's GATT client: the node's one service, a
// frame per write to one characteristic and a frame per notification on the other, an MTU that
// fits the longest frame, and passkey pairing before either may be used.
//
// Android calls back on its own binder threads; this posts every callback to [handler], so the
// connection above it is only ever touched from one thread.
package org.ternmesh.app.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import androidx.core.content.ContextCompat
import org.ternmesh.companion.Companion as Protocol
import java.util.UUID

/** Why a link could not open, or closed. */
enum class LinkFailure {
    /** Bluetooth is off, or the phone has none. */
    BLUETOOTH_OFF,

    /** The node offers no Tern service: not a Tern node, or its firmware is too old. */
    NO_SERVICE,

    /** The phone and node agreed an MTU too small for the longest frame. */
    MTU,

    /** Pairing failed or was cancelled: a wrong passkey, most often. */
    PAIRING,

    /** The link dropped. */
    LOST,
}

sealed interface LinkState {
    data object Closed : LinkState

    /** Waiting for the node: in range, or for Android to reach it. */
    data object Connecting : LinkState

    /** Android is asking the user for the node's passkey. */
    data object Pairing : LinkState

    /** Frames may go both ways. */
    data object Open : LinkState

    data class Failed(val why: LinkFailure) : LinkState
}

@SuppressLint("MissingPermission") // The app asks for BLUETOOTH_CONNECT before it makes a link.
class BleLink(private val context: Context, private val handler: Handler) {
    var onState: (LinkState) -> Unit = {}

    /** One frame from the node. */
    var onFrame: (ByteArray) -> Unit = {}

    var state: LinkState = LinkState.Closed
        private set

    private var gatt: BluetoothGatt? = null
    private var toNode: BluetoothGattCharacteristic? = null

    /** Frames to write, one write in flight at a time, as GATT requires. */
    private val writes = ArrayDeque<ByteArray>()
    private var writing = false
    private var bondWatch: BroadcastReceiver? = null

    /**
     * Connects to the node at [address]. With [waitForIt], Android connects whenever the node comes
     * into range, however long that takes; without, it gives up after about half a minute.
     */
    fun connect(address: String, waitForIt: Boolean) {
        disconnect()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) return set(LinkState.Failed(LinkFailure.BLUETOOTH_OFF))
        val device = adapter.getRemoteDevice(address)
        set(LinkState.Connecting)
        gatt = device.connectGatt(context, waitForIt, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        stopWatchingBond()
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        toNode = null
        writes.clear()
        writing = false
        if (state != LinkState.Closed) set(LinkState.Closed)
    }

    /** Writes one frame, after those before it. */
    fun write(frame: ByteArray) {
        if (state != LinkState.Open) return
        writes.addLast(frame)
        writeNext()
    }

    private fun writeNext() {
        val g = gatt ?: return
        val c = toNode ?: return
        if (writing) return
        val frame = writes.removeFirstOrNull() ?: return
        writing = true
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, frame, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.writeType = type
            @Suppress("DEPRECATION")
            c.value = frame
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
        if (!started) {
            // The stack is busy or the link is going: the connection's answer timer covers the frame.
            writing = false
        }
    }

    private fun set(s: LinkState) {
        state = s
        onState(s)
    }

    private fun fail(why: LinkFailure) {
        disconnect()
        set(LinkState.Failed(why))
    }

    /** Runs [block] on the link's thread, if [g] is still the link's. */
    private fun on(g: BluetoothGatt, block: () -> Unit) {
        handler.post { if (g === gatt) block() }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = on(g) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // The longest frame and ATT's 3 bytes; Android asks for this much and the node
                    // agrees to at least it, or answers HELLO with ERROR 7.
                    if (!g.requestMtu(MTU)) fail(LinkFailure.LOST)
                }
                BluetoothProfile.STATE_DISCONNECTED -> fail(LinkFailure.LOST)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = on(g) {
            if (status != BluetoothGatt.GATT_SUCCESS || mtu < Protocol.MIN_MTU) return@on fail(LinkFailure.MTU)
            if (!g.discoverServices()) fail(LinkFailure.LOST)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = on(g) {
            val service = g.getService(SERVICE)
            val to = service?.getCharacteristic(TO_NODE)
            val from = service?.getCharacteristic(FROM_NODE)
            if (status != BluetoothGatt.GATT_SUCCESS || to == null || from == null) return@on fail(LinkFailure.NO_SERVICE)
            toNode = to
            // Both characteristics need an encrypted link from passkey pairing. Bonding first,
            // rather than letting the first write fail into it, gives the user one prompt and
            // this link one clear outcome.
            when (g.device.bondState) {
                BluetoothDevice.BOND_BONDED -> subscribe(g)
                BluetoothDevice.BOND_BONDING -> awaitBond(g)
                else -> {
                    awaitBond(g)
                    if (!g.device.createBond()) fail(LinkFailure.PAIRING)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = on(g) {
            if (d.uuid != CCCD) return@on
            if (status == BluetoothGatt.GATT_SUCCESS) {
                set(LinkState.Open)
            } else {
                // Refused for want of encryption: the bond the phone held is one the node has
                // since cleared.
                fail(LinkFailure.PAIRING)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = on(g) {
            writing = false
            writeNext()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val frame = value.copyOf()
            on(g) { if (c.uuid == FROM_NODE) onFrame(frame) }
        }

        @Deprecated("Android 12 and earlier")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            val frame = c.value?.copyOf() ?: return
            on(g) { if (c.uuid == FROM_NODE) onFrame(frame) }
        }
    }

    private fun subscribe(g: BluetoothGatt) {
        val from = g.getService(SERVICE)?.getCharacteristic(FROM_NODE) ?: return fail(LinkFailure.NO_SERVICE)
        val cccd = from.getDescriptor(CCCD) ?: return fail(LinkFailure.NO_SERVICE)
        g.setCharacteristicNotification(from, true)
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
        if (!started) fail(LinkFailure.LOST)
    }

    /** Waits for Android's pairing, which shows the user its own passkey prompt. */
    private fun awaitBond(g: BluetoothGatt) {
        set(LinkState.Pairing)
        stopWatchingBond()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                if (device?.address != g.device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> on(g) {
                        stopWatchingBond()
                        subscribe(g)
                    }
                    BluetoothDevice.BOND_NONE -> on(g) { fail(LinkFailure.PAIRING) }
                }
            }
        }
        bondWatch = receiver
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private fun stopWatchingBond() {
        bondWatch?.let { runCatching { context.unregisterReceiver(it) } }
        bondWatch = null
    }

    companion object {
        val SERVICE: UUID = UUID.fromString(Protocol.SERVICE)
        val TO_NODE: UUID = UUID.fromString(Protocol.TO_NODE)
        val FROM_NODE: UUID = UUID.fromString(Protocol.FROM_NODE)

        /** The Client Characteristic Configuration descriptor, which turns notifications on. */
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Phones commonly offer 185 or more; anything from [Protocol.MIN_MTU] carries every frame. */
        const val MTU = 185

        fun isOn(context: Context): Boolean =
            context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

        fun enableIntent() = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
    }
}
