package com.dss.emulator.activities

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.dss.emulator.bluetooth.BLEPermissionsManager
import com.dss.emulator.bluetooth.DataQueueManager
import com.dss.emulator.bluetooth.central.BLECentralController
import com.dss.emulator.core.RCRIEmulator
import com.dss.emulator.dsscommand.DSSCommand
import com.dss.emulator.fake.FakeFirmwareGenerator
import com.dss.emulator.register.Direction
import com.dss.emulator.register.Register
import com.dss.emulator.register.registerList
import com.dss.emulator.udb.R
import kotlinx.coroutines.launch

class RcRiEmulatorActivity : ComponentActivity() {
    // UI Components
    private lateinit var historyTextView: TextView
    private lateinit var releaseStateTextView: TextView
    private lateinit var tableContainer: LinearLayout
    private lateinit var toggleButton: Button

    // Controllers and Managers
    private lateinit var permissionsManager: BLEPermissionsManager
    private lateinit var devicesDialog: FindDevicesDialog
    private lateinit var bleCentralController: BLECentralController
    private lateinit var rcriEmulator: RCRIEmulator
    private lateinit var dataQueueManager: DataQueueManager

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rc_ri_emulator)

        initializeComponents()
        setupUI()
        setupBluetooth()
        setupDataQueue()
        showDeviceListDialog()
    }

    private fun initializeComponents() {
        historyTextView = findViewById(R.id.historyTextView)
        releaseStateTextView = findViewById(R.id.releaseStateTextView)
        tableContainer = findViewById(R.id.tableContainer)
        toggleButton = findViewById(R.id.toggleButton)

        historyTextView.text = ""
    }

    private fun setupUI() {
        var isExpanded = false
        toggleButton.setOnClickListener {
            isExpanded = toggleTableVisibility(isExpanded)
        }

        setupCommandButtons()
    }

    private fun toggleTableVisibility(isExpanded: Boolean): Boolean {
        tableContainer.visibility = if (isExpanded) View.GONE else View.VISIBLE
        toggleButton.text = if (isExpanded) "Show Registers" else "Hide Registers"
        return !isExpanded
    }

    private fun setupCommandButtons() {
        findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.commandRBButton).setOnClickListener {
            sendCommand(DSSCommand.createRBCommand("RC-RI", "UDB"))
        }

        findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.commandFTButton).setOnClickListener {
            sendCommand(DSSCommand.createFTCommand("RC-RI", "UDB"))
        }

        findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.commandGIButton).setOnClickListener {
            sendCommand(DSSCommand.createGICommand("RC-RI", "UDB"))
        }

        findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.popupIdleButton).setOnClickListener {
            rcriEmulator.popupIdle()
            updateReleaseStateTextView()
            updateHistoryTextView()
        }

        findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.popupInitButton).setOnClickListener {
            rcriEmulator.popupInit()
            updateHistoryTextView()
        }

        findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.popupConnectButton).setOnClickListener {
            rcriEmulator.popupConnect()
            updateHistoryTextView()
        }

        findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.firmwareUpdateButton).setOnClickListener {
            updateFirmware()
        }
    }

    private fun setupBluetooth() {
        permissionsManager = BLEPermissionsManager(this) { granted ->
            if (!granted) {
                handleBluetoothPermissionDenied()
            }
        }

        bleCentralController = BLECentralController(context = this, onDeviceFound = { device ->
            runOnUiThread {
                devicesDialog.addDevice(device)
            }
        }, onDeviceConnected = { device ->
            handleDeviceConnected(device)
        })

        rcriEmulator = RCRIEmulator(this, bleCentralController)

        devicesDialog = FindDevicesDialog(bleCentralController = bleCentralController,
            context = this,
            onDeviceSelected = { device ->
                Log.d("DeviceSelected", "Selected device: ${device.name} ${device.address}")
                bleCentralController.connectToDevice(device)
            })
    }

    private fun handleBluetoothPermissionDenied() {
        Log.e("RcRiEmulatorActivity", "Bluetooth permissions denied")
        AlertDialog.Builder(this).setTitle("Bluetooth Permissions Denied")
            .setMessage("Please grant Bluetooth permissions to use this app.")
            .setPositiveButton("OK") { _, _ -> finish() }.show()
    }

    @SuppressLint("MissingPermission")
    private fun handleDeviceConnected(device: android.bluetooth.BluetoothDevice) {
        runOnUiThread {
            devicesDialog.stopScanning()
            devicesDialog.dismiss()
            dataQueueManager.resume()

            AlertDialog.Builder(this).setTitle("Connected to ${device.name}")
                .setMessage("Connected to ${device.name}").setPositiveButton("OK") { _, _ -> }
                .show()
        }
    }

    private fun setupDataQueue() {
        dataQueueManager = DataQueueManager.getInstance()
        dataQueueManager.start()

        dataQueueManager.addListener { data ->
            Log.d("RcRiEmulatorActivity", "Received data length: ${data.size}")
            rcriEmulator.onReceiveData(data)
            updateHistoryTextView()
            updateRegisterTable()
            updateReleaseStateTextView()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!dataQueueManager.isActive()) {
            dataQueueManager.start()
        }
        dataQueueManager.resume()
    }

    override fun onPause() {
        super.onPause()
        dataQueueManager.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        dataQueueManager.stop()
    }

    private fun showDeviceListDialog() {
        devicesDialog.show()
        permissionsManager.checkAndRequestPermissions()
        devicesDialog.startScanning()
    }

    private fun sendCommand(command: DSSCommand) {
        rcriEmulator.sendCommand(command)
        updateHistoryTextView()
    }

    private fun updateRegisterTable() {
        runOnUiThread {
            val tableLayout = findViewById<TableLayout>(R.id.tableData)
            tableLayout.removeAllViews()

            registerList.forEachIndexed { index, register ->
                val tableRow = createRegisterTableRow(index, register)
                tableLayout.addView(tableRow)
            }
        }
    }

    private fun createRegisterTableRow(index: Int, register: Register): TableRow {
        return TableRow(this).apply {
            addView(createNumberTextView(index))
            addView(createNameTextView(register))
            addView(createValueTextView(register))
            addView(createDirectionTextView(register))
        }
    }

    private fun createNumberTextView(index: Int): TextView {
        return TextView(this).apply {
            layoutParams = TableRow.LayoutParams(40.dpToPx(), TableRow.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER
            setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
            text = (index + 1).toString()
        }
    }

    private fun createNameTextView(register: Register): TextView {
        return TextView(this).apply {
            layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
            text = register.name
        }
    }

    private fun createValueTextView(register: Register): TextView {
        return TextView(this).apply {
            layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
            text = register.getValueString() ?: "null"
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { handleValueClick(register, this) }
        }
    }

    private fun createDirectionTextView(register: Register): TextView {
        return TextView(this).apply {
            layoutParams = TableRow.LayoutParams(100.dpToPx(), TableRow.LayoutParams.WRAP_CONTENT)
            setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
            text = register.direction.toString()
        }
    }

    private fun handleValueClick(register: Register, valueTextView: TextView) {
        if (register.direction == Direction.GUI_TO_UDB || register.direction == Direction.BOTH) {
            showEditDialog(register, valueTextView)
        } else {
            sendCommand(DSSCommand.createGTCommand("RC-RI", "UDB", register.name))
        }
    }

    private fun showEditDialog(register: Register, valueTextView: TextView) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_register, null)
        setupDialogViews(dialogView, register)

        AlertDialog.Builder(this).setView(dialogView).setPositiveButton("OK") { _, _ ->
            handleRegisterUpdate(
                register, valueTextView, dialogView
            )
        }.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }.create().show()
    }

    private fun setupDialogViews(dialogView: View, register: Register) {
        dialogView.findViewById<TextView>(R.id.register_name).text = "Name: ${register.name}"
        dialogView.findViewById<TextView>(R.id.register_value_type).text =
            "Value Type: ${register.dataType}"
        dialogView.findViewById<TextView>(R.id.register_description).text =
            "Description: ${register.description}"
        dialogView.findViewById<TextView>(R.id.register_direction).text =
            "Direction: ${register.direction}"
        dialogView.findViewById<EditText>(R.id.register_value_input)
            .setText(register.getValue().toString())
    }

    private fun handleRegisterUpdate(
        register: Register, valueTextView: TextView, dialogView: View
    ) {
        val newValue = dialogView.findViewById<EditText>(R.id.register_value_input).text.toString()
        try {
            register.setValueString(newValue)
            valueTextView.text = register.getValueString() ?: "null"
            sendCommand(
                DSSCommand.createSTCommand(
                    "RC-RI", "UDB", register.name, register.getValue().toString()
                )
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid input: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateHistoryTextView() {
        runOnUiThread {
            historyTextView.text = rcriEmulator.getCommandHistory()
        }
    }

    private fun updateReleaseStateTextView() {
        runOnUiThread {
            releaseStateTextView.text = rcriEmulator.getReleaseState().toString()
            releaseStateTextView.setBackgroundColor(Color.BLUE)
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()
    private fun updateFirmware() {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Uploading Firmware")
            .setMessage("Please wait...")
            .setCancelable(false)
            .create()

        progressDialog.show()

        lifecycleScope.launch {
            try {
                val firmwareStream = FakeFirmwareGenerator.generate()

                rcriEmulator.setPendingAckCallback {
                    runOnUiThread {
                        updateHistoryTextView()
                    }
                }

                rcriEmulator.uploadFirmwareFromStream(firmwareStream) { progress ->
                    runOnUiThread {
                        progressDialog.setMessage("Uploading firmware... $progress%")
                    }
                }

                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this@RcRiEmulatorActivity, "Firmware upload complete", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressDialog.dismiss()
                    // Show Failed Dialog
                    val failedDialog = AlertDialog.Builder(this@RcRiEmulatorActivity)
                        .setTitle("Upload Failed")
                        .setMessage("Failed to upload firmware: ${e.message}")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .create()
                    failedDialog.show()
                    Toast.makeText(this@RcRiEmulatorActivity, "Upload failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
