package com.dss.emulator.activities

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dss.emulator.BLEDevice
import com.dss.emulator.BLEDeviceAdapter
import com.dss.emulator.bluetooth.central.BLECentralController
import com.dss.emulator.udb.R

class FindDevicesDialog(
    private val bleCentralController: BLECentralController,
    context: Context,
    private val onDeviceSelected: (BLEDevice) -> Unit
) : ComponentDialog(context) {

    private var BLEDeviceAdapter: BLEDeviceAdapter = BLEDeviceAdapter { device ->
        onDeviceSelected(device)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_OPTIONS_PANEL)
        setContentView(R.layout.dialog_find_devices)

        setCanceledOnTouchOutside(false) // Prevent dialog from closing when clicking outside

        setupRecyclerView()
        setupDialogWindow()
    }

    private fun setupRecyclerView() {
        val recyclerView: RecyclerView = findViewById(R.id.recyclerView)
        recyclerView.adapter = BLEDeviceAdapter
        recyclerView.layoutManager = LinearLayoutManager(this.context)
    }

    private fun setupDialogWindow() {
        window?.apply {

            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.7f) // adjust darkness as desired

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setBackgroundBlurRadius(50)
            }

            val displayMetrics = context.resources.displayMetrics

            val params = attributes.apply {
                width = (displayMetrics.widthPixels * 0.8).toInt()   // 90% width
                height = (displayMetrics.heightPixels * 0.8).toInt() // 80% height
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL   // Position at top-center
                y =
                    (displayMetrics.heightPixels * 0.1).toInt()     // Optional: small margin from top (5% offset)
            }

            attributes = params
        }
    }

    fun startScanning() {
        BLEDeviceAdapter.updateDeviceList(emptyList())
        bleCentralController.startScanning()
    }

    fun stopScanning() {
        bleCentralController.stopScanning()
    }

    fun addDevice(device: BLEDevice) {
        BLEDeviceAdapter.addDevice(device)
    }
}
