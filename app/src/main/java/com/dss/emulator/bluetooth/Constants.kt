// File: Constants.kt
package com.dss.emulator.bluetooth

import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

object Constants {

    // Service and Characteristic UUIDs
//    var UDB_SERVICE_UUID: UUID = UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB");
    val UDB_SERVICE_UUID: UUID = UUID.fromString("0003ABCD-0000-1000-8000-00805F9B0131")
    val DATA_READ_CHARACTERISTIC_UUID: UUID =
        UUID.fromString("00031201-0000-1000-8000-00805f9b0130")
    val COMMAND_WRITE_CHARACTERISTIC_UUID: UUID =
        UUID.fromString("00031202-0000-1000-8000-00805f9b0130")
    val CHARACTERISTIC_USER_DESCRIPTION_UUID: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    // Device Name
    const val DEVICE_NAME: String = "UDB-E"

    // Characteristic Values
    const val CHARACTERISTIC_1_VALUE: String = "Hello from Characteristic 1"
    const val CHARACTERISTIC_2_VALUE: String = "Hello from Characteristic 2"

    // Descriptor Names
    const val DATA_READ_DESCRIPTOR: String = "DATA_READ"
    const val COMMAND_WRITE_DESCRIPTOR: String = "COMMAND_WRITE"

    // Required Permissions
    val REQUIRED_PERMISSIONS: List<String> = buildRequiredPermissions()
    private fun buildRequiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12 (API 31) and above
            permissions.add(android.Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(android.Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            // Android versions below 12
            permissions.add(android.Manifest.permission.BLUETOOTH)
            permissions.add(android.Manifest.permission.BLUETOOTH_ADMIN)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // Android 10 (API 29) and above
            permissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            permissions.add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        return permissions
    }


    // Advertise Settings
    val ADVERTISE_SETTINGS: AdvertiseSettings = AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
        .setConnectable(true)
        .setTimeout(0)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
        .build()

    // Advertise Data
    fun getAdvertiseData(): AdvertiseData {
        val builder = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(UDB_SERVICE_UUID))

        val advertiseData = builder.build()

        // Log the size of the advertisement data
        Log.d(
            "AdvertiseData",
            "Manufacturer Specific Data Size: ${advertiseData.manufacturerSpecificData.size()}"
        )
        Log.d("AdvertiseData", "Service UUIDs Size: ${advertiseData.serviceUuids.size}")

        return advertiseData
    }

    // Scan Settings
    val SCAN_SETTINGS: android.bluetooth.le.ScanSettings =
        android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
}