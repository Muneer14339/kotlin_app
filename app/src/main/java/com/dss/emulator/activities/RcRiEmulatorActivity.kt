package com.dss.emulator.activities

import android.annotation.SuppressLint
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.dss.emulator.bluetooth.BLEPermissionsManager
import com.dss.emulator.bluetooth.DataQueueManager
import com.dss.emulator.bluetooth.central.BLECentralController
import com.dss.emulator.core.RCRIEmulator
import com.dss.emulator.core.ReleaseState
import com.dss.emulator.dsscommand.DSSCommand
import com.dss.emulator.fake.FakeFirmwareGenerator
import com.dss.emulator.register.Direction
import com.dss.emulator.register.Register
import com.dss.emulator.register.registerList
import com.dss.emulator.udb.R
import com.dss.emulator.ui.UDBColorCoding
import kotlinx.coroutines.launch
import kotlin.random.Random

class RcRiEmulatorActivity : ComponentActivity(), SensorEventListener {
    // UI Components
    private lateinit var historyTextView: TextView
    private lateinit var releaseStateTextView: TextView
    private lateinit var statusBarTextView: TextView
    private lateinit var retryCountTextView: TextView
    private lateinit var rangeDistanceTextView: TextView
    private lateinit var countdownTextView: TextView
    private lateinit var tableContainer: LinearLayout
    private lateinit var toggleButton: Button

    // New UI Components
    private lateinit var popupSrangeButton: Button
    private lateinit var popupCrangeButton: Button
    private lateinit var popupTriggerButton: Button
    private lateinit var popupBroadcastButton: Button
    private lateinit var popupPIQIDButton: Button
    private lateinit var popupPIIDButton: Button
    private lateinit var popupNTButton: Button

    // Controllers and Managers
    private lateinit var permissionsManager: BLEPermissionsManager
    private lateinit var devicesDialog: FindDevicesDialog
    private lateinit var bleCentralController: BLECentralController
    private lateinit var rcriEmulator: RCRIEmulator
    private lateinit var dataQueueManager: DataQueueManager

    // Real Sensor Support
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null
    private var temperatureSensor: Sensor? = null

    // State management
    private var countDownTimer: CountDownTimer? = null
    private var currentState: ReleaseState = ReleaseState.IDLE_REQ
    private var currentRetryCount: Int = 0
    private var currentRange: Int = 0
    private var isDeviceConnected: Boolean = false

