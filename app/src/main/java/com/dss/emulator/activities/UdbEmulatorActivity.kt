package com.dss.emulator.activities

import android.annotation.SuppressLint
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
import com.dss.emulator.bluetooth.BLEPermissionsManager
import com.dss.emulator.bluetooth.DataQueueManager
import com.dss.emulator.bluetooth.peripheral.BLEPeripheralController
import com.dss.emulator.core.UDBEmulator
import com.dss.emulator.dsscommand.DSSCommand
import com.dss.emulator.register.Direction
import com.dss.emulator.register.Register
import com.dss.emulator.register.Registers
import com.dss.emulator.register.registerList
import com.dss.emulator.udb.R

class UdbEmulatorActivity : ComponentActivity() {
    // UI Components
    private lateinit var historyTextView: TextView
    private lateinit var statusText: TextView
    private lateinit var tableContainer: LinearLayout
    private lateinit var toggleButton: Button
    private lateinit var viewFirmwareButton: Button

    // Controllers and Managers
    private lateinit var permissionsManager: BLEPermissionsManager
    private lateinit var bleCentralController: BLEPeripheralController
    private lateinit var udbEmulator: UDBEmulator
    private lateinit var dataQueueManager: DataQueueManager

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_udb_emulator)

        initializeComponents()
        setupUI()
        setupBluetooth()
        setupDataQueue()
    }

    private fun initializeComponents() {
        historyTextView = findViewById(R.id.historyTextView)
        statusText = findViewById(R.id.statusText)
        tableContainer = findViewById(R.id.tableContainer)
        toggleButton = findViewById(R.id.toggleButton)
        viewFirmwareButton = findViewById(R.id.viewFirmwareButton)

        historyTextView.text = ""
    }

    private fun setupUI() {
        var isExpanded = false
        toggleButton.setOnClickListener {
            isExpanded = toggleTableVisibility(isExpanded)
        }

        viewFirmwareButton.setOnClickListener {
            showFirmwareDialog()
        }
    }

    private fun toggleTableVisibility(isExpanded: Boolean): Boolean {
        tableContainer.visibility = if (isExpanded) View.GONE else View.VISIBLE
        toggleButton.text = if (isExpanded) "Show Registers" else "Hide Registers"
        return !isExpanded
    }

    private fun setupBluetooth() {
        permissionsManager = BLEPermissionsManager(this) { granted ->
            if (!granted) {
                handleBluetoothPermissionDenied()
            }
        }
        permissionsManager.checkAndRequestPermissions()

        bleCentralController = BLEPeripheralController(this, onDeviceConnected = { device ->
            handleDeviceConnection(device)
        })
        bleCentralController.startAdvertising()
        bleCentralController.startGattServer()
    }

    private fun handleBluetoothPermissionDenied() {
        Log.e("Permissions", "Bluetooth permissions denied")
        AlertDialog.Builder(this).setTitle("Bluetooth Permissions Denied")
            .setMessage("Please grant Bluetooth permissions to use this app.")
            .setPositiveButton("OK") { _, _ -> finish() }.show()
    }

    private fun handleDeviceConnection(device: android.bluetooth.BluetoothDevice?) {
        if (device == null) {
            bleCentralController.startAdvertising()
            dataQueueManager.pause()
            updateStatusText("Waiting For Connection...")
        } else {
            bleCentralController.stopAdvertising()
            dataQueueManager.resume()
            updateStatusText("Device: ${device.address}")
            showDeviceConnectedDialog(device)
        }
    }

    private fun updateStatusText(text: String) {
        runOnUiThread {
            statusText.text = text
        }
    }

    private fun showDeviceConnectedDialog(device: android.bluetooth.BluetoothDevice) {
        runOnUiThread {
            AlertDialog.Builder(this).setTitle("Device Connected")
                .setMessage("Device: ${device.address}").setPositiveButton("OK") { _, _ -> }.show()
        }
    }

    private fun setupDataQueue() {
        dataQueueManager = DataQueueManager.getInstance()
        dataQueueManager.start()

        udbEmulator = UDBEmulator(this, bleCentralController)

        dataQueueManager.addListener { data ->
            udbEmulator.onReceiveData(data)
            updateHistoryTextView()
            updateRegisterTable()
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
        bleCentralController.stopGattServer()
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
        if (register.direction == Direction.BOTH || register.direction == Direction.UDB_TO_GUI) {
            showEditDialog(register, valueTextView)
        } else {
            Toast.makeText(this, "${register.name} is not editable", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateHistoryTextView() {
        runOnUiThread {
            historyTextView.text = udbEmulator.getCommandHistory()
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

            val rMap = 1L shl register.regMapBit
            Registers.REG_MAP.setValue(rMap)
            sendCommand(DSSCommand.createRMCommand("UDB", "RC-RI", rMap))

            updateRegisterTable()
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid input: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendCommand(command: DSSCommand) {
        udbEmulator.sendCommand(command)
        updateHistoryTextView()
    }

    private fun showFirmwareDialog() {
        val firmwareLines = udbEmulator.getFirmwareLines()
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_firmware, null)
        val firmwareTextView = dialogView.findViewById<TextView>(R.id.firmwareTextView)
        
        firmwareTextView.text = firmwareLines.joinToString("\n")

        val dialog = AlertDialog.Builder(this)
            .setTitle("Firmware Content")
            .setView(dialogView)
            .create()

        dialog.setOnShowListener {
            val width = resources.displayMetrics.widthPixels * 0.9
            val height = resources.displayMetrics.heightPixels * 0.8
            dialog.window?.setLayout(width.toInt(), height.toInt())
        }

        dialog.show()
    }

    // Extension function to convert dp to pixels
    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()
}
