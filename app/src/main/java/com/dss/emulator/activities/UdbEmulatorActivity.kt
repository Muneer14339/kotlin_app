package com.dss.emulator.activities

import android.annotation.SuppressLint
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.*
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
import kotlin.random.Random

class UdbEmulatorActivity : ComponentActivity(), SensorEventListener {
    // UI Components
    private lateinit var historyTextView: TextView
    private lateinit var statusText: TextView
    private lateinit var connectionStatusText: TextView
    private lateinit var currentOperationText: TextView
    private lateinit var rangeValueText: TextView
    private lateinit var batteryLevelText: TextView
    private lateinit var temperatureText: TextView
    private lateinit var depthText: TextView
    private lateinit var noiseBar: ProgressBar
    private lateinit var noiseValueText: TextView
    private lateinit var tableContainer: LinearLayout
    private lateinit var toggleButton: Button
    private lateinit var viewFirmwareButton: Button
    private lateinit var simulateButton: Button

    // Controllers and Managers
    private lateinit var permissionsManager: BLEPermissionsManager
    private lateinit var bleCentralController: BLEPeripheralController
    private lateinit var udbEmulator: UDBEmulator
    private lateinit var dataQueueManager: DataQueueManager

    // Real Sensor Support
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null
    private var temperatureSensor: Sensor? = null
    private var pressureSensor: Sensor? = null
    private var lightSensor: Sensor? = null

    // State tracking
    private var currentRStateReq: Int = 0
    private var currentRStateRpt: Int = 0
    private var isConnected: Boolean = false

