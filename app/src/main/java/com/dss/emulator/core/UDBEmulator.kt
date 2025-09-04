package com.dss.emulator.core

import android.content.Context
import android.util.Log
import com.dss.emulator.bluetooth.peripheral.BLEPeripheralController
import com.dss.emulator.dsscommand.DSSCommand
import com.dss.emulator.dsscommand.StandardRequest
import com.dss.emulator.register.Direction
import com.dss.emulator.register.Register
import com.dss.emulator.register.Registers
import com.dss.emulator.register.registerMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class UDBEmulator : IEmulator {

    companion object {
        const val PASSWORD = "1776"
    }

    private val blePeripheralController: BLEPeripheralController
    private val firmwareLines: MutableList<String> = mutableListOf()
    private val appContext: Context

    // Enhanced simulation parameters
    private var currentRangeValue: Int = Random.nextInt(1000, 5000) // Random range in cm
    private var noiseLevel: Int = Random.nextInt(20, 60) // Random noise level in dB
    private var detectionThreshold: Int = 45 // Default detection threshold in dB
    private var batteryLevel: Int = Random.nextInt(1200, 1500) // 12-15V in hundredths
    private var waterTemp: Int = Random.nextInt(50, 250) // 5-25°C in tenths
    private var depth: Int = Random.nextInt(500, 2000) // 5-20m in cm

    // Simulation control flags
    private var simulateFailures: Boolean = false
    private var failureRate: Float = 0.2f // 20% failure rate
    private var isRangingContinuously: Boolean = false

    constructor(
        context: Context, blePeripheralController: BLEPeripheralController
    ) : super(context) {
        this.blePeripheralController = blePeripheralController
        this.appContext = context.applicationContext
        this.setSource("UDB")
        this.setDestination("RC-RI")

        // Initialize default register values
        initializeDefaultValues()
    }

    private fun initializeDefaultValues() {
        Registers.MODEL.setValue(12345)
        Registers.SN.setValue(67890)
        Registers.FIRMWARE.setValue(0x0123)
        Registers.SELFTEST.setValue(0x0000) // All tests pass
        Registers.AR_ACP.setValue(101) // ARC-1 LF protocol
        Registers.AR_THOLD_DB.setValue(detectionThreshold)
        Registers.AR_NOISE_DB.setValue(noiseLevel)

        // Initialize range report registers
        Registers.RR1_DIV.setValue(100)
        Registers.RR1_LGD.setValue("Battery V")
        Registers.RR2_DIV.setValue(10)
        Registers.RR2_LGD.setValue("Temp °C")
        Registers.RR3_DIV.setValue(100)
        Registers.RR3_LGD.setValue("Depth m")
        Registers.RR4_DIV.setValue(1)
        Registers.RR4_LGD.setValue("Status")

        // Set default status report values
        Registers.RR1_VAL.setValue(batteryLevel)
        Registers.RR2_VAL.setValue(waterTemp)
        Registers.RR3_VAL.setValue(depth)
        Registers.RR4_VAL.setValue(1) // Status OK
    }

    fun getFirmwareLines(): List<String> {
        return firmwareLines
    }

    fun clearFirmware() {
        firmwareLines.clear()
    }

    override fun sendData(data: ByteArray) {
        this.blePeripheralController.sendData(data)
    }

    override fun parseDollarCommand(command: DSSCommand) {
        this.handleCommand(command)
    }

    override fun parseBinaryCommand(data: ByteArray) {
        TODO("Not yet implemented")
    }

    private fun parseGTCommand(command: DSSCommand) {
        require(command.command == StandardRequest.GT.toString()) { "Invalid command: Expected GET (GT) command" }
        require(command.data.size == 1) { "Invalid data size: Expected exactly one data entry" }

        val registerName = command.data[0]
        val register = registerMap[registerName]
            ?: throw IllegalArgumentException("Invalid register: '$registerName' not found in register map")
        val registerValue = register.getValue().toString()

        Log.d("UDBEmulator", "parseGTCommand: $registerName $registerValue")

        this.sendCommand(
            DSSCommand.createRTResponse(
                this.getDestination(),
                this.getSource(),
                register.name,
                registerValue,
                command.commandId
            )
        )
    }

    private fun parseSTCommand(command: DSSCommand) {
        require(command.command == StandardRequest.ST.toString()) { "Invalid command: Expected SET (ST) command" }
        require(command.data.size == 2) { "Invalid data size: Expected exactly two data entries" }
        val registerName = command.data[0]
        val registerValue = command.data[1]
        val register = registerMap[registerName]
            ?: throw IllegalArgumentException("Invalid register: '$registerName' not found in register map")
        Log.d("UDBEmulator", "parseSTCommand: $registerName $registerValue")

        if (register.direction == Direction.GUI_TO_UDB || register.direction == Direction.BOTH) {
            register.setValueString(registerValue)

            if (register == Registers.RSTATE_REQ) {
                handleRStateREQValueChange()
            }

            if (register == Registers.AR_THOLD_DB) {
                detectionThreshold = register.getValue() as? Int ?: 45
            }
        }

        this.sendCommand(
            if (register.direction == Direction.GUI_TO_UDB || register.direction == Direction.BOTH) {
                DSSCommand.createOKResponse(
                    this.getDestination(), this.getSource(), command.commandId
                )
            } else {
                DSSCommand.createNOResponse(
                    this.getDestination(), this.getSource(), command.commandId
                )
            }
        )
    }

    private fun parseGICommand(command: DSSCommand) {
        require(command.command == StandardRequest.GI.toString()) { "Invalid command: Expected GET ID (GI) command" }
        require(command.data.isEmpty()) { "Invalid data size: Expected no data entries" }
        val serialNumber = Registers.SN.getValue().toString()

        Log.d("UDBEmulator", "parseGICommand: $serialNumber")

        this.sendCommand(
            DSSCommand.createIDResponse(
                this.getDestination(), this.getSource(), serialNumber, command.commandId
            )
        )
    }

    private fun parseSICommand(command: DSSCommand) {
        require(command.command == StandardRequest.SI.toString()) { "Invalid command: Expected SET ID (SI) command" }
        require(command.data.size == 2) { "Invalid data size: Expected exactly two data entries" }
        val password = command.data[0]
        val serialNumber = command.data[1]

        Log.d("UDBEmulator", "parseSICommand: $password $serialNumber")

        this.sendCommand(
            if (password == PASSWORD) {
                Registers.SN.setValueString(serialNumber)
                DSSCommand.createOKResponse(
                    this.getDestination(), this.getSource(), command.commandId
                )
            } else {
                DSSCommand.createNOResponse(
                    this.getDestination(), this.getSource(), command.commandId
                )
            }
        )
    }

    private fun parseSPCommand(command: DSSCommand) {
        require(command.command == StandardRequest.SP.toString()) { "Invalid command: Expected SET Protected Register (SP) command" }
        require(command.data.size == 3) { "Invalid data size: Expected exactly three data entries" }
        val password = command.data[0]
        val registerName = command.data[1]
        val registerValue = command.data[2]

        Log.d("UDBEmulator", "parseSPCommand: $password $registerName $registerValue")

        if (password == PASSWORD) {
            val register = registerMap[registerName]
                ?: throw IllegalArgumentException("Invalid register: '$registerName' not found in register map")
            register.setValueString(registerValue)

            this.sendCommand(
                if (register.direction == Direction.GUI_TO_UDB || register.direction == Direction.BOTH) {
                    DSSCommand.createOKResponse(
                        this.getDestination(), this.getSource(), command.commandId
                    )
                } else {
                    DSSCommand.createNOResponse(
                        this.getDestination(), this.getSource(), command.commandId
                    )
                }
            )
        } else {
            this.sendCommand(
                DSSCommand.createNOResponse(
                    this.getDestination(), this.getSource(), command.commandId
                )
            )
        }
    }

    private fun parseFTCommand(command: DSSCommand) {
        require(command.command == StandardRequest.FT.toString()) { "Invalid command: Expected Factory Test (FT) command" }
        Log.d("UDBEmulator", "parseFactoryTestCommand")

        // Simulate factory test with realistic delay
        CoroutineScope(Dispatchers.IO).launch {
            delay(2000)
            this@UDBEmulator.sendCommand(
                DSSCommand.createOKResponse(
                    this@UDBEmulator.getDestination(),
                    this@UDBEmulator.getSource(),
                    command.commandId
                )
            )
        }
    }

    private fun parseLDCommand(command: DSSCommand) {
        require(command.command == StandardRequest.LD.toString()) { "Invalid command: Expected LD (firmware load) command" }
        require(command.data.size == 1) { "Expected exactly one data entry in LD command" }

        val line = command.data[0].trim()
        Log.d("UDBEmulator", "Received firmware line: $line")

        firmwareLines.add(line)

        this.sendCommand(
            DSSCommand.createAckResponse(this.getDestination(), this.getSource(), command.commandId)
        )
    }

    private fun parseRBCommand(command: DSSCommand) {
        require(command.command == StandardRequest.RB.toString()) { "Invalid command: Expected Reboot (RB) command" }
        Log.d("UDBEmulator", "Reboot requested. Applying firmware...")

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val firmwareFile = File(appContext.filesDir, "firmware_${timestamp}.bin")

        try {
            FileOutputStream(firmwareFile).use { fos ->
                for ((index, line) in firmwareLines.withIndex()) {
                    fos.write((line + "\n").toByteArray())
                    Log.d("UDBEmulator", "Saved firmware line $index: $line")
                }
            }
            Log.i("UDBEmulator", "Firmware saved to: ${firmwareFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("UDBEmulator", "Failed to save firmware: ${e.message}")
        }

        this.sendCommand(
            DSSCommand.createOKResponse(this.getDestination(), this.getSource(), command.commandId)
        )
    }

    private fun handleCommand(command: DSSCommand) {
        when (command.command) {
            StandardRequest.GT.toString() -> parseGTCommand(command)
            StandardRequest.ST.toString() -> parseSTCommand(command)
            StandardRequest.SP.toString() -> parseSPCommand(command)
            StandardRequest.GI.toString() -> parseGICommand(command)
            StandardRequest.SI.toString() -> parseSICommand(command)
            StandardRequest.FT.toString() -> parseFTCommand(command)
            StandardRequest.RB.toString() -> parseRBCommand(command)
            StandardRequest.LD.toString() -> parseLDCommand(command)
            else -> throw IllegalArgumentException("Unknown command: ${command.command}")
        }
    }

    private fun sendRMCommand() {
        var regMap = Registers.REG_MAP.getValue() as Long

        if (regMap != 0L) {
            this.sendCommand(
                DSSCommand.createRMCommand(
                    this.getSource(), this.getDestination(), regMap
                )
            )
        }

        CoroutineScope(Dispatchers.IO).launch {
            delay(50)
            Registers.REG_MAP.setValue(0L)
        }
    }

    private fun dispatchRegisterChange(register: Register) {
        var regMap = Registers.REG_MAP.getValue() as Long
        regMap = regMap or (1L shl register.regMapBit)
        Registers.REG_MAP.setValue(regMap)
    }

    private fun shouldSimulateFailure(): Boolean {
        return simulateFailures && Random.nextFloat() < failureRate
    }

    private fun updateSensorValues() {
        // Add realistic sensor drift
        currentRangeValue += Random.nextInt(-100, 101)
        currentRangeValue = currentRangeValue.coerceIn(500, 8000)

        batteryLevel = (batteryLevel + Random.nextInt(-5, 1)).coerceIn(1000, 1500)
        waterTemp = (waterTemp + Random.nextInt(-2, 3)).coerceIn(20, 300)
        depth = (depth + Random.nextInt(-50, 51)).coerceIn(100, 5000)
        noiseLevel = (noiseLevel + Random.nextInt(-5, 6)).coerceIn(15, 80)

        // Update registers
        Registers.RRR_VAL.setValue(currentRangeValue)
        Registers.RR1_VAL.setValue(batteryLevel)
        Registers.RR2_VAL.setValue(waterTemp)
        Registers.RR3_VAL.setValue(depth)
        Registers.AR_NOISE_DB.setValue(noiseLevel)
    }

    // COMPLETE STATE MACHINE IMPLEMENTATION
    private fun handleRStateREQValueChange() {
        var rStateREQ = Registers.RSTATE_REQ.getValue()
        Log.d("UDBEmulator", "handleRStateREQValueChange: $rStateREQ")

        CoroutineScope(Dispatchers.IO).launch {
            when (rStateREQ) {
                0x00 -> handleIdleRequest()
                0x10 -> handleInitRequest()
                0x20 -> handleConnectionRequest()
                0x30 -> handleSingleRangeRequest()
                0x40 -> handleContinuousRangeRequest()
                0x50 -> handleTriggerRequest()
                0x60 -> handleBroadcastRequest()
                0x70 -> handlePublicQuickIDRequest()
                0x80 -> handlePublicFullIDRequest()
                0x90 -> handleNoiseTestRequest()
                0x64 -> handleRebootRequest()
            }
        }
    }

    private suspend fun handleIdleRequest() {
        Log.d("UDBEmulator", "handleIdleRequest")

        // Stop continuous ranging if active
        isRangingContinuously = false

        Registers.RSTATE_MAP.setValue(1 shl 0)
        dispatchRegisterChange(Registers.MODEL)
        dispatchRegisterChange(Registers.SN)
        dispatchRegisterChange(Registers.FIRMWARE)
        sendRMCommand()

        delay(1000)
        Registers.RSTATE_RPT.setValue(0x1) // IDLE_ACK
        dispatchRegisterChange(Registers.RSTATE_RPT)
        sendRMCommand()
    }

    private suspend fun handleInitRequest() {
        Log.d("UDBEmulator", "handleInitRequest")
        Registers.RSTATE_MAP.setValue(1 shl 1)
        Registers.RSTATE_RPT.setValue(0x11) // INIT_PENDING
        dispatchRegisterChange(Registers.RSTATE_RPT)
        sendRMCommand()

        delay(2000) // Simulate initialization time

        // Check for initialization failure
        if (shouldSimulateFailure()) {
            Registers.RSTATE_RPT.setValue(0x13) // INIT_FAIL
            Registers.SELFTEST.setValue(0x0001) // Set a failure bit
            dispatchRegisterChange(Registers.SELFTEST)
        } else {
            // Successful initialization
            Registers.RSTATE_RPT.setValue(0x12) // INIT_OK

            // Update all relevant registers for initialization
            Registers.RR1_LGD.setValue("Battery V")
            Registers.RR2_LGD.setValue("Temp °C")
            Registers.RR3_LGD.setValue("Depth m")
            Registers.RR4_LGD.setValue("Status")

            dispatchRegisterChange(Registers.AR_ACP)
            dispatchRegisterChange(Registers.RSTATE_MAP)
            dispatchRegisterChange(Registers.RR_MAP)
            dispatchRegisterChange(Registers.RR1_DIV)
            dispatchRegisterChange(Registers.RR1_LGD)
            dispatchRegisterChange(Registers.RR2_DIV)
            dispatchRegisterChange(Registers.RR2_LGD)
            dispatchRegisterChange(Registers.RR3_DIV)
            dispatchRegisterChange(Registers.RR3_LGD)
            dispatchRegisterChange(Registers.RR4_DIV)
            dispatchRegisterChange(Registers.RR4_LGD)
            dispatchRegisterChange(Registers.SELFTEST)
            dispatchRegisterChange(Registers.AR_THOLD_DB)
        }

        dispatchRegisterChange(Registers.RSTATE_RPT)
        sendRMCommand()
    }

    private suspend fun handleConnectionRequest() {
        Log.d("UDBEmulator", "handleConnectionRequest")
        Registers.RSTATE_MAP.setValue(1 shl 2)

        // Connection sequence with ID packet simulation
        Registers.RSTATE_RPT.setValue(0x21) // CON_ID1
        dispatchRegisterChange(Registers.RSTATE_RPT)
        sendRMCommand()

        delay(2000) // ID packet 1 processing time

        Registers.RSTATE_RPT.setValue(0x22) // CON_ID2
        dispatchRegisterChange(Registers.RSTATE_RPT)
        sendRMCommand()

        delay(2000) // ID packet 2 processing time

        if (shouldSimulateFailure()) {
            // Connection could fail and return to a previous state
            // For now, let's assume it eventually succeeds
            delay(1000)
        }

        Registers.RSTATE_RPT.setValue(0x23) // CON_OK
        dispatchRegisterChange(Registers.RSTATE_RPT)
        sendRMCommand()
    }

    private suspend fun handleSingleRangeRequest() {
        Log.d("UDBEmulator", "handleSingleRangeRequest")
        Registers.RSTATE_MAP.setValue(1 shl 3)
        Registers.RSTATE_RPT.setValue(0x31) // RNG_SINGLE_PENDING
        dispatchRegisterChange(Registers.RSTATE_RPT)
        sendRMCommand()

        delay(3000) // Simulate ranging time

        if (shouldSimulateFailure()) {
            // Range failure
            val rrMiss = (Registers.RR_MISS.getValue() as? Int ?: 0) + 1
            Registers.RR_MISS.setValue(rrMiss)
            Registers.RSTATE_RPT.setValue(0x33) // RNG_SINGLE_FAIL
            dispatchRegisterChange(Registers.RR_MISS)
        } else {
            // Successful range
            updateSensorValues()

            val rrCtr = (Registers.RR_CTR.getValue() as? Int ?: 0) + 1
            Registers.RR_CTR.setValue(rrCtr)
            Registers.RR_MISS.setValue(0) // Reset miss counter on success

            Registers.RSTATE_RPT.setValue(0x32) // RNG_SINGLE_OK
            dispatchRegisterChange(Registers.RRR_VAL)
            dispatchRegisterChange(Registers.RR1_VAL)
            dispatchRegisterChange(Registers.RR2_VAL)
            dispatchRegisterChange(Registers.RR3_VAL)
            dispatchRegisterChange(Registers.RR4_VAL)
            dispatchRegisterChange(Registers.RR_CTR)
            dispatchRegisterChange(Registers.RR_MISS)
        }

        dispatchRegisterChange(Registers.RSTATE_RPT)
        sendRMCommand()
    }

    private suspend fun handleContinuousRangeRequest() {
        Log.d("UDBEmulator", "handleContinuousRangeRequest")
        Registers.RSTATE_MAP.setValue(1 shl 4)
        Registers.RSTATE_RPT.setValue(0x41) // RNG_CONT_PENDING
        dispatchRegisterChange(Registers.RSTATE_RPT)
        sendRMCommand()

        delay(2000)

        if (shouldSimulateFailure()) {
            val rrMiss = (Registers.RR_MISS.getValue() as? Int ?: 0) + 1
            Registers.RR_MISS.setValue(rrMiss)
            Registers.RSTATE_RPT.setValue(0x43) // RNG_CONT_FAIL
            dispatchRegisterChange(Registers.RR_MISS)
        } else {
            Registers.RSTATE_RPT.setValue(0x42) // RNG_CONT_OK
            isRangingContinuously = true
            // Start continuous ranging loop
            startContinuousRanging()
        }

        dispatchRegisterChange(Registers.RSTATE_RPT)
        sendRMCommand()
    }

    private fun startContinuousRanging() {
        CoroutineScope(Dispatchers.IO).launch {
            while (isRangingContinuously && Registers.RSTATE_RPT.getValue() == 0x42) {
                delay(5000) // Range every 5 seconds

                updateSensorValues()

                val rrCtr = (Registers.RR_CTR.getValue() as? Int ?: 0) + 1
                Registers.RR_CTR.setValue(rrCtr)

                dispatchRegisterChange(Registers.RRR_VAL)
                dispatchRegisterChange(Registers.RR1_VAL)
                dispatchRegisterChange(Registers.RR2_VAL)
                dispatchRegisterChange(Registers.RR3_VAL)
                dispatchRegisterChange(Registers.RR4_VAL)
                dispatchRegisterChange(Registers.RR_CTR)
                sendRMCommand()

                Log.d("UDBEmulator", "Continuous range update: ${currentRangeValue}cm")
            }
        }
    }

    private suspend fun handleTriggerRequest() {
        Log.d("UDBEmulator", "handleTriggerRequest")
        Registers.RSTATE_MAP.setValue(1 shl 5)

        // Stop continuous ranging if active
        isRangingContinuously = false

        // Arm sequence
        Registers.RSTATE_RPT.setValue(0x51) // AT_ARM_PENDING
        dispatchRegisterChange(Registers.RSTATE_RPT)
        sendRMCommand()

        delay(3000) // Arming time

        if (shouldSimulateFailure()) {
            val rrMiss = (Registers.RR_MISS.getValue() as? Int ?: 0) + 1
            Registers.RR_MISS.setValue(rrMiss)
            Registers.RSTATE_RPT.setValue(0x53) // AT_ARM_FAIL
            dispatchRegisterChange(Registers.RR_MISS)
        } else {
            Registers.RSTATE_RPT.setValue(0x52) // AT_ARM_OK
        }

        dispatchRegisterChange(Registers.RSTATE_RPT)
        sendRMCommand()

        if (Registers.RSTATE_RPT.getValue() == 0x52) {
            delay(1000)
            // Trigger sequence
            Registers.RSTATE_RPT.setValue(0x54) // AT_TRG_PENDING
            dispatchRegisterChange(Registers.RSTATE_RPT)
            sendRMCommand()

            delay(2000) // Trigger time

            if (shouldSimulateFailure()) {
                val rrMiss = (Registers.RR_MISS.getValue() as? Int ?: 0) + 1
                Registers.RR_MISS.setValue(rrMiss)
                Registers.RSTATE_RPT.setValue(0x56) // AT_TRG_FAIL
                dispatchRegisterChange(Registers.RR_MISS)
            } else {
                Registers.RSTATE_RPT.setValue(0x55) // AT_TRG_OK (RELEASED!)

                // Update status to indicate release
                Registers.RR4_VAL.setValue(0) // Status: Released
                dispatchRegisterChange(Registers.RR4_VAL)
            }

            dispatchRegisterChange(Registers.RSTATE_RPT)
            sendRMCommand()
        }
    }

    private suspend fun handleBroadcastRequest() {
        Log.d("UDBEmulator", "handleBroadcastRequest")
        Registers.RSTATE_MAP.setValue(1 shl 6)
        Registers.RSTATE_RPT.setValue(0x61) // BCR_PENDING
        dispatchRegisterChange(Registers.RSTATE_RPT)
        sendRMCommand()

        delay(2000) // Broadcast transmission time

        val rrCtr = (Registers.RR_CTR.getValue() as? Int ?: 0) + 1
        Registers.RR_CTR.setValue(rrCtr)

        Registers.RSTATE_RPT.setValue(0x62) // BCR_OK
        dispatchRegisterChange(Registers.RR_CTR)
        dispatchRegisterChange(Registers.RSTATE_RPT)
        sendRMCommand()
    }

    private suspend fun handlePublicQuickIDRequest() {
        Log.d("UDBEmulator", "handlePublicQuickIDRequest")
        Registers.RSTATE_MAP.setValue(1 shl 7)
        Registers.RSTATE_RPT.setValue(0x71) // PI_QID_PENDING
        dispatchRegisterChange(Registers.RSTATE_RPT)
        sendRMCommand()

        delay(3000) // Search time

        // Simulate detection probability based on range and noise
        val detectionProbability = if (noiseLevel < detectionThreshold) 0.8f else 0.3f

        if (Random.nextFloat() < detectionProbability) {
            // Detection successful
            Registers.PUBLIC_QID.setValue(Random.nextInt(100, 999))
            Registers.RRR_VAL.setValue(currentRangeValue)

            val rrCtr = (Registers.RR_CTR.getValue() as? Int ?: 0) + 1
            Registers.RR_CTR.setValue(rrCtr)
            Registers.RR_MISS.setValue(0) // Reset miss counter

            Registers.RSTATE_RPT.setValue(0x72) // PI_QID_DETECT
            dispatchRegisterChange(Registers.RR_CTR)
            dispatchRegisterChange(Registers.RRR_VAL)
            dispatchRegisterChange(Registers.PUBLIC_QID)
            dispatchRegisterChange(Registers.RR_MISS)
        } else {
            // No detection
            val rrMiss = (Registers.RR_MISS.getValue() as? Int ?: 0) + 1
            Registers.RR_MISS.setValue(rrMiss)

            Registers.RSTATE_RPT.setValue(0x73) // PI_QID_NODETECT
            dispatchRegisterChange(Registers.RR_MISS)
        }

        dispatchRegisterChange(Registers.RSTATE_RPT)
        sendRMCommand()
    }

    private suspend fun handlePublicFullIDRequest() {
        Log.d("UDBEmulator", "handlePublicFullIDRequest")
        Registers.RSTATE_MAP.setValue(1 shl 8)
        Registers.RSTATE_RPT.setValue(0x81) // PI_ID_PENDING
        dispatchRegisterChange(Registers.RSTATE_RPT)
        sendRMCommand()

        delay(4000) // Longer search time for full ID

        // Lower detection probability for full ID
        val detectionProbability = if (noiseLevel < detectionThreshold) 0.7f else 0.2f

        if (Random.nextFloat() < detectionProbability) {
            // Detection successful
            Registers.PUBLIC_ID.setValue(Random.nextInt(10000, 99999))
            Registers.RRR_VAL.setValue(currentRangeValue)

            val rrCtr = (Registers.RR_CTR.getValue() as? Int ?: 0) + 1
            Registers.RR_CTR.setValue(rrCtr)
            Registers.RR_MISS.setValue(0) // Reset miss counter

            Registers.RSTATE_RPT.setValue(0x82) // PI_ID_DETECT
            dispatchRegisterChange(Registers.RR_CTR)
            dispatchRegisterChange(Registers.RRR_VAL)
            dispatchRegisterChange(Registers.PUBLIC_ID)
            dispatchRegisterChange(Registers.RR_MISS)
        } else {
            // No detection
            val rrMiss = (Registers.RR_MISS.getValue() as? Int ?: 0) + 1
            Registers.RR_MISS.setValue(rrMiss)
            val rrCtr = (Registers.RR_CTR.getValue() as? Int ?: 0) + 1
            Registers.RR_CTR.setValue(rrCtr)

            Registers.RSTATE_RPT.setValue(0x83) // PI_ID_NODETECT
            dispatchRegisterChange(Registers.RR_MISS)
            dispatchRegisterChange(Registers.RR_CTR)
        }

        dispatchRegisterChange(Registers.RSTATE_RPT)
        sendRMCommand()
    }

    private suspend fun handleNoiseTestRequest() {
        Log.d("UDBEmulator", "handleNoiseTestRequest")
        Registers.RSTATE_MAP.setValue(1 shl 9)
        Registers.RSTATE_RPT.setValue(0x91) // NT_PENDING
        dispatchRegisterChange(Registers.RSTATE_RPT)
        sendRMCommand()

        delay(5000) // Noise measurement time

        // Generate realistic noise level measurement
        noiseLevel = Random.nextInt(15, 80)
        Registers.AR_NOISE_DB.setValue(noiseLevel)

        val rrCtr = (Registers.RR_CTR.getValue() as? Int ?: 0) + 1
        Registers.RR_CTR.setValue(rrCtr)

        Registers.RSTATE_RPT.setValue(0x92) // NT_OK
        dispatchRegisterChange(Registers.RR_CTR)
        dispatchRegisterChange(Registers.AR_NOISE_DB)
        dispatchRegisterChange(Registers.RSTATE_RPT)
        sendRMCommand()
    }

    private suspend fun handleRebootRequest() {
        Log.d("UDBEmulator", "handleRebootRequest")
        Registers.RSTATE_MAP.setValue(1 shl 10)

        // Stop any ongoing operations
        isRangingContinuously = false

        Registers.RSTATE_RPT.setValue(0x65) // RB_ACK
        dispatchRegisterChange(Registers.RSTATE_RPT)
        sendRMCommand()

        delay(2000) // Simulate reboot time

        // Reset to idle state after reboot
        Registers.RSTATE_REQ.setValue(0x00)
        handleIdleRequest()
    }

    // SIMULATION CONTROL METHODS
    fun setSimulateFailures(enable: Boolean, failureRate: Float = 0.2f) {
        this.simulateFailures = enable
        this.failureRate = failureRate.coerceIn(0f, 1f)
        Log.d("UDBEmulator", "Failure simulation: $enable (rate: ${this.failureRate})")
    }

    fun setCustomSensorValues(range: Int, battery: Int, temp: Int, depth: Int, noise: Int) {
        this.currentRangeValue = range.coerceIn(500, 8000)
        this.batteryLevel = battery.coerceIn(1000, 1600)
        this.waterTemp = temp.coerceIn(0, 400)
        this.depth = depth.coerceIn(100, 5000)
        this.noiseLevel = noise.coerceIn(15, 100)

        // Update registers immediately
        Registers.RRR_VAL.setValue(this.currentRangeValue)
        Registers.RR1_VAL.setValue(this.batteryLevel)
        Registers.RR2_VAL.setValue(this.waterTemp)
        Registers.RR3_VAL.setValue(this.depth)
        Registers.AR_NOISE_DB.setValue(this.noiseLevel)

        Log.d("UDBEmulator", "Custom sensor values set: Range=${this.currentRangeValue}cm, Battery=${this.batteryLevel/100.0}V, Temp=${this.waterTemp/10.0}°C, Depth=${this.depth/100.0}m, Noise=${this.noiseLevel}dB")
    }

    fun getCurrentSensorValues(): Map<String, Int> {
        return mapOf(
            "range" to currentRangeValue,
            "battery" to batteryLevel,
            "temperature" to waterTemp,
            "depth" to depth,
            "noise" to noiseLevel
        )
    }

    fun getSimulationStatus(): String {
        return """
            UDB Simulation Status:
            Range: ${currentRangeValue}cm (${currentRangeValue/100.0}m)
            Battery: ${batteryLevel/100.0}V
            Temperature: ${waterTemp/10.0}°C
            Depth: ${depth/100.0}m
            Noise Level: ${noiseLevel}dB
            Detection Threshold: ${detectionThreshold}dB
            Failure Simulation: $simulateFailures (${(failureRate*100).toInt()}%)
            Continuous Ranging: $isRangingContinuously
            Firmware Lines: ${firmwareLines.size}
        """.trimIndent()
    }
}