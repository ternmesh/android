// Finds nodes by the service they advertise. A node advertises nothing that says which node it is
// (the specification forbids it), so what the user picks from is Android's name for the device,
// its Bluetooth address and how strongly it is heard.
package org.ternmesh.app.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Found(val address: String, val name: String?, val rssi: Int)

@SuppressLint("MissingPermission") // The app asks for BLUETOOTH_SCAN before it scans.
class Scanner(private val context: Context) {
    private val _found = MutableStateFlow<List<Found>>(emptyList())
    val found: StateFlow<List<Found>> = _found

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val f = Found(result.device.address, result.scanRecord?.deviceName ?: result.device.name, result.rssi)
            // In the order the nodes were first heard, each kept in its place as its signal
            // changes: sorted by signal, two nodes close by swap places many times a second and
            // cannot be tapped.
            val list = _found.value
            val i = list.indexOfFirst { it.address == f.address }
            _found.value = if (i < 0) list + f else list.toMutableList().also { it[i] = f }
        }

        override fun onScanFailed(errorCode: Int) {
            _scanning.value = false
        }
    }

    fun start() {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
        if (_scanning.value) return
        _found.value = emptyList()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BleLink.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, callback)
        _scanning.value = true
    }

    fun stop() {
        if (!_scanning.value) return
        runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(callback)
        }
        _scanning.value = false
    }
}
