package com.dss.emulator.bluetooth.central

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.dss.emulator.BLEDevice
import com.dss.emulator.bluetooth.Constants
import com.dss.emulator.bluetooth.DataQueueManager

class BLECentralController(
    private val context: Context,
    private val onDeviceFound: (BLEDevice) -> Unit,
    private val onDeviceConnected: (BluetoothDevice) -> Unit
) {
    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                Log.d("BLE Gatt", "Connected to GATT server.")
                gatt.discoverServices()
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("BLE Gatt", "Disconnected from GATT server.")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLE Gatt", "MTU changed to $mtu")
            } else {
                Log.e("BLE Gatt", "MTU change failed with status $status")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val services = gatt.services

                var isUDBDevice = false
                for (service in services) {
                    if (service.uuid.equals(Constants.UDB_SERVICE_UUID)) {
                        isUDBDevice = true
                        val characteristic =
                            service.getCharacteristic(Constants.DATA_READ_CHARACTERISTIC_UUID)
                        gatt!!.setCharacteristicNotification(characteristic, true)
                    }
                }
                if (isUDBDevice) {
                    gatt.requestMtu(512)
                    onDeviceConnected(gatt.device)
                }

            } else {
                Log.w("BLE Gatt", "onServicesDiscovered received: $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {

            when (characteristic.uuid) {
                Constants.DATA_READ_CHARACTERISTIC_UUID -> {
                    val data = characteristic.value
                    if (data != null) {
                        Log.d("BLE Gatt", "Data read: ${data.toString()}")
                        DataQueueManager.getInstance().addData(data)
                    }
                }
            }
        }
    }

    private val scanner: BLEScanner by lazy {
        BLEScanner(
            bluetoothManager = bluetoothManager!!, onDeviceFound
        )
    }

    fun startScanning() {
        scanner.startScanning()
    }

    fun stopScanning() {
        scanner.stopScanning()
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BLEDevice) {
        bluetoothGatt?.let { gatt ->
            gatt.disconnect()
            gatt.close()
            bluetoothGatt = null
        }

        val bluetoothDevice = bluetoothManager?.adapter?.getRemoteDevice(device.address)
        bluetoothGatt = bluetoothDevice?.connectGatt(
            context, false, gattCallback
        )

        Log.d("BLECentralController", "Connecting to device: ${device.address}")
    }

    @SuppressLint("MissingPermission")
    fun sendData(data: ByteArray) {
        bluetoothGatt?.let { gatt ->
            val characteristic = gatt.getService(Constants.UDB_SERVICE_UUID)
                ?.getCharacteristic(Constants.COMMAND_WRITE_CHARACTERISTIC_UUID)

            characteristic?.let {
                it.value = data
                it.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                gatt.writeCharacteristic(it)
            } ?: Log.e("Bluetooth", "Characteristic not found")
        } ?: Log.e("Bluetooth", "BluetoothGatt is null")
    }
}
