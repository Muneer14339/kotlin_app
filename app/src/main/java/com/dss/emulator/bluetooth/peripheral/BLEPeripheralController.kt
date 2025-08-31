package com.dss.emulator.bluetooth.peripheral

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log

class BLEPeripheralController(
    context: Context, private val onDeviceConnected: (BluetoothDevice?) -> Unit
) {
    private val advertiser: BLEAdvertiser by lazy { BLEAdvertiser(bluetoothManager.adapter.bluetoothLeAdvertiser) }
    private var bluetoothManager: BluetoothManager =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)!!
    private val isAdvertising = false

    @SuppressLint("MissingPermission")
    private var gattServerManager: GattServerManager =
        GattServerManager(context, bluetoothManager, onDeviceConnected = { device ->
            Log.d("BLEPeripheralController", "Device connected: ${device?.name}")
            onDeviceConnected(device)
        })

    fun startGattServer() {
        gattServerManager.startGattServer()
    }

    fun stopGattServer() {
        gattServerManager.stopGattServer()
    }

    fun startAdvertising() {
        if (!isAdvertising) advertiser.startAdvertising()
        else Log.d("BLEPeripheralController", "Advertising is already running")
    }

    fun stopAdvertising() {
        if (isAdvertising) advertiser.stopAdvertising()
        else Log.d("BLEPeripheralController", "Advertising is not running")
    }

    fun sendData(data: ByteArray) {
        gattServerManager.sendData(data)
    }
}
