package com.dss.emulator.bluetooth.peripheral

import android.annotation.SuppressLint
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.BluetoothLeAdvertiser
import android.util.Log
import com.dss.emulator.bluetooth.Constants

class BLEAdvertiser(
    private val advertiser: BluetoothLeAdvertiser
) {

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: android.bluetooth.le.AdvertiseSettings) {
            Log.d("BLE", "Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e("BLEAdvertiser", "Advertising failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        advertiser.startAdvertising(
            Constants.ADVERTISE_SETTINGS, Constants.getAdvertiseData(), advertiseCallback
        )
        Log.d("BLEAdvertiser", "Started advertising")
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        advertiser.stopAdvertising(advertiseCallback)
        Log.d("BLEAdvertiser", "Stopped advertising")
    }
}
