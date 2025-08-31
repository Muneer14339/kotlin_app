package com.dss.emulator.bluetooth.central

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.util.Log
import com.dss.emulator.BLEDevice
import com.dss.emulator.bluetooth.Constants


class BLEScanner(
    private val bluetoothManager: BluetoothManager,
    private val onDeviceFound: (BLEDevice) -> Unit
) {
    private val scannedDevices = mutableListOf<BLEDevice>()

    private var isScanning = false

    private val scanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e("BLEScanner", "Scan failed with error: $errorCode")
        }

        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.device?.let { deviceInfo ->
                val device = BLEDevice(
                    address = deviceInfo.address,
                    name = deviceInfo.name ?: "Unknown",
                    rssi = result.rssi
                )
                if (scannedDevices.none { it.address == device.address }) {
                    scannedDevices.add(device)
                    Log.d("BLEScanner", "Device found: ${device.address} ${device.name}")
                    onDeviceFound(device)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (isScanning) {
            Log.d("BLEScanner", "Scanner already running.")
            return
        }

        val scanner = bluetoothManager.adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e("BLEScanner", "bluetoothLeScanner is null - check adapter or Bluetooth state.")
            return
        }

        scannedDevices.clear()
        scanner.startScan(null, Constants.SCAN_SETTINGS, scanCallback)
        isScanning = true
        Log.d("BLEScanner", "BLE scan started successfully.")
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (!isScanning) {
            Log.d("BLEScanner", "Scanner already stopped.")
            return
        }

        val scanner = bluetoothManager.adapter.bluetoothLeScanner
        if (scanner != null) {
            scanner.stopScan(scanCallback)
            Log.d("BLEScanner", "BLE scan stopped.")
        } else {
            Log.e("BLEScanner", "Cannot stop scan, bluetoothLeScanner is null.")
        }
        isScanning = false
    }
}