    // Real sensor values
    private var deviceTemperature: Float = 20.0f
    private var devicePressure: Float = 1013.25f // Sea level pressure
    private var deviceMovement: Float = 0.0f
    private var ambientLight: Float = 0.0f
    private var simulatedBattery: Float = 13.5f
    private var simulatedDepth: Float = 0.0f
    private var simulatedRange: Int = 2500 // 25m

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_udb_emulator)
            initializeComponents()
            setupRealSensors()
            setupUI()
            setupBluetooth()
            setupDataQueue()
            startPeriodicUpdates()
        } catch (e: Exception) {
            Log.e("UdbEmulatorActivity", "Error in onCreate: ${e.message}")
            setupFallbackUI()
        }
    }

    private fun initializeComponents() {
        try {
            historyTextView = findViewById(R.id.historyTextView)
            statusText = findViewById(R.id.statusText)
            tableContainer = findViewById(R.id.tableContainer)
            toggleButton = findViewById(R.id.toggleButton)
            viewFirmwareButton = findViewById(R.id.viewFirmwareButton)

            // Try to find enhanced UI components
            connectionStatusText = try {
                findViewById(R.id.connectionStatusText)
            } catch (e: Exception) {
                statusText // Fallback to basic status text
            }

            currentOperationText = try {
                findViewById(R.id.currentOperationText)
            } catch (e: Exception) {
                TextView(this).apply { text = "Operation: Idle" }
            }

            rangeValueText = try {
                findViewById(R.id.rangeValueText)
            } catch (e: Exception) {
                TextView(this).apply { text = "Range: 0.00m" }
            }

            batteryLevelText = try {
                findViewById(R.id.batteryLevelText)
            } catch (e: Exception) {
                TextView(this).apply { text = "Battery: 0.0V" }
            }

            temperatureText = try {
                findViewById(R.id.temperatureText)
            } catch (e: Exception) {
                TextView(this).apply { text = "Temp: 0.0°C" }
            }

            depthText = try {
                findViewById(R.id.depthText)
            } catch (e: Exception) {
                TextView(this).apply { text = "Depth: 0.0m" }
            }

            noiseBar = try {
                findViewById(R.id.noiseProgressBar)
            } catch (e: Exception) {
                ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = 30
                }
            }

            noiseValueText = try {
                findViewById(R.id.noiseValueText)
            } catch (e: Exception) {
                TextView(this).apply { text = "30 dB" }
            }

            simulateButton = try {
                findViewById(R.id.simulateButton)
            } catch (e: Exception) {
                Button(this).apply { text = "Simulate" }
            }

            historyTextView.text = ""
            updateConnectionStatus("Waiting For Connection...")
            updateCurrentOperation("Idle")

        } catch (e: Exception) {
            Log.e("UdbEmulatorActivity", "Error initializing components: ${e.message}")
        }
    }

    private fun setupFallbackUI() {
        try {
            setContentView(R.layout.activity_udb_emulator)
            historyTextView = findViewById(R.id.historyTextView)
            statusText = findViewById(R.id.statusText)
            tableContainer = findViewById(R.id.tableContainer)
            toggleButton = findViewById(R.id.toggleButton)
            viewFirmwareButton = findViewById(R.id.viewFirmwareButton)

            connectionStatusText = statusText
            currentOperationText = TextView(this).apply { text = "Operation: Idle" }
            rangeValueText = TextView(this).apply { text = "Range: 0.00m" }
            batteryLevelText = TextView(this).apply { text = "Battery: 0.0V" }
            temperatureText = TextView(this).apply { text = "Temp: 0.0°C" }
            depthText = TextView(this).apply { text = "Depth: 0.0m" }
            noiseBar = ProgressBar(this)
            noiseValueText = TextView(this).apply { text = "30 dB" }
            simulateButton = Button(this).apply { text = "Simulate" }

            setupBasicUI()
            setupBluetooth()
            setupDataQueue()

        } catch (e: Exception) {
            Log.e("UdbEmulatorActivity", "Failed to setup fallback UI: ${e.message}")
            finish()
        }
    }

    private fun setupRealSensors() {
        try {
            sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            temperatureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
            pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
            lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

            Log.d("UDB_Sensors", "Available sensors:")
            Log.d("UDB_Sensors", "Accelerometer: ${accelerometer != null}")
            Log.d("UDB_Sensors", "Temperature: ${temperatureSensor != null}")
            Log.d("UDB_Sensors", "Pressure: ${pressureSensor != null}")
            Log.d("UDB_Sensors", "Light: ${lightSensor != null}")

        } catch (e: Exception) {
            Log.e("UdbEmulatorActivity", "Error setting up sensors: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            if (!dataQueueManager.isActive()) {
                dataQueueManager.start()
            }
            dataQueueManager.resume()

            // Register sensor listeners
            accelerometer?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
            temperatureSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
            pressureSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
            lightSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }

        } catch (e: Exception) {
            Log.e("UdbEmulatorActivity", "Error in onResume: ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            dataQueueManager.pause()
            sensorManager.unregisterListener(this)
        } catch (e: Exception) {
            Log.e("UdbEmulatorActivity", "Error in onPause: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            dataQueueManager.stop()
            bleCentralController.stopGattServer()
            sensorManager.unregisterListener(this)
        } catch (e: Exception) {
            Log.e("UdbEmulatorActivity", "Error in onDestroy: ${e.message}")
        }
    }

    // Real Sensor Implementation
    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    val x = it.values[0]
                    val y = it.values[1]
                    val z = it.values[2]
                    deviceMovement = kotlin.math.sqrt((x*x + y*y + z*z).toDouble()).toFloat()

                    // Simulate depth based on movement and pressure
                    val movementFactor = (deviceMovement - 9.8f) * 0.5f
                    simulatedDepth = (simulatedDepth + movementFactor).coerceIn(0f, 100f)

                    // Simulate range variation based on movement
                    val rangeVariation = (deviceMovement * 20).toInt()
                    simulatedRange = (2500 + rangeVariation).coerceIn(500, 8000)
                }
                Sensor.TYPE_AMBIENT_TEMPERATURE -> {
                    deviceTemperature = it.values[0]
                    // Underwater is typically colder
                    val underwaterTemp = deviceTemperature - 5f + Random.nextFloat() * 3f
                    deviceTemperature = underwaterTemp.coerceIn(2f, 30f)
                }
                Sensor.TYPE_PRESSURE -> {
                    devicePressure = it.values[0]
                    // Convert pressure to simulated depth (rough approximation)
                    val pressureDepth = (devicePressure - 1013.25f) / 100f // 100 Pa per meter roughly
                    if (pressureDepth > 0) {
                        simulatedDepth = pressureDepth.coerceIn(0f, 100f)
                    }
                }
                Sensor.TYPE_LIGHT -> {
                    ambientLight = it.values[0]
                    // Low light = deeper underwater simulation
                    if (ambientLight < 10f) {
                        simulatedDepth = kotlin.math.max(simulatedDepth, 20f)
                    }
                }
            }

            // Update battery simulation (slowly decreases)
            simulatedBattery = 13.5f - (System.currentTimeMillis() % 3600000) / 300000f // Decreases over hour
            simulatedBattery = simulatedBattery.coerceIn(10.5f, 14.5f)

            // Update UDB registers with real sensor data
            updateUDBRegistersWithSensorData()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d("UDB_Sensors", "Sensor accuracy changed: ${sensor?.name}, accuracy: $accuracy")
    }

    private fun updateUDBRegistersWithSensorData() {
        try {
            // Update range register
            Registers.RRR_VAL.setValue(simulatedRange)

            // Update battery voltage (12-15V range, in 100ths)
            val batteryValue = (simulatedBattery * 100).toInt()
            Registers.RR1_VAL.setValue(batteryValue)

            // Update temperature (in 10ths of degrees)
            val tempValue = (deviceTemperature * 10).toInt()
            Registers.RR2_VAL.setValue(tempValue)

            // Update depth (in cm)
            val depthValue = (simulatedDepth * 100).toInt()
            Registers.RR3_VAL.setValue(depthValue)

            // Update noise level (based on movement)
            val noiseLevel = (20 + deviceMovement * 5).toInt().coerceIn(15, 80)
            Registers.AR_NOISE_DB.setValue(noiseLevel)

            Log.d("UDB_SensorUpdate", "Range: ${simulatedRange}cm, Battery: ${simulatedBattery}V, Temp: ${deviceTemperature}°C, Depth: ${simulatedDepth}m")

        } catch (e: Exception) {
            Log.e("UdbEmulatorActivity", "Error updating UDB registers: ${e.message}")
        }
    }

    private fun setupUI() {
        try {
            setupBasicUI()
        } catch (e: Exception) {
            Log.e("UdbEmulatorActivity", "Error setting up UI: ${e.message}")
        }
    }

    private fun setupBasicUI() {
        try {
            var isExpanded = false
            toggleButton.setOnClickListener {
                isExpanded = toggleTableVisibility(isExpanded)
            }

            viewFirmwareButton.setOnClickListener {
                showFirmwareDialog()
            }

            simulateButton.setOnClickListener {
                showSimulationDialog()
            }
        } catch (e: Exception) {
            Log.e("UdbEmulatorActivity", "Error in setupBasicUI: ${e.message}")
        }
    }

    private fun toggleTableVisibility(isExpanded: Boolean): Boolean {
        tableContainer.visibility = if (isExpanded) View.GONE else View.VISIBLE
        toggleButton.text = if (isExpanded) "Show Registers" else "Hide Registers"
        return !isExpanded
    }

    private fun setupBluetooth() {
        try {
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

        } catch (e: Exception) {
            Log.e("UdbEmulatorActivity", "Error setting up Bluetooth: ${e.message}")
            updateConnectionStatus("Bluetooth setup failed")
        }
    }

    private fun handleBluetoothPermissionDenied() {
        Log.e("UdbEmulatorActivity", "Bluetooth permissions denied")
        updateConnectionStatus("Bluetooth permissions denied")
        AlertDialog.Builder(this).setTitle("Bluetooth Permissions Denied")
            .setMessage("Please grant Bluetooth permissions to use this app.")
            .setPositiveButton("OK") { _, _ -> }
            .setNegativeButton("Exit") { _, _ -> finish() }
            .show()
    }

    private fun handleDeviceConnection(device: android.bluetooth.BluetoothDevice?) {
        runOnUiThread {
            try {
                if (device == null) {
                    bleCentralController.startAdvertising()
                    dataQueueManager.pause()
                    updateConnectionStatus("Waiting For Connection...")
                    isConnected = false
                } else {
                    bleCentralController.stopAdvertising()
                    dataQueueManager.resume()
                    updateConnectionStatus("Connected: ${device.address}")
                    isConnected = true
                    showDeviceConnectedDialog(device)
                }
            } catch (e: Exception) {
                Log.e("UdbEmulatorActivity", "Error handling device connection: ${e.message}")
            }
        }
    }

    private fun updateConnectionStatus(text: String) {
        runOnUiThread {
            try {
                connectionStatusText.text = text
                statusText.text = text

                // Color code connection status
                val color = if (isConnected) Color.GREEN else Color.RED
                connectionStatusText.setTextColor(color)
                statusText.setTextColor(color)

                Log.d("UDB_Connection", text)
            } catch (e: Exception) {
                Log.e("UdbEmulatorActivity", "Error updating connection status: ${e.message}")
            }
        }
    }

    private fun updateCurrentOperation(operation: String) {
        runOnUiThread {
            try {
                currentOperationText.text = "Operation: $operation"

                // Color code based on operation
                val color = when (operation.lowercase()) {
                    "idle" -> Color.GRAY
                    "init", "connection" -> Color.BLUE
                    "ranging", "single range", "continuous range" -> Color.CYAN
                    "arming", "triggering", "release" -> Color.RED
                    "broadcast" -> Color.MAGENTA
                    "public interrogate", "noise test" -> Color.YELLOW
                    else -> Color.BLACK
                }
                currentOperationText.setTextColor(color)

            } catch (e: Exception) {
                Log.e("UdbEmulatorActivity", "Error updating operation: ${e.message}")
            }
        }
    }

    private fun showDeviceConnectedDialog(device: android.bluetooth.BluetoothDevice) {
        runOnUiThread {
            try {
                AlertDialog.Builder(this).setTitle("RC-RI Connected!")
                    .setMessage("Controller connected successfully\nDevice: ${device.address}\n\nReady to receive commands")
                    .setPositiveButton("OK") { _, _ -> }
                    .show()
            } catch (e: Exception) {
                Log.e("UdbEmulatorActivity", "Error showing connection dialog: ${e.message}")
            }
        }
    }

    private fun setupDataQueue() {
        try {
            dataQueueManager = DataQueueManager.getInstance()
            dataQueueManager.start()

            udbEmulator = UDBEmulator(this, bleCentralController)

            dataQueueManager.addListener { data ->
                try {
                    udbEmulator.onReceiveData(data)
                    updateHistoryTextView()
                    updateRegisterTable()
                    updateOperationBasedOnRegisters()
                } catch (e: Exception) {
                    Log.e("UdbEmulatorActivity", "Error processing data: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("UdbEmulatorActivity", "Error setting up data queue: ${e.message}")
        }
    }

    private fun updateOperationBasedOnRegisters() {
        try {
            currentRStateReq = Registers.RSTATE_REQ.getValue() as? Int ?: 0
            currentRStateRpt = Registers.RSTATE_RPT.getValue() as? Int ?: 0

            val operation = when (currentRStateReq) {
                0x00 -> "Idle Request"
                0x10 -> when (currentRStateRpt) {
                    0x11 -> "Initializing..."
                    0x12 -> "Initialization Complete"
                    0x13 -> "Initialization Failed"
                    else -> "Initialize Request"
                }
                0x20 -> when (currentRStateRpt) {
                    0x21 -> "Connection ID1"
                    0x22 -> "Connection ID2"
                    0x23 -> "Connected to RC-RI"
                    else -> "Connection Request"
                }
                0x30 -> when (currentRStateRpt) {
                    0x31 -> "Processing Single Range..."
                    0x32 -> "Single Range Complete"
                    0x33 -> "Single Range Failed"
                    else -> "Single Range Request"
                }
                0x40 -> when (currentRStateRpt) {
                    0x41 -> "Starting Continuous Range..."
                    0x42 -> "Continuous Ranging Active"
                    0x43 -> "Continuous Range Failed"
                    else -> "Continuous Range Request"
                }
                0x50 -> when (currentRStateRpt) {
                    0x51 -> "⚠️ ARMING DEVICE..."
                    0x52 -> "⚠️ DEVICE ARMED"
                    0x54 -> "🚨 TRIGGERING RELEASE..."
                    0x55 -> "🚨 DEVICE RELEASED!"
                    0x56 -> "Trigger Failed"
                    else -> "Trigger Request"
                }
                0x60 -> when (currentRStateRpt) {
                    0x61 -> "Processing Broadcast..."
                    0x62 -> "Broadcast Complete"
                    else -> "Broadcast Request"
                }
                0x70 -> when (currentRStateRpt) {
                    0x71 -> "Searching Quick ID..."
                    0x72 -> "Quick ID Detected"
                    0x73 -> "No Quick ID Found"
                    else -> "Public Quick ID Request"
                }
                0x80 -> when (currentRStateRpt) {
                    0x81 -> "Searching Full ID..."
                    0x82 -> "Full ID Detected"
                    0x83 -> "No Full ID Found"
                    else -> "Public Full ID Request"
                }
                0x90 -> when (currentRStateRpt) {
                    0x91 -> "Measuring Noise Level..."
                    0x92 -> "Noise Test Complete"
                    else -> "Noise Test Request"
                }
                0x64 -> "Rebooting..."
                else -> "Idle"
            }

            updateCurrentOperation(operation)

        } catch (e: Exception) {
            Log.e("UdbEmulatorActivity", "Error updating operation based on registers: ${e.message}")
        }
    }

    private fun startPeriodicUpdates() {
        try {
            // Update sensor displays every 2 seconds
            val updateRunnable = object : Runnable {
                override fun run() {
                    updateSensorDisplays()
                    findViewById<View>(android.R.id.content).postDelayed(this, 2000)
                }
            }
            findViewById<View>(android.R.id.content).postDelayed(updateRunnable, 2000)
        } catch (e: Exception) {
            Log.e("UdbEmulatorActivity", "Error starting periodic updates: ${e.message}")
        }
    }

    private fun updateSensorDisplays() {
        runOnUiThread {
            try {
                // Update range display
                val rangeValue = Registers.RRR_VAL.getValue() as? Int ?: simulatedRange
                val rangeInMeters = rangeValue / 100.0
                rangeValueText.text = "Range: ${String.format("%.2f", rangeInMeters)}m"

                // Update battery level
                val batteryValue = Registers.RR1_VAL.getValue() as? Int ?: (simulatedBattery * 100).toInt()
                val batteryVoltage = batteryValue / 100.0
                batteryLevelText.text = "Battery: ${String.format("%.1f", batteryVoltage)}V"

                // Update temperature
                val tempValue = Registers.RR2_VAL.getValue() as? Int ?: (deviceTemperature * 10).toInt()
                val temperature = tempValue / 10.0
                temperatureText.text = "Temp: ${String.format("%.1f", temperature)}°C"

                // Update depth
                val depthValue = Registers.RR3_VAL.getValue() as? Int ?: (simulatedDepth * 100).toInt()
                val depth = depthValue / 100.0
                depthText.text = "Depth: ${String.format("%.1f", depth)}m"

                // Update noise level
                val noiseValue = Registers.AR_NOISE_DB.getValue() as? Int ?: 30
                noiseBar.progress = noiseValue
                noiseValueText.text = "${noiseValue} dB"

                // Color code noise level
                val noiseColor = when {
                    noiseValue < 30 -> Color.GREEN
                    noiseValue < 60 -> Color.YELLOW
                    else -> Color.RED
                }
                noiseValueText.setTextColor(noiseColor)

            } catch (e: Exception) {
                Log.e("UdbEmulatorActivity", "Error updating sensor displays: ${e.message}")
            }
        }
    }

    private fun updateRegisterTable() {
        runOnUiThread {
            try {
                val tableLayout = findViewById<TableLayout>(R.id.tableData)
                tableLayout.removeAllViews()

                registerList.forEachIndexed { index, register ->
                    val tableRow = createRegisterTableRow(index, register)
                    tableLayout.addView(tableRow)
                }
            } catch (e: Exception) {
                Log.e("UdbEmulatorActivity", "Error updating register table: ${e.message}")
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
            Toast.makeText(this, "${register.name} is read-only from RC-RI", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateHistoryTextView() {
        runOnUiThread {
            try {
                historyTextView.text = udbEmulator.getCommandHistory()
            } catch (e: Exception) {
                Log.e("UdbEmulatorActivity", "Error updating history: ${e.message}")
            }
        }
    }

    private fun showEditDialog(register: Register, valueTextView: TextView) {
        try {
            val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_register, null)
            setupDialogViews(dialogView, register)

            AlertDialog.Builder(this).setView(dialogView).setPositiveButton("OK") { _, _ ->
                handleRegisterUpdate(register, valueTextView, dialogView)
            }.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }.create().show()
        } catch (e: Exception) {
            Log.e("UdbEmulatorActivity", "Error showing edit dialog: ${e.message}")
        }
    }

    private fun setupDialogViews(dialogView: View, register: Register) {
        try {
            dialogView.findViewById<TextView>(R.id.register_name).text = "Name: ${register.name}"
            dialogView.findViewById<TextView>(R.id.register_value_type).text =
                "Value Type: ${register.dataType}"
            dialogView.findViewById<TextView>(R.id.register_description).text =
                "Description: ${register.description}"
            dialogView.findViewById<TextView>(R.id.register_direction).text =
                "Direction: ${register.direction}"
            dialogView.findViewById<EditText>(R.id.register_value_input)
                .setText(register.getValue().toString())
        } catch (e: Exception) {
            Log.e("UdbEmulatorActivity", "Error setting up dialog views: ${e.message}")
        }
    }

    private fun handleRegisterUpdate(
        register: Register, valueTextView: TextView, dialogView: View
    ) {
        try {
            val newValue = dialogView.findViewById<EditText>(R.id.register_value_input).text.toString()
            register.setValueString(newValue)
            valueTextView.text = register.getValueString() ?: "null"

            val rMap = 1L shl register.regMapBit
            Registers.REG_MAP.setValue(rMap)
            sendCommand(DSSCommand.createRMCommand("UDB", "RC-RI", rMap))

            updateRegisterTable()
        } catch (e: Exception) {
            Log.e("UdbEmulatorActivity", "Error updating register: ${e.message}")
            Toast.makeText(this, "Invalid input: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendCommand(command: DSSCommand) {
        try {
            udbEmulator.sendCommand(command)
            updateHistoryTextView()
        } catch (e: Exception) {
            Log.e("UdbEmulatorActivity", "Error sending command: ${e.message}")
        }
    }

    private fun showFirmwareDialog() {
        try {
            val firmwareLines = udbEmulator.getFirmwareLines()
            val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_firmware, null)
            val firmwareTextView = dialogView.findViewById<TextView>(R.id.firmwareTextView)

            val firmwareText = if (firmwareLines.isEmpty()) {
                "No firmware uploaded yet.\n\nFirmware will appear here after RC-RI uploads new firmware via LD commands."
            } else {
                "Firmware Lines: ${firmwareLines.size}\n\nReal sensor data active:\n" +
                        "- Temperature: ${String.format("%.1f", deviceTemperature)}°C\n" +
                        "- Depth: ${String.format("%.1f", simulatedDepth)}m\n" +
                        "- Battery: ${String.format("%.1f", simulatedBattery)}V\n" +
                        "- Range: ${simulatedRange/100.0}m\n\n" +
                        "Firmware Content:\n" + firmwareLines.joinToString("\n")
            }

            firmwareTextView.text = firmwareText

            val dialog = AlertDialog.Builder(this)
                .setTitle("UDB Status & Firmware")
                .setView(dialogView)
                .setPositiveButton("Clear Firmware") { _, _ ->
                    udbEmulator.clearFirmware()
                    Toast.makeText(this, "Firmware cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Close") { dialog, _ -> dialog.dismiss() }
                .create()

            dialog.setOnShowListener {
                val width = resources.displayMetrics.widthPixels * 0.9
                val height = resources.displayMetrics.heightPixels * 0.8
                dialog.window?.setLayout(width.toInt(), height.toInt())
            }

            dialog.show()
        } catch (e: Exception) {
            Log.e("UdbEmulatorActivity", "Error showing firmware dialog: ${e.message}")
        }
    }

    private fun showSimulationDialog() {
        try {
            val input = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 32, 32, 32)
            }

            val rangeSeek = SeekBar(this).apply {
                max = 8000
                progress = simulatedRange
            }
            val batterySeek = SeekBar(this).apply {
                max = 1600
                progress = (simulatedBattery * 100).toInt()
            }
            val tempSeek = SeekBar(this).apply {
                max = 400
                progress = (deviceTemperature * 10).toInt()
            }
            val depthSeek = SeekBar(this).apply {
                max = 5000
                progress = (simulatedDepth * 100).toInt()
            }

            val rangeText = TextView(this).apply { text = "Range: ${simulatedRange/100.0}m" }
            val batteryText = TextView(this).apply { text = "Battery: ${String.format("%.1f", simulatedBattery)}V" }
            val tempText = TextView(this).apply { text = "Temp: ${String.format("%.1f", deviceTemperature)}°C" }
            val depthText = TextView(this).apply { text = "Depth: ${String.format("%.1f", simulatedDepth)}m" }

            rangeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    rangeText.text = "Range: ${progress/100.0}m"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })

            batterySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    batteryText.text = "Battery: ${progress/100.0}V"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })

            input.addView(TextView(this).apply { text = "Adjust Simulated Sensor Values:" })
            input.addView(rangeText)
            input.addView(rangeSeek)
            input.addView(batteryText)
            input.addView(batterySeek)
            input.addView(tempText)
            input.addView(tempSeek)
            input.addView(depthText)
            input.addView(depthSeek)

            AlertDialog.Builder(this)
                .setTitle("🎛️ UDB Sensor Simulation")
                .setView(input)
                .setPositiveButton("Apply") { _, _ ->
                    simulatedRange = rangeSeek.progress
                    simulatedBattery = batterySeek.progress / 100.0f
                    deviceTemperature = tempSeek.progress / 10.0f
                    simulatedDepth = depthSeek.progress / 100.0f

                    updateUDBRegistersWithSensorData()
                    updateSensorDisplays()
                    updateRegisterTable()

                    Toast.makeText(this, "Sensor values updated", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                .show()

        } catch (e: Exception) {
            Log.e("UdbEmulatorActivity", "Error showing simulation dialog: ${e.message}")
            // Fallback simple dialog
            Toast.makeText(this, "Simulation feature temporarily unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    // Extension function to convert dp to pixels
    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()
}