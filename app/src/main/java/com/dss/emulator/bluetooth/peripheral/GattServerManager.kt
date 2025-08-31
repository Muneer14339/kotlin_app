package com.dss.emulator.bluetooth.peripheral

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import com.dss.emulator.bluetooth.Constants
import com.dss.emulator.bluetooth.DataQueueManager

class GattServerManager(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val onDeviceConnected: (BluetoothDevice?) -> Unit,
) {

    private var connectedDevice: BluetoothDevice? = null;
    private var bluetoothGattServer: BluetoothGattServer? = null

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            Log.d("GattServerManager", "Device $device connection state changed: $newState")

            connectedDevice = if (newState == BluetoothProfile.STATE_CONNECTED) {
                device
            } else {
                null
            }

            onDeviceConnected(connectedDevice)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            characteristic?.let {
                when (it.uuid) {
                    Constants.DATA_READ_CHARACTERISTIC_UUID -> {
                        val value = Constants.CHARACTERISTIC_1_VALUE.toByteArray()
                        bluetoothGattServer?.sendResponse(
                            device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value
                        )
                    }

                    Constants.COMMAND_WRITE_CHARACTERISTIC_UUID -> {
                        val value = Constants.CHARACTERISTIC_2_VALUE.toByteArray()
                        bluetoothGattServer?.sendResponse(
                            device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value
                        )
                    }

                    else -> {}
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            characteristic?.let {
                if (it.uuid == Constants.COMMAND_WRITE_CHARACTERISTIC_UUID) {
                    bluetoothGattServer?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null
                    )

                    DataQueueManager.getInstance().addData(value ?: byteArrayOf())
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor?
        ) {
            descriptor?.let {
                if (it.uuid == Constants.CHARACTERISTIC_USER_DESCRIPTION_UUID) {
                    val value = when (it.characteristic.uuid) {
                        Constants.DATA_READ_CHARACTERISTIC_UUID -> Constants.DATA_READ_DESCRIPTOR.toByteArray()
                        Constants.COMMAND_WRITE_CHARACTERISTIC_UUID -> Constants.COMMAND_WRITE_DESCRIPTOR.toByteArray()
                        else -> byteArrayOf()
                    }
                    bluetoothGattServer?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value
                    )
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            descriptor?.let {
                if (it.uuid == Constants.CHARACTERISTIC_USER_DESCRIPTION_UUID) {
                    val receivedValue = String(value ?: byteArrayOf())
                    Log.d("GattServerManager", "Received value for descriptor: $receivedValue")
                    bluetoothGattServer?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startGattServer() {
        try {
            bluetoothGattServer = bluetoothManager.openGattServer(context, gattServerCallback)
            bluetoothGattServer?.addService(buildGattService())
            Log.d("GattServerManager", "GATT Server started")
        } catch (e: Exception) {
            Log.e("GattServerManager", "Failed to start GATT server: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopGattServer() {
        bluetoothGattServer?.close()
        Log.d("GattServerManager", "GATT Server stopped")
    }

    private fun buildGattService(): BluetoothGattService {
        val service = BluetoothGattService(
            Constants.UDB_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // Characteristic for data read
        val dataReadCharacteristic = BluetoothGattCharacteristic(
            Constants.DATA_READ_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        // Descriptor for data read characteristic
        val readDescriptor = BluetoothGattDescriptor(
            Constants.CHARACTERISTIC_USER_DESCRIPTION_UUID, BluetoothGattDescriptor.PERMISSION_READ
        )
        dataReadCharacteristic.addDescriptor(readDescriptor)
        service.addCharacteristic(dataReadCharacteristic)

        // Characteristic for command write
        val commandWriteCharacteristic = BluetoothGattCharacteristic(
            Constants.COMMAND_WRITE_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        // Descriptor for command write characteristic (separate instance)
        val writeDescriptor = BluetoothGattDescriptor(
            Constants.CHARACTERISTIC_USER_DESCRIPTION_UUID, BluetoothGattDescriptor.PERMISSION_READ
        )
        commandWriteCharacteristic.addDescriptor(writeDescriptor)
        service.addCharacteristic(commandWriteCharacteristic)

        return service
    }

    @SuppressLint("MissingPermission")
    fun sendData(data: ByteArray) {
        bluetoothGattServer?.let { server ->
            val characteristic = server.getService(Constants.UDB_SERVICE_UUID)
                ?.getCharacteristic(Constants.DATA_READ_CHARACTERISTIC_UUID)

            if (characteristic == null) {
                Log.e("Bluetooth", "Command Write Characteristic not found")
                return
            }

            characteristic.value = data

            connectedDevice?.let { device ->
                server.notifyCharacteristicChanged(device, characteristic, false)
            } ?: Log.e("Bluetooth", "No connected device to notify")
        }
    }
}