    // Real sensor values
    private var deviceTemperature: Float = 20.0f
    private var deviceMovement: Float = 0.0f
    private var simulatedDepth: Float = 0.0f

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_rc_ri_emulator)
            initializeComponents()
            setupRealSensors()
            setupUI()
            setupBluetooth()
            setupDataQueue()
            startPeriodicUIUpdates()
            showDeviceListDialog()
        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error in onCreate: ${e.message}")
            // Fallback UI setup
            setupFallbackUI()
        }
    }

    private fun initializeComponents() {
        try {
            historyTextView = findViewById(R.id.historyTextView)
            releaseStateTextView = findViewById(R.id.releaseStateTextView)
            tableContainer = findViewById(R.id.tableContainer)
            toggleButton = findViewById(R.id.toggleButton)

            // Try to find enhanced UI components, fallback if not found
            statusBarTextView = try {
                findViewById(R.id.statusBarTextView)
            } catch (e: Exception) {
                TextView(this).also {
                    it.text = "Status: Ready"
                    it.setBackgroundColor(Color.GRAY)
                }
            }

            retryCountTextView = try {
                findViewById(R.id.retryCountTextView)
            } catch (e: Exception) {
                TextView(this).also { it.text = "Retries: 0" }
            }

            rangeDistanceTextView = try {
                findViewById(R.id.rangeDistanceTextView)
            } catch (e: Exception) {
                TextView(this).also { it.text = "Range: 0.00m" }
            }

            countdownTextView = try {
                findViewById(R.id.countdownTextView)
            } catch (e: Exception) {
                TextView(this).also { it.visibility = View.GONE }
            }

            historyTextView.text = ""
            updateStatusBar("Waiting For Connection...", Color.GRAY)
            updateRetryCount(0)
            updateRangeDistance(0)

        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error initializing components: ${e.message}")
        }
    }

    private fun setupFallbackUI() {
        // If enhanced layout fails, use basic layout
        try {
            setContentView(R.layout.activity_rc_ri_emulator)
            historyTextView = findViewById(R.id.historyTextView)
            releaseStateTextView = findViewById(R.id.releaseStateTextView)
            tableContainer = findViewById(R.id.tableContainer)
            toggleButton = findViewById(R.id.toggleButton)

            // Create simple status display
            statusBarTextView = TextView(this).also {
                it.text = "Status: Ready"
                it.setBackgroundColor(Color.GRAY)
                it.setPadding(16, 8, 16, 8)
            }

            setupBasicUI()

        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Failed to setup fallback UI: ${e.message}")
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

            Log.d("Sensors", "Available sensors:")
            Log.d("Sensors", "Accelerometer: ${accelerometer != null}")
            Log.d("Sensors", "Gyroscope: ${gyroscope != null}")
            Log.d("Sensors", "Magnetometer: ${magnetometer != null}")
            Log.d("Sensors", "Temperature: ${temperatureSensor != null}")

        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error setting up sensors: ${e.message}")
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
            gyroscope?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }

        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error in onResume: ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            dataQueueManager.pause()
            sensorManager.unregisterListener(this)
        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error in onPause: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            dataQueueManager.stop()
            countDownTimer?.cancel()
            sensorManager.unregisterListener(this)
        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error in onDestroy: ${e.message}")
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

                    // Simulate depth based on movement (more movement = more depth change)
                    simulatedDepth += (deviceMovement - 9.8f) * 0.1f
                    simulatedDepth = simulatedDepth.coerceIn(0f, 50f) // 0-50m depth
                }
                Sensor.TYPE_AMBIENT_TEMPERATURE -> {
                    deviceTemperature = it.values[0]
                }
                Sensor.TYPE_GYROSCOPE -> {
                    // Use gyroscope for simulated underwater current effects
                    val rotation = kotlin.math.sqrt(
                        (it.values[0]*it.values[0] +
                                it.values[1]*it.values[1] +
                                it.values[2]*it.values[2]).toDouble()
                    ).toFloat()
                }
            }

            // Update displays with real sensor data
            updateSensorDisplays()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d("Sensors", "Sensor accuracy changed: ${sensor?.name}, accuracy: $accuracy")
    }

    private fun updateSensorDisplays() {
        runOnUiThread {
            try {
                // Update range with simulated sonar + device movement
//                val baseRange = Random.nextInt(1000, 5000) // Base sonar range
//                val movementOffset = (deviceMovement * 10).toInt() // Movement affects range
//                currentRange = (baseRange + movementOffset).coerceIn(500, 8000)
//                //updateRangeDistance(currentRange)

                // Show real temperature or simulated underwater temp
                val displayTemp = if (temperatureSensor != null) {
                    deviceTemperature
                } else {
                    15.0f + Random.nextFloat() * 10f // 15-25°C simulated
                }

                // Show simulated depth based on accelerometer
                val displayDepth = simulatedDepth

                // Show simulated battery (decreases over time)
                val batteryLevel = 13.5f - (System.currentTimeMillis() % 100000) / 10000f

                Log.d("SensorUpdate", "Range: ${currentRange}cm, Temp: ${displayTemp}°C, Depth: ${displayDepth}m, Battery: ${batteryLevel}V")

            } catch (e: Exception) {
                Log.e("RcRiEmulatorActivity", "Error updating sensor displays: ${e.message}")
            }
        }
    }

    // Add this to RcRiEmulatorActivity.kt in setupUI() method

    private fun setupManualCommandInput() {
        try {
            val inputEditText = findViewById<EditText>(R.id.inputEditText)
            val sendCommandButton = findViewById<com.google.android.material.button.MaterialButton>(R.id.sendCommandButton)

            sendCommandButton.setOnClickListener {
                val commandText = inputEditText.text.toString().trim()
                handleManualCommand(commandText, inputEditText)
            }

            // Enter key support
            inputEditText.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN) {
                    sendCommandButton.performClick()
                    true
                } else {
                    false
                }
            }

            // Helpful hints
            inputEditText.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && inputEditText.text.isEmpty()) {
                    inputEditText.hint = "#I001,UDB,RC-RI,GT,RSTATE_RPT,*0000"
                }
            }

        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error setting up manual command input: ${e.message}")
        }
    }

    private fun handleManualCommand(commandText: String, inputEditText: EditText) {
        try {
            when {
                commandText.isEmpty() -> {
                    showError("Please enter a command")
                    return
                }

                !commandText.startsWith("#") -> {
                    showError("Command must start with # \nExample: #I001,UDB,RC-RI,GT,RSTATE_RPT,*0000")
                    return
                }

                else -> {
                    // Validate basic format
                    val parts = commandText.split(",")
                    if (parts.size < 4) {
                        showError("Invalid format.\nCorrect format: #I001,UDB,RC-RI,COMMAND[,DATA],*CHECKSUM")
                        return
                    }

                    // Create proper DSS command
                    val fullCommand = if (commandText.endsWith("\r\n")) {
                        commandText
                    } else {
                        "$commandText\r\n"
                    }

                    Log.d("ManualCommand", "Sending: $fullCommand")

                    try {
                        // Parse as DSS Command to validate
                        val dssCommand = DSSCommand(fullCommand)

                        // Send command using the emulator
                        rcriEmulator.sendCommand(dssCommand)

                        // Clear input
                        inputEditText.text.clear()

                        // Update all UI elements
                        updateHistoryTextView()
                        updateRegisterTable()

                        // Force state update after a delay to allow UDB response
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            updateReleaseStateTextView()
                            updateAllUIFromRegisters()
                        }, 500) // Wait 500ms for UDB response

                        // Show success feedback
                        updateStatusBar("Manual command sent: ${commandText.take(20)}...", Color.GREEN)

                    } catch (e: IllegalArgumentException) {
                        showError("Invalid command format: ${e.message}")
                    } catch (e: Exception) {
                        showError("Command failed: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ManualCommand", "Error handling manual command: ${e.message}")
            showError("Error: ${e.message}")
        }
    }

// ==================================================================
// RcRiEmulatorActivity.kt میں یہ نیا method add کریں:
// ==================================================================

    private fun updateAllUIFromRegisters() {
        try {
            // Force update from current register values
            val currentStateRpt = com.dss.emulator.register.Registers.RSTATE_RPT.getValue() as? Int ?: 0
            val currentRange = com.dss.emulator.register.Registers.RRR_VAL.getValue() as? Int ?: 0
            val retryCount = com.dss.emulator.register.Registers.RR_MISS.getValue() as? Int ?: 0

            // Update range display
            updateRangeDistance(currentRange)

            // Update retry count
            updateRetryCount(retryCount)

            // Update state based on RSTATE_RPT register value
            val releaseState = when (currentStateRpt) {
                0x1 -> com.dss.emulator.core.ReleaseState.IDLE_ACK
                0x11 -> com.dss.emulator.core.ReleaseState.INIT_PENDING
                0x12 -> com.dss.emulator.core.ReleaseState.INIT_OK
                0x13 -> com.dss.emulator.core.ReleaseState.INIT_FAIL
                0x21 -> com.dss.emulator.core.ReleaseState.CON_ID1
                0x22 -> com.dss.emulator.core.ReleaseState.CON_ID2
                0x23 -> com.dss.emulator.core.ReleaseState.CON_OK
                0x31 -> com.dss.emulator.core.ReleaseState.RNG_SINGLE_PENDING
                0x32 -> com.dss.emulator.core.ReleaseState.RNG_SINGLE_OK
                0x33 -> com.dss.emulator.core.ReleaseState.RNG_SINGLE_FAIL
                0x41 -> com.dss.emulator.core.ReleaseState.RNG_CONT_PENDING
                0x42 -> com.dss.emulator.core.ReleaseState.RNG_CONT_OK
                0x43 -> com.dss.emulator.core.ReleaseState.RNG_CONT_FAIL
                0x51 -> com.dss.emulator.core.ReleaseState.AT_ARM_PENDING
                0x52 -> com.dss.emulator.core.ReleaseState.AT_ARM_OK
                0x54 -> com.dss.emulator.core.ReleaseState.AT_TRG_PENDING
                0x55 -> com.dss.emulator.core.ReleaseState.AT_TRG_OK
                0x56 -> com.dss.emulator.core.ReleaseState.AT_TRG_FAIL
                0x61 -> com.dss.emulator.core.ReleaseState.BCR_PENDING
                0x62 -> com.dss.emulator.core.ReleaseState.BCR_OK
                0x71 -> com.dss.emulator.core.ReleaseState.PI_QID_PENDING
                0x72 -> com.dss.emulator.core.ReleaseState.PI_QID_DETECT
                0x73 -> com.dss.emulator.core.ReleaseState.PI_QID_NODETECT
                0x81 -> com.dss.emulator.core.ReleaseState.PI_ID_PENDING
                0x82 -> com.dss.emulator.core.ReleaseState.PI_ID_DETECT
                0x83 -> com.dss.emulator.core.ReleaseState.PI_ID_NODETECT
                0x91 -> com.dss.emulator.core.ReleaseState.NT_PENDING
                0x92 -> com.dss.emulator.core.ReleaseState.NT_OK
                0x65 -> com.dss.emulator.core.ReleaseState.RB_ACK
                else -> com.dss.emulator.core.ReleaseState.IDLE_REQ
            }

            // Update current state tracking
            currentState = releaseState

            // Force UI update
            runOnUiThread {
                releaseStateTextView.text = releaseState.toString()

                // Color code based on state
                when (releaseState) {
                    com.dss.emulator.core.ReleaseState.IDLE_ACK,
                    com.dss.emulator.core.ReleaseState.INIT_OK,
                    com.dss.emulator.core.ReleaseState.CON_OK ->
                        releaseStateTextView.setBackgroundColor(Color.GREEN)

                    com.dss.emulator.core.ReleaseState.INIT_FAIL,
                    com.dss.emulator.core.ReleaseState.RNG_SINGLE_FAIL,
                    com.dss.emulator.core.ReleaseState.AT_ARM_FAIL,
                    com.dss.emulator.core.ReleaseState.AT_TRG_FAIL ->
                        releaseStateTextView.setBackgroundColor(Color.RED)

                    com.dss.emulator.core.ReleaseState.AT_ARM_PENDING,
                    com.dss.emulator.core.ReleaseState.AT_TRG_PENDING ->
                        releaseStateTextView.setBackgroundColor(Color.MAGENTA)

                    else -> releaseStateTextView.setBackgroundColor(Color.BLUE)
                }

                // Update status bar based on state
                updateStatusBarBasedOnState(releaseState)
            }

            Log.d("UIUpdate", "Updated UI from registers - State: $releaseState, Range: ${currentRange}cm, Retries: $retryCount")

        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error updating UI from registers: ${e.message}")
        }
    }


    private fun setupUI() {
        try {
            var isExpanded = false
            toggleButton.setOnClickListener {
                isExpanded = toggleTableVisibility(isExpanded)
            }

            setupCommandButtons()
            setupNewPopupButtonsSafely()
            setupManualCommandInput()
        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error setting up UI: ${e.message}")
            setupBasicUI()
        }
    }

    private fun setupBasicUI() {
        // Basic UI setup for fallback
        findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.popupIdleButton)?.setOnClickListener {
            safeExecute("Idle") { rcriEmulator.popupIdle() }
        }

        findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.popupInitButton)?.setOnClickListener {
            safeExecute("Init") { rcriEmulator.popupInit() }
        }

        findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.popupConnectButton)?.setOnClickListener {
            safeExecute("Connect") { rcriEmulator.popupConnect() }
        }
    }

    private fun toggleTableVisibility(isExpanded: Boolean): Boolean {
        tableContainer.visibility = if (isExpanded) View.GONE else View.VISIBLE
        toggleButton.text = if (isExpanded) "Show Registers" else "Hide Registers"
        return !isExpanded
    }

    // Also update setupCommandButtons method to add dialogs for basic operations
    private fun setupCommandButtons() {
        try {
            findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.commandRBButton)?.setOnClickListener {
                sendCommand(DSSCommand.createRBCommand("RC-RI", "UDB"))
            }

            findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.commandFTButton)?.setOnClickListener {
                sendCommand(DSSCommand.createFTCommand("RC-RI", "UDB"))
            }

            findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.commandGIButton)?.setOnClickListener {
                sendCommand(DSSCommand.createGICommand("RC-RI", "UDB"))
            }

            findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.popupIdleButton)?.setOnClickListener {
                showIdleDialog()
            }

            findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.popupInitButton)?.setOnClickListener {
                showInitDialog()
            }

            findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.popupConnectButton)?.setOnClickListener {
                showConnectDialog()
            }

            findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.firmwareUpdateButton)?.setOnClickListener {
                updateFirmware()
            }
        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error setting up command buttons: ${e.message}")
        }
    }

    private fun setupNewPopupButtonsSafely() {
        try {
            // Try to find new buttons, create simple alternatives if not found
            popupSrangeButton = try {
                findViewById<Button>(R.id.popupSrangeButton)
            } catch (e: Exception) {
                Button(this).apply { text = "Single Range" }
            }

            popupCrangeButton = try {
                findViewById<Button>(R.id.popupCrangeButton)
            } catch (e: Exception) {
                Button(this).apply { text = "Cont Range" }
            }

            popupTriggerButton = try {
                findViewById<Button>(R.id.popupTriggerButton)
            } catch (e: Exception) {
                Button(this).apply { text = "TRIGGER" }
            }

            // Set up click listeners with dialogs
            popupSrangeButton.setOnClickListener {
                showSingleRangeDialog()
            }

            popupCrangeButton.setOnClickListener {
                showContinuousRangeDialog()
            }

            popupTriggerButton.setOnClickListener {
                showReleaseConfirmationDialog {
                    rcriEmulator.popupTrigger()
                }
            }

            // Find other buttons if available
            findViewById<Button>(R.id.popupBroadcastButton)?.setOnClickListener {
                showBroadcastDialog()
            }

            findViewById<Button>(R.id.popupPIQIDButton)?.setOnClickListener {
                showPublicQuickIDDialog()
            }

            findViewById<Button>(R.id.popupPIIDButton)?.setOnClickListener {
                showPublicFullIDDialog()
            }

            findViewById<Button>(R.id.popupNTButton)?.setOnClickListener {
                showNoiseTestDialog()
            }

        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error setting up popup buttons: ${e.message}")
        }
    }

    // 1. Single Range Dialog
    private fun showSingleRangeDialog() {
        try {
            AlertDialog.Builder(this)
                .setTitle("📏 Single Range Measurement")
                .setMessage("This will perform a single acoustic range measurement to the release device.\n\n" +
                        "Current State: ${currentState}\n" +
                        "Last Range: ${currentRange/100.0}m\n" +
                        "Retry Count: $currentRetryCount\n\n" +
                        "Ready to proceed?")
                .setIcon(android.R.drawable.ic_dialog_info)
                .setPositiveButton("START RANGING") { _, _ ->
                    safeExecute("Single Range") { rcriEmulator.popupSrange() }
                }
                .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                .show()
        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error showing single range dialog: ${e.message}")
        }
    }

    // 2. Continuous Range Dialog
    private fun showContinuousRangeDialog() {
        try {
            AlertDialog.Builder(this)
                .setTitle("📏 Continuous Range Measurement")
                .setMessage("This will start continuous acoustic ranging to the release device.\n\n" +
                        "• Measurements will be taken periodically\n" +
                        "• Real-time range updates will be displayed\n" +
                        "• This will continue until stopped\n\n" +
                        "Current State: ${currentState}\n" +
                        "Ready to start continuous ranging?")
                .setIcon(android.R.drawable.ic_dialog_info)
                .setPositiveButton("START CONTINUOUS") { _, _ ->
                    safeExecute("Continuous Range") { rcriEmulator.popupCrange() }
                }
                .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                .show()
        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error showing continuous range dialog: ${e.message}")
        }
    }

    // 3. Public Quick ID Dialog
    private fun showPublicQuickIDDialog() {
        try {
            AlertDialog.Builder(this)
                .setTitle("🔍 Public Quick ID Interrogate")
                .setMessage("This will send a public interrogate signal to detect nearby fishing gear with Quick ID response.\n\n" +
                        "• Searches for quick identification signals\n" +
                        "• Faster than full ID but less detailed\n" +
                        "• Range: up to ${currentRange/100.0}m\n" +
                        "• Noise level: ${com.dss.emulator.register.Registers.AR_NOISE_DB.getValue()}dB\n\n" +
                        "Continue with Quick ID search?")
                .setIcon(android.R.drawable.ic_dialog_info)
                .setPositiveButton("START SEARCH") { _, _ ->
                    safeExecute("Public Quick ID") { rcriEmulator.popupPIQID() }
                }
                .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                .show()
        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error showing public quick ID dialog: ${e.message}")
        }
    }

    // 4. Public Full ID Dialog
    private fun showPublicFullIDDialog() {
        try {
            AlertDialog.Builder(this)
                .setTitle("🔍 Public Full ID Interrogate")
                .setMessage("This will send a public interrogate signal to detect nearby fishing gear with Full ID response.\n\n" +
                        "• Searches for complete identification signals\n" +
                        "• More detailed than Quick ID but takes longer\n" +
                        "• Range: up to ${currentRange/100.0}m\n" +
                        "• Noise level: ${com.dss.emulator.register.Registers.AR_NOISE_DB.getValue()}dB\n\n" +
                        "Continue with Full ID search?")
                .setIcon(android.R.drawable.ic_dialog_info)
                .setPositiveButton("START SEARCH") { _, _ ->
                    safeExecute("Public Full ID") { rcriEmulator.popupPIID() }
                }
                .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                .show()
        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error showing public full ID dialog: ${e.message}")
        }
    }

    // 5. Noise Test Dialog
    private fun showNoiseTestDialog() {
        try {
            AlertDialog.Builder(this)
                .setTitle("🔊 Acoustic Noise Test")
                .setMessage("This will perform an acoustic noise level measurement.\n\n" +
                        "• Measures ambient underwater noise\n" +
                        "• Helps optimize detection settings\n" +
                        "• Current noise level: ${com.dss.emulator.register.Registers.AR_NOISE_DB.getValue()}dB\n" +
                        "• Detection threshold: ${com.dss.emulator.register.Registers.AR_THOLD_DB.getValue()}dB\n\n" +
                        "Start noise measurement?")
                .setIcon(android.R.drawable.ic_dialog_info)
                .setPositiveButton("START TEST") { _, _ ->
                    safeExecute("Noise Test") { rcriEmulator.popupNT() }
                }
                .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                .show()
        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error showing noise test dialog: ${e.message}")
        }
    }

    // 6. Idle Dialog
    private fun showIdleDialog() {
        try {
            AlertDialog.Builder(this)
                .setTitle("💤 Return to Idle State")
                .setMessage("This will return the system to idle state.\n\n" +
                        "• All active operations will be stopped\n" +
                        "• Device will be ready for new commands\n" +
                        "• Current state: ${currentState}\n\n" +
                        "Are you sure you want to return to idle?")
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton("GO IDLE") { _, _ ->
                    safeExecute("Idle") { rcriEmulator.popupIdle() }
                }
                .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                .show()
        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error showing idle dialog: ${e.message}")
        }
    }

    // 7. Initialize Dialog
    private fun showInitDialog() {
        try {
            AlertDialog.Builder(this)
                .setTitle("🔧 Initialize System")
                .setMessage("This will initialize the UDB system and prepare it for operations.\n\n" +
                        "Setup includes:\n" +
                        "• Device self-test\n" +
                        "• Acoustic configuration\n" +
                        "• Communication protocol setup\n" +
                        "• System parameter validation\n\n" +
                        "Current state: ${currentState}\n" +
                        "Ready to initialize?")
                .setIcon(android.R.drawable.ic_dialog_info)
                .setPositiveButton("INITIALIZE") { _, _ ->
                    safeExecute("Init") { rcriEmulator.popupInit() }
                }
                .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                .show()
        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error showing init dialog: ${e.message}")
        }
    }

    // 8. Connect Dialog
    private fun showConnectDialog() {
        try {
            AlertDialog.Builder(this)
                .setTitle("📡 Connect to Release Device")
                .setMessage("This will establish communication with the acoustic release device.\n\n" +
                        "Connection process:\n" +
                        "• ID packet exchange\n" +
                        "• Authentication verification\n" +
                        "• Communication link establishment\n\n" +
                        "Current state: ${currentState}\n" +
                        "Range: ${currentRange/100.0}m\n" +
                        "PIN ID: ${com.dss.emulator.register.Registers.PIN_ID.getValue()}\n\n" +
                        "Ready to connect?")
                .setIcon(android.R.drawable.ic_dialog_info)
                .setPositiveButton("CONNECT") { _, _ ->
                    safeExecute("Connect") { rcriEmulator.popupConnect() }
                }
                .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                .show()
        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error showing connect dialog: ${e.message}")
        }
    }

    // Safe execution wrapper to prevent crashes
    private fun safeExecute(operationName: String, operation: () -> Unit) {
        try {
            Log.d("RcRiEmulatorActivity", "Executing: $operationName")
            operation()
            updateHistoryTextView()
            updateReleaseStateTextView()
            updateStatusBar("$operationName executed", Color.GREEN)
        } catch (e: IllegalArgumentException) {
            Log.w("RcRiEmulatorActivity", "Invalid state for $operationName: ${e.message}")
            updateStatusBar("Cannot $operationName in current state", Color.YELLOW)
            showError("Cannot perform $operationName: ${e.message}")
        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error executing $operationName: ${e.message}")
            updateStatusBar("$operationName failed", Color.RED)
            showError("$operationName failed: ${e.message}")
        }
    }

    private fun setupBluetooth() {
        try {
            permissionsManager = BLEPermissionsManager(this) { granted ->
                if (!granted) {
                    handleBluetoothPermissionDenied()
                }
            }

            bleCentralController = BLECentralController(context = this, onDeviceFound = { device ->
                runOnUiThread {
                    try {
                        devicesDialog.addDevice(device)
                    } catch (e: Exception) {
                        Log.e("RcRiEmulatorActivity", "Error adding device: ${e.message}")
                    }
                }
            }, onDeviceConnected = { device ->
                handleDeviceConnected(device)
            })

            rcriEmulator = RCRIEmulator(this, bleCentralController)

            // Set up callbacks for enhanced functionality
            rcriEmulator.setStateChangedCallback { newState ->
                currentState = newState
                updateReleaseStateTextView()
                updateStatusBarBasedOnState(newState)
            }

            rcriEmulator.setRangeReceivedCallback { range ->
                currentRange = range
                updateRangeDistance(range)
                playDetectionAlert()
            }

            rcriEmulator.setDetectionAlertCallback {
                playDetectionAlert()
            }

            rcriEmulator.setReleaseAlertCallback {
                playReleaseAlert()
                startReleaseCountdown()
            }

            rcriEmulator.setRetryCountChangedCallback { retryCount ->
                currentRetryCount = retryCount
                updateRetryCount(retryCount)
            }

            devicesDialog = FindDevicesDialog(bleCentralController = bleCentralController,
                context = this,
                onDeviceSelected = { device ->
                    Log.d("DeviceSelected", "Selected device: ${device.name} ${device.address}")
                    bleCentralController.connectToDevice(device)
                })

        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error setting up Bluetooth: ${e.message}")
            updateStatusBar("Bluetooth setup failed", Color.RED)
        }
    }

    private fun handleBluetoothPermissionDenied() {
        Log.e("RcRiEmulatorActivity", "Bluetooth permissions denied")
        updateStatusBar("Bluetooth permissions denied", Color.RED)
        AlertDialog.Builder(this).setTitle("Bluetooth Permissions Denied")
            .setMessage("Please grant Bluetooth permissions to use this app.")
            .setPositiveButton("OK") { _, _ -> }
            .setNegativeButton("Exit") { _, _ -> finish() }
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun handleDeviceConnected(device: android.bluetooth.BluetoothDevice) {
        runOnUiThread {
            try {
                devicesDialog.stopScanning()
                devicesDialog.dismiss()
                dataQueueManager.resume()
                isDeviceConnected = true

                updateStatusBar("Connected to ${device.name}", Color.GREEN)

                AlertDialog.Builder(this).setTitle("Connected Successfully!")
                    .setMessage("Connected to ${device.name}\nReady for operations")
                    .setPositiveButton("OK") { _, _ -> }
                    .show()

            } catch (e: Exception) {
                Log.e("RcRiEmulatorActivity", "Error handling device connection: ${e.message}")
            }
        }
    }

    private fun setupDataQueue() {
        try {
            dataQueueManager = DataQueueManager.getInstance()
            dataQueueManager.start()

            dataQueueManager.addListener { data ->
                try {
                    Log.d("RcRiEmulatorActivity", "Received data length: ${data.size}")
                    rcriEmulator.onReceiveData(data)

                    // Update all UI elements
                    updateHistoryTextView()
                    updateRegisterTable()
                    updateReleaseStateTextView()

                    // Additional UI update after processing
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        updateAllUIFromRegisters()
                    }, 200)

                } catch (e: Exception) {
                    Log.e("RcRiEmulatorActivity", "Error processing received data: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error setting up data queue: ${e.message}")
        }
    }

    private fun showDeviceListDialog() {
        try {
            devicesDialog.show()
            permissionsManager.checkAndRequestPermissions()
            devicesDialog.startScanning()
        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error showing device list: ${e.message}")
            updateStatusBar("Failed to start scanning", Color.RED)
        }
    }

    private fun sendCommand(command: DSSCommand) {
        try {
            rcriEmulator.sendCommand(command)
            updateHistoryTextView()
        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error sending command: ${e.message}")
            showError("Command failed: ${e.message}")
        }
    }

    // Enhanced UI Update Functions with error handling
    private fun updateStatusBar(message: String, color: Int) {
        runOnUiThread {
            try {
                statusBarTextView.text = message
                statusBarTextView.setBackgroundColor(color)
                statusBarTextView.setTextColor(if (color == Color.WHITE || color == Color.YELLOW) Color.BLACK else Color.WHITE)
                Log.d("StatusUpdate", message)
            } catch (e: Exception) {
                Log.e("RcRiEmulatorActivity", "Error updating status bar: ${e.message}")
            }
        }
    }

    // Replace the updateStatusBarBasedOnState method with this enhanced version:
    private fun updateStatusBarBasedOnState(state: ReleaseState) {
        val statusMessage = UDBColorCoding.getStatusMessage(state)
        val statusColor = UDBColorCoding.getStateColor(state)
        val textColor = UDBColorCoding.getTextColor(statusColor)

        runOnUiThread {
            try {
                statusBarTextView.text = statusMessage
                statusBarTextView.setBackgroundColor(statusColor)
                statusBarTextView.setTextColor(textColor)

                // Update the release state text view as well
                releaseStateTextView.text = state.toString()
                releaseStateTextView.setBackgroundColor(statusColor)
                releaseStateTextView.setTextColor(textColor)

                Log.d("StatusUpdate", "State: $state, Color: $statusColor, Message: $statusMessage")
            } catch (e: Exception) {
                Log.e("RcRiEmulatorActivity", "Error updating status bar: ${e.message}")
            }
        }
    }

    // Replace the updateConnectionStatus method:
    private fun updateConnectionStatus(message: String, isConnected: Boolean = false) {
        runOnUiThread {
            try {
                val color = if (isConnected) {
                    UDBColorCoding.StatusColors.SUCCESS
                } else if (message.contains("Failed", ignoreCase = true) ||
                    message.contains("Error", ignoreCase = true)) {
                    UDBColorCoding.StatusColors.ERROR
                } else if (message.contains("Waiting", ignoreCase = true)) {
                    UDBColorCoding.StatusColors.WARNING
                } else {
                    UDBColorCoding.StatusColors.INFO
                }

                val textColor = UDBColorCoding.getTextColor(color)

                statusBarTextView.text = message
                statusBarTextView.setBackgroundColor(color)
                statusBarTextView.setTextColor(textColor)

                Log.d("ConnectionUpdate", "Message: $message, Color: $color")
            } catch (e: Exception) {
                Log.e("RcRiEmulatorActivity", "Error updating connection status: ${e.message}")
            }
        }
    }

    // Replace the updateRetryCount method:
    private fun updateRetryCount(count: Int) {
        runOnUiThread {
            try {
                val color = UDBColorCoding.getRetryCountColor(count)
                val textColor = UDBColorCoding.getTextColor(color)

                retryCountTextView.text = "Retries: $count"
                retryCountTextView.setTextColor(textColor)
                retryCountTextView.setBackgroundColor(color)

                // Add padding for better visibility
                retryCountTextView.setPadding(8, 4, 8, 4)

            } catch (e: Exception) {
                Log.e("RcRiEmulatorActivity", "Error updating retry count: ${e.message}")
            }
        }
    }

    // Replace the updateRangeDistance method:
    private fun updateRangeDistance(range: Int) {
        runOnUiThread {
            try {
                val rangeInMeters = range / 100.0
                val color = UDBColorCoding.getRangeColor(rangeInMeters)
                val textColor = UDBColorCoding.getTextColor(color)

                rangeDistanceTextView.text = "Range: ${String.format("%.2f", rangeInMeters)}m"
                rangeDistanceTextView.setTextColor(textColor)
                rangeDistanceTextView.setBackgroundColor(color)
                rangeDistanceTextView.setPadding(8, 4, 8, 4)

            } catch (e: Exception) {
                Log.e("RcRiEmulatorActivity", "Error updating range: ${e.message}")
            }
        }
    }

    // Enhanced countdown with proper coloring:
    private fun startReleaseCountdown() {
        try {
            // Calculate countdown: 10 seconds + 1 second per 2m of range
            val baseTime = 10000L // 10 seconds
            val rangeTime = (currentRange / 200) * 1000L // 1 second per 2m
            val totalTime = baseTime + rangeTime

            countDownTimer?.cancel()
            countDownTimer = object : CountDownTimer(totalTime, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    val seconds = millisUntilFinished / 1000
                    runOnUiThread {
                        try {
                            countdownTextView.text = "Pop-up ETA: ${seconds}s"
                            countdownTextView.visibility = View.VISIBLE

                            // Color code based on time remaining
                            val color = when {
                                seconds > 30 -> UDBColorCoding.StatusColors.INFO
                                seconds > 10 -> UDBColorCoding.StatusColors.WARNING
                                else -> UDBColorCoding.StatusColors.CRITICAL
                            }

                            countdownTextView.setBackgroundColor(color)
                            countdownTextView.setTextColor(UDBColorCoding.getTextColor(color))
                            countdownTextView.setPadding(8, 4, 8, 4)

                        } catch (e: Exception) {
                            Log.e("RcRiEmulatorActivity", "Error updating countdown: ${e.message}")
                        }
                    }
                }

                override fun onFinish() {
                    runOnUiThread {
                        try {
                            countdownTextView.text = "🎯 POP-UP EXPECTED!"
                            countdownTextView.setBackgroundColor(UDBColorCoding.StatusColors.SUCCESS)
                            countdownTextView.setTextColor(UDBColorCoding.getTextColor(UDBColorCoding.StatusColors.SUCCESS))
                            playReleaseAlert()
                        } catch (e: Exception) {
                            Log.e("RcRiEmulatorActivity", "Error finishing countdown: ${e.message}")
                        }
                    }
                }
            }
            countDownTimer?.start()
        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error starting countdown: ${e.message}")
        }
    }

    // Dialog Functions with error handling
    private fun showReleaseConfirmationDialog(onConfirm: () -> Unit) {
        try {
            AlertDialog.Builder(this)
                .setTitle("⚠️ RELEASE CONFIRMATION")
                .setMessage("This will TRIGGER the acoustic release!\n\nReal range: ${currentRange/100.0}m\nDevice temp: ${String.format("%.1f", deviceTemperature)}°C\n\nAre you sure?")
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton("YES - RELEASE") { _, _ ->
                    safeExecute("Release Trigger", onConfirm)
                }
                .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                .show()
        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error showing confirmation dialog: ${e.message}")
        }
    }

    private fun showBroadcastDialog() {
        try {
            val input = EditText(this).apply {
                hint = "Enter Group ID (1-999)"
                setText("1")
            }

            AlertDialog.Builder(this)
                .setTitle("Broadcast Group Release")
                .setMessage("Enter Group ID for broadcast release:")
                .setView(input)
                .setPositiveButton("START BROADCAST") { _, _ ->
                    val groupId = input.text.toString().toIntOrNull() ?: 1
                    safeExecute("Broadcast Group $groupId") {
                        rcriEmulator.popupBroadcast(groupId)
                    }
                }
                .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                .show()
        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error showing broadcast dialog: ${e.message}")
        }
    }

    private fun showError(message: String) {
        runOnUiThread {
            try {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                AlertDialog.Builder(this)
                    .setTitle("Operation Error")
                    .setMessage(message)
                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                    .show()
            } catch (e: Exception) {
                Log.e("RcRiEmulatorActivity", "Error showing error dialog: ${e.message}")
            }
        }
    }

    // Audio Alert Functions
    private fun playDetectionAlert() {
        try {
            val mediaPlayer = MediaPlayer.create(this, android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)
            mediaPlayer?.start()
        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Failed to play detection alert: ${e.message}")
        }
    }

    private fun playReleaseAlert() {
        try {
            val mediaPlayer = MediaPlayer.create(this, android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI)
            mediaPlayer?.start()
        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Failed to play release alert: ${e.message}")
        }
    }

    // Existing functions (with error handling added)
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
                Log.e("RcRiEmulatorActivity", "Error updating register table: ${e.message}")
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
        try {
            val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_register, null)
            setupDialogViews(dialogView, register)

            AlertDialog.Builder(this).setView(dialogView).setPositiveButton("OK") { _, _ ->
                handleRegisterUpdate(register, valueTextView, dialogView)
            }.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }.create().show()
        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error showing edit dialog: ${e.message}")
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
            Log.e("RcRiEmulatorActivity", "Error setting up dialog views: ${e.message}")
        }
    }

    private fun handleRegisterUpdate(
        register: Register, valueTextView: TextView, dialogView: View
    ) {
        try {
            val newValue = dialogView.findViewById<EditText>(R.id.register_value_input).text.toString()
            register.setValueString(newValue)
            valueTextView.text = register.getValueString() ?: "null"
            sendCommand(
                DSSCommand.createSTCommand(
                    "RC-RI", "UDB", register.name, register.getValue().toString()
                )
            )
        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error updating register: ${e.message}")
            Toast.makeText(this, "Invalid input: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateHistoryTextView() {
        runOnUiThread {
            try {
                historyTextView.text = rcriEmulator.getCommandHistory()
            } catch (e: Exception) {
                Log.e("RcRiEmulatorActivity", "Error updating history: ${e.message}")
            }
        }
    }


    // Replace the updateReleaseStateTextView method:
    private fun updateReleaseStateTextView() {
        runOnUiThread {
            try {
                var state = try {
                    rcriEmulator.getReleaseState()
                } catch (e: Exception) {
                    // Fallback: determine state from registers
                    val rStateRpt = com.dss.emulator.register.Registers.RSTATE_RPT.getValue() as? Int ?: 0
                    when (rStateRpt) {
                        0x1 -> ReleaseState.IDLE_ACK
                        0x12 -> ReleaseState.INIT_OK
                        0x23 -> ReleaseState.CON_OK
                        0x32 -> ReleaseState.RNG_SINGLE_OK
                        0x42 -> ReleaseState.RNG_CONT_OK
                        0x52 -> ReleaseState.AT_ARM_OK
                        0x55 -> ReleaseState.AT_TRG_OK
                        0x62 -> ReleaseState.BCR_OK
                        0x72 -> ReleaseState.PI_QID_DETECT
                        0x82 -> ReleaseState.PI_ID_DETECT
                        0x92 -> ReleaseState.NT_OK
                        else -> ReleaseState.IDLE_REQ
                    }
                }

                // Use the standardized color coding
                val statusColor = UDBColorCoding.getStateColor(state)
                val textColor = UDBColorCoding.getTextColor(statusColor)
                val statusMessage = UDBColorCoding.getStatusMessage(state)

                releaseStateTextView.text = statusMessage
                releaseStateTextView.setBackgroundColor(statusColor)
                releaseStateTextView.setTextColor(textColor)
                releaseStateTextView.setPadding(12, 8, 12, 8)

                // Update current state tracking
                currentState = state

                Log.d("StateUpdate", "Updated release state: $state with color: $statusColor")

            } catch (e: Exception) {
                Log.e("RcRiEmulatorActivity", "Error updating release state: ${e.message}")
            }
        }
    }
// ==================================================================
// Add this periodic update method - call from onCreate():
// ==================================================================

    private fun startPeriodicUIUpdates() {
        val updateRunnable = object : Runnable {
            override fun run() {
                try {
                    // Update UI from register values every 2 seconds
                    updateAllUIFromRegisters()

                    // Schedule next update
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 2000)
                } catch (e: Exception) {
                    Log.e("RcRiEmulatorActivity", "Error in periodic UI update: ${e.message}")
                }
            }
        }

        // Start periodic updates
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(updateRunnable, 1000)
    }


    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun updateFirmware() {
        try {
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
                        showError("Firmware upload failed: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("RcRiEmulatorActivity", "Error starting firmware update: ${e.message}")
        }
    }
}