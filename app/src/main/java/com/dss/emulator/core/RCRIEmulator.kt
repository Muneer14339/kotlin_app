package com.dss.emulator.core

import android.content.Context
import android.util.Log
import com.dss.emulator.bluetooth.central.BLECentralController
import com.dss.emulator.dsscommand.DSSCommand
import com.dss.emulator.dsscommand.StandardRequest
import com.dss.emulator.dsscommand.StandardResponse
import com.dss.emulator.register.Register
import com.dss.emulator.register.Registers
import com.dss.emulator.register.registerList
import com.dss.emulator.register.registerMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URL

class RCRIEmulator : IEmulator {

    companion object {
        const val PASSWORD = "1776"
    }

    private val bleCentralController: BLECentralController
    private var pendingAckCallback: ((DSSCommand) -> Unit)? = null

    // State management callbacks
    private var onStateChanged: ((ReleaseState) -> Unit)? = null
    private var onRangeReceived: ((Int) -> Unit)? = null
    private var onDetectionAlert: (() -> Unit)? = null
    private var onReleaseAlert: (() -> Unit)? = null
    private var onRetryCountChanged: ((Int) -> Unit)? = null

    constructor(context: Context, bleCentralController: BLECentralController) : super(context) {
        this.bleCentralController = bleCentralController
        this.setSource("RC-RI")
        this.setDestination("UDB")
    }

    fun setPendingAckCallback(callback: (DSSCommand) -> Unit) {
        pendingAckCallback = callback
    }

    fun setStateChangedCallback(callback: (ReleaseState) -> Unit) {
        onStateChanged = callback
    }

    fun setRangeReceivedCallback(callback: (Int) -> Unit) {
        onRangeReceived = callback
    }

    fun setDetectionAlertCallback(callback: () -> Unit) {
        onDetectionAlert = callback
    }

    fun setReleaseAlertCallback(callback: () -> Unit) {
        onReleaseAlert = callback
    }

    fun setRetryCountChangedCallback(callback: (Int) -> Unit) {
        onRetryCountChanged = callback
    }

    override fun sendData(data: ByteArray) {
        this.bleCentralController.sendData(data)
    }

    override fun parseDollarCommand(command: DSSCommand) {
        this.handleCommand(command)
    }

    override fun parseBinaryCommand(data: ByteArray) {
        TODO("Not yet implemented")
    }

    private fun parseOKResponse(command: DSSCommand) {
        require(command.command == StandardResponse.OK.toString()) { "Invalid command: Expected OK (OK) command" }
        require(command.data.isEmpty()) { "Invalid data size: Expected no data entries" }
        Log.d("RCRIEmulator", "parseOKResponse")
    }

    private fun parseNOResponse(command: DSSCommand) {
        require(command.command == StandardResponse.NO.toString()) { "Invalid command: Expected NO (NO) command" }
        require(command.data.size == 0) { "Invalid data size: Expected no data entries" }
        Log.d("RCRIEmulator", "parseNOResponse")
    }

    private fun parseIDResponse(command: DSSCommand) {
        require(command.command == StandardResponse.ID.toString()) { "Invalid command: Expected ID (ID) command" }
        require(command.data.size == 1) { "Invalid data size: Expected exactly one data entry" }
        val serialNumber = command.data[0]
        Log.d("RCRIEmulator", "parseIDResponse: $serialNumber")
    }

    private fun parseRTResponse(command: DSSCommand) {
        require(command.command == StandardResponse.RT.toString()) { "Invalid command: Expected RT (RT) command" }
        require(command.data.size == 2) { "Invalid data size: Expected 2 data entries" }

        val registerName = command.data[0]
        val registerValue = command.data[1]

        Log.d("RCRIEmulator", "parseRTResponse: $registerName $registerValue")

        var register = registerMap[registerName]
            ?: throw IllegalArgumentException("Unknown register: $registerName")
        register.setValueString(registerValue)

        if (register == Registers.RSTATE_RPT) {
            handleRStateRPTValueChange()
        }

        if (register == Registers.RRR_VAL) {
            val rangeValue = register.getValue() as? Int ?: 0
            onRangeReceived?.invoke(rangeValue)
        }
    }

    private fun parseRMCommand(command: DSSCommand) {
        require(command.command == StandardRequest.RM.toString()) { "Invalid command: Expected Register Map Change Report (RM) command" }
        require(command.data.size == 1) { "Invalid data size: Expected one data entry here" }

        Log.d("RCRIEmulator", "parseRMCommand")

        val registerMapBit = command.data[0].toLong()

        for (register in registerList) {
            if ((1L shl register.regMapBit) and registerMapBit != 0L) {
                Log.d("RCRIEmulator", "parseRMCommand: ${register.name}")

                this.sendCommand(
                    DSSCommand.createGTCommand(
                        this.getDestination(), this.getSource(), register.name
                    )
                )
            }
        }
    }

    private fun handleCommand(command: DSSCommand) {
        when (command.command) {
            StandardResponse.OK.toString() -> {
                parseOKResponse(command)
                pendingAckCallback?.invoke(command)
                pendingAckCallback = null
            }

            StandardResponse.NO.toString() -> {
                parseNOResponse(command)
                pendingAckCallback?.invoke(command)
                pendingAckCallback = null
            }

            StandardResponse.ACK.toString() -> {
                pendingAckCallback?.invoke(command)
                pendingAckCallback = null
            }

            StandardResponse.NAK.toString() -> {
                pendingAckCallback?.invoke(command)
                pendingAckCallback = null
            }

            StandardResponse.ID.toString() -> parseIDResponse(command)
            StandardResponse.RT.toString() -> parseRTResponse(command)
            StandardRequest.RM.toString() -> parseRMCommand(command)
            else -> throw IllegalArgumentException("Unknown command: ${command.command}")
        }
    }

    fun sendGTCommand(register: Register) {
        this.sendCommand(
            DSSCommand.createGTCommand(
                this.getDestination(), this.getSource(), register.name
            )
        )
    }

    fun sendSTCommand(register: Register) {
        this.sendCommand(
            DSSCommand.createSTCommand(
                this.getDestination(),
                this.getSource(),
                register.name,
                register.getValue().toString()
            )
        )
    }

    private var rState: ReleaseState = ReleaseState.IDLE_REQ
        get() {
            return field
        }
        set(value) {
            field = value
            onStateChanged?.invoke(value)
        }

    private fun updateRState(newRState: ReleaseState) {
        this.rState = newRState
    }

    // BASIC STATE OPERATIONS (Already implemented)
    fun popupIdle() {
        this.logHistory("------ popupIdle ------\r\n")

        Registers.RSTATE_REQ.setValue(0)
        this.sendSTCommand(Registers.RSTATE_REQ)
        this.rState = ReleaseState.IDLE_REQ
    }

    fun popupInit() {
        this.logHistory("------ popupInit ------\r\n")

        require(this.rState == ReleaseState.IDLE_ACK) { "Must be in IDLE_ACK state to initialize. Current state: ${this.rState}" }

        this.rState = ReleaseState.INIT_REQ
        Registers.RSTATE_REQ.setValue(0x10)
        this.sendSTCommand(Registers.RSTATE_REQ)

        Registers.AR_MFG.setValue("ASH")
        Registers.AR_MODEL.setValue("ARC1-12")
        Registers.SOUNDSPEED.setValue(0x01)
        Registers.RANGE_MAX.setValue(0x01)

        this.sendSTCommand(Registers.AR_MFG)
        this.sendSTCommand(Registers.AR_MODEL)
        this.sendSTCommand(Registers.SOUNDSPEED)
        this.sendSTCommand(Registers.RANGE_MAX)
    }

    fun popupConnect() {
        this.logHistory("------ popupConnect ------\r\n")
        require(this.rState == ReleaseState.INIT_OK) { "Must be in INIT_OK state to connect. Current state: ${this.rState}" }

        this.rState = ReleaseState.CON_REQ
        Registers.RSTATE_REQ.setValue(0x20)
        this.sendSTCommand(Registers.RSTATE_REQ)

        // Set PIN_ID for connection
        Registers.PIN_ID.setValue(12345)
        this.sendSTCommand(Registers.PIN_ID)
    }

    // RANGING OPERATIONS
    fun popupSrange() {
        this.logHistory("------ popupSrange ------\r\n")
        require(this.rState == ReleaseState.CON_OK ||
                this.rState == ReleaseState.RNG_SINGLE_OK ||
                this.rState == ReleaseState.RNG_CONT_OK) {
            "Must be connected or in a ranging state to perform single ranging. Current state: ${this.rState}"
        }

        this.rState = ReleaseState.RNG_SINGLE_REQ

        Registers.RSTATE_REQ.setValue(0x30)
        this.sendSTCommand(Registers.RSTATE_REQ)

        // Reset ranging counters
        Registers.RR_MISS.setValue(0)
        Registers.RR_CTR.setValue(0)
        this.sendSTCommand(Registers.RR_MISS)
        this.sendSTCommand(Registers.RR_CTR)
    }

    fun popupCrange() {
        this.logHistory("------ popupCrange ------\r\n")
        require(this.rState == ReleaseState.CON_OK ||
                this.rState == ReleaseState.RNG_SINGLE_OK ||
                this.rState == ReleaseState.RNG_CONT_OK) {
            "Must be connected or in a ranging state to perform continuous ranging. Current state: ${this.rState}"
        }

        Registers.RSTATE_REQ.setValue(0x40)
        this.sendSTCommand(Registers.RSTATE_REQ)
        this.rState = ReleaseState.RNG_CONT_REQ

        // Reset ranging counters
        Registers.RR_MISS.setValue(0)
        Registers.RR_CTR.setValue(0)
        this.sendSTCommand(Registers.RR_MISS)
        this.sendSTCommand(Registers.RR_CTR)
    }

    // TRIGGER OPERATIONS
    fun popupTrigger() {
        this.logHistory("------ popupTrigger ------\r\n")
        require(this.rState == ReleaseState.CON_OK ||
                this.rState == ReleaseState.RNG_SINGLE_OK ||
                this.rState == ReleaseState.RNG_CONT_OK) {
            "Must be connected or in a ranging state to trigger release. Current state: ${this.rState}"
        }

        Registers.RSTATE_REQ.setValue(0x50)
        this.sendSTCommand(Registers.RSTATE_REQ)
        this.rState = ReleaseState.AT_REQ

        // Reset counters for trigger operation
        Registers.RR_MISS.setValue(0)
        Registers.RR_CTR.setValue(0)
        this.sendSTCommand(Registers.RR_MISS)
        this.sendSTCommand(Registers.RR_CTR)
    }

    // BROADCAST OPERATIONS
    fun popupBroadcast(groupId: Int = 1) {
        this.logHistory("------ popupBroadcast (Group: $groupId) ------\r\n")
        require(this.rState == ReleaseState.INIT_OK) {
            "Must be in INIT_OK state to broadcast. Current state: ${this.rState}"
        }

        Registers.RSTATE_REQ.setValue(0x60)
        this.sendSTCommand(Registers.RSTATE_REQ)
        this.rState = ReleaseState.BCR_REQ

        // Set GROUP_ID for broadcast
        Registers.GROUP_ID.setValue(groupId)
        this.sendSTCommand(Registers.GROUP_ID)

        // Reset counter for broadcast
        Registers.RR_CTR.setValue(0)
        this.sendSTCommand(Registers.RR_CTR)
    }

    // PUBLIC INTERROGATE OPERATIONS
    fun popupPIQID() {
        this.logHistory("------ popupPIQID (Public Quick ID) ------\r\n")
        require(this.rState == ReleaseState.INIT_OK) {
            "Must be in INIT_OK state to perform public interrogate. Current state: ${this.rState}"
        }

        Registers.RSTATE_REQ.setValue(0x70)
        this.sendSTCommand(Registers.RSTATE_REQ)
        this.rState = ReleaseState.PI_QID_REQ

        // Reset counters for public interrogate
        Registers.RR_CTR.setValue(0)
        Registers.RR_MISS.setValue(0)
        this.sendSTCommand(Registers.RR_CTR)
        this.sendSTCommand(Registers.RR_MISS)
    }

    fun popupPIID() {
        this.logHistory("------ popupPIID (Public Full ID) ------\r\n")
        require(this.rState == ReleaseState.INIT_OK) {
            "Must be in INIT_OK state to perform public interrogate. Current state: ${this.rState}"
        }

        Registers.RSTATE_REQ.setValue(0x80)
        this.sendSTCommand(Registers.RSTATE_REQ)
        this.rState = ReleaseState.PI_ID_REQ

        // Reset counters for public interrogate
        Registers.RR_CTR.setValue(0)
        Registers.RR_MISS.setValue(0)
        this.sendSTCommand(Registers.RR_CTR)
        this.sendSTCommand(Registers.RR_MISS)
    }

    // NOISE TEST OPERATIONS
    fun popupNT() {
        this.logHistory("------ popupNT (Noise Test) ------\r\n")
        require(this.rState == ReleaseState.IDLE_ACK) {
            "Must be in IDLE_ACK state to perform noise test. Current state: ${this.rState}"
        }

        Registers.RSTATE_REQ.setValue(0x90)
        this.sendSTCommand(Registers.RSTATE_REQ)
        this.rState = ReleaseState.NT_REQ

        // Reset counters for noise test
        Registers.RR_CTR.setValue(0)
        Registers.RR_MISS.setValue(0)
        this.sendSTCommand(Registers.RR_CTR)
        this.sendSTCommand(Registers.RR_MISS)
    }

    // REBOOT OPERATIONS
    fun popupRB() {
        this.logHistory("------ popupRB (Reboot) ------\r\n")
        require(this.rState == ReleaseState.IDLE_ACK) {
            "Must be in IDLE_ACK state to reboot. Current state: ${this.rState}"
        }

        Registers.RSTATE_REQ.setValue(0x64)
        this.sendSTCommand(Registers.RSTATE_REQ)
        this.rState = ReleaseState.RB_OK
    }

    // STATE CHANGE HANDLER - This handles all state transitions based on UDB responses
    fun handleRStateRPTValueChange() {
        var rptValue = Registers.RSTATE_RPT.getValue()

        Log.d("RCRIEmulator", "handleRStateRPTValueChange: $rptValue")
        Log.d("RCRIEmulator", "Current rState: ${this.rState}")

        when (this.rState) {
            // IDLE STATE TRANSITIONS
            ReleaseState.IDLE_REQ -> {
                if (rptValue == 0x1) {
                    this.rState = ReleaseState.IDLE_ACK
                }
            }

            // INITIALIZATION STATE TRANSITIONS
            ReleaseState.INIT_REQ -> {
                if (rptValue == 0x11) {
                    this.rState = ReleaseState.INIT_PENDING
                }
            }

            ReleaseState.INIT_PENDING -> {
                when (rptValue) {
                    0x12 -> this.rState = ReleaseState.INIT_OK
                    0x13 -> this.rState = ReleaseState.INIT_FAIL
                }
            }

            // CONNECTION STATE TRANSITIONS
            ReleaseState.CON_REQ -> {
                if (rptValue == 0x21) {
                    this.rState = ReleaseState.CON_ID1
                }
            }

            ReleaseState.CON_ID1 -> {
                when (rptValue) {
                    0x22 -> this.rState = ReleaseState.CON_ID2
                    0x23 -> this.rState = ReleaseState.CON_OK
                }
            }

            ReleaseState.CON_ID2 -> {
                if (rptValue == 0x23) {
                    this.rState = ReleaseState.CON_OK
                }
            }

            // SINGLE RANGING STATE TRANSITIONS
            ReleaseState.RNG_SINGLE_REQ -> {
                if (rptValue == 0x31) {
                    this.rState = ReleaseState.RNG_SINGLE_PENDING
                }
            }

            ReleaseState.RNG_SINGLE_PENDING -> {
                when (rptValue) {
                    0x32 -> {
                        this.rState = ReleaseState.RNG_SINGLE_OK
                        onDetectionAlert?.invoke()
                    }
                    0x33 -> {
                        this.rState = ReleaseState.RNG_SINGLE_FAIL
                        val missCount = Registers.RR_MISS.getValue() as? Int ?: 0
                        onRetryCountChanged?.invoke(missCount)
                    }
                }
            }

            // CONTINUOUS RANGING STATE TRANSITIONS
            ReleaseState.RNG_CONT_REQ -> {
                if (rptValue == 0x41) {
                    this.rState = ReleaseState.RNG_CONT_PENDING
                }
            }

            ReleaseState.RNG_CONT_PENDING -> {
                when (rptValue) {
                    0x42 -> {
                        this.rState = ReleaseState.RNG_CONT_OK
                        onDetectionAlert?.invoke()
                    }
                    0x43 -> {
                        this.rState = ReleaseState.RNG_CONT_FAIL
                        val missCount = Registers.RR_MISS.getValue() as? Int ?: 0
                        onRetryCountChanged?.invoke(missCount)
                    }
                }
            }

            // TRIGGER/ARM STATE TRANSITIONS
            ReleaseState.AT_REQ -> {
                if (rptValue == 0x51) {
                    this.rState = ReleaseState.AT_ARM_PENDING
                }
            }

            ReleaseState.AT_ARM_PENDING -> {
                when (rptValue) {
                    0x52 -> this.rState = ReleaseState.AT_ARM_OK
                    0x53 -> {
                        this.rState = ReleaseState.AT_ARM_FAIL
                        val missCount = Registers.RR_MISS.getValue() as? Int ?: 0
                        onRetryCountChanged?.invoke(missCount)
                    }
                }
            }

            ReleaseState.AT_ARM_OK -> {
                if (rptValue == 0x54) {
                    this.rState = ReleaseState.AT_TRG_PENDING
                }
            }

            ReleaseState.AT_TRG_PENDING -> {
                when (rptValue) {
                    0x55 -> {
                        this.rState = ReleaseState.AT_TRG_OK
                        onReleaseAlert?.invoke()
                    }
                    0x56 -> {
                        this.rState = ReleaseState.AT_TRG_FAIL
                        val missCount = Registers.RR_MISS.getValue() as? Int ?: 0
                        onRetryCountChanged?.invoke(missCount)
                    }
                }
            }

            // BROADCAST STATE TRANSITIONS
            ReleaseState.BCR_REQ -> {
                if (rptValue == 0x61) {
                    this.rState = ReleaseState.BCR_PENDING
                }
            }

            ReleaseState.BCR_PENDING -> {
                if (rptValue == 0x62) {
                    this.rState = ReleaseState.BCR_OK
                }
            }

            // PUBLIC QUICK ID STATE TRANSITIONS
            ReleaseState.PI_QID_REQ -> {
                if (rptValue == 0x71) {
                    this.rState = ReleaseState.PI_QID_PENDING
                }
            }

            ReleaseState.PI_QID_PENDING -> {
                when (rptValue) {
                    0x72 -> {
                        this.rState = ReleaseState.PI_QID_DETECT
                        onDetectionAlert?.invoke()
                    }
                    0x73 -> {
                        this.rState = ReleaseState.PI_QID_NODETECT
                        val missCount = Registers.RR_MISS.getValue() as? Int ?: 0
                        onRetryCountChanged?.invoke(missCount)
                    }
                }
            }

            // PUBLIC FULL ID STATE TRANSITIONS
            ReleaseState.PI_ID_REQ -> {
                if (rptValue == 0x81) {
                    this.rState = ReleaseState.PI_ID_PENDING
                }
            }

            ReleaseState.PI_ID_PENDING -> {
                when (rptValue) {
                    0x82 -> {
                        this.rState = ReleaseState.PI_ID_DETECT
                        onDetectionAlert?.invoke()
                    }
                    0x83 -> {
                        this.rState = ReleaseState.PI_ID_NODETECT
                        val missCount = Registers.RR_MISS.getValue() as? Int ?: 0
                        onRetryCountChanged?.invoke(missCount)
                    }
                }
            }

            // NOISE TEST STATE TRANSITIONS
            ReleaseState.NT_REQ -> {
                if (rptValue == 0x91) {
                    this.rState = ReleaseState.NT_PENDING
                }
            }

            ReleaseState.NT_PENDING -> {
                if (rptValue == 0x92) {
                    this.rState = ReleaseState.NT_OK
                }
            }

            // REBOOT STATE TRANSITIONS
            ReleaseState.RB_OK -> {
                if (rptValue == 0x65) {
                    this.rState = ReleaseState.RB_ACK
                }
            }

            else -> {
                Log.w("RCRIEmulator", "Unhandled state transition from ${this.rState} with rptValue: $rptValue")
            }
        }

        Log.d("RCRIEmulator", "New rState: ${this.rState}")
    }

    // HELPER/UTILITY METHODS
    fun getReleaseState(): ReleaseState {
        return this.rState
    }

    fun getCurrentRetryCount(): Int {
        return Registers.RR_MISS.getValue() as? Int ?: 0
    }

    fun getCurrentRange(): Int {
        return Registers.RRR_VAL.getValue() as? Int ?: 0
    }

    fun isReadyForRanging(): Boolean {
        return this.rState == ReleaseState.CON_OK ||
                this.rState == ReleaseState.RNG_SINGLE_OK ||
                this.rState == ReleaseState.RNG_CONT_OK
    }

    fun isReadyForTrigger(): Boolean {
        return this.rState == ReleaseState.CON_OK ||
                this.rState == ReleaseState.RNG_SINGLE_OK ||
                this.rState == ReleaseState.RNG_CONT_OK
    }

    fun isReadyForPublicInterrogate(): Boolean {
        return this.rState == ReleaseState.INIT_OK
    }

    fun isReadyForBroadcast(): Boolean {
        return this.rState == ReleaseState.INIT_OK
    }

    fun isReadyForNoiseTest(): Boolean {
        return this.rState == ReleaseState.IDLE_ACK
    }

    fun isReadyForReboot(): Boolean {
        return this.rState == ReleaseState.IDLE_ACK
    }

    // FIRMWARE UPDATE FUNCTIONALITY
    fun updateFirmwareFromUrl(firmwareUrl: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(firmwareUrl)
                val connection = url.openConnection()
                val inputStream = connection.getInputStream()

                uploadFirmwareFromStream(inputStream)

            } catch (e: Exception) {
                Log.e("RCRIEmulator", "Failed to download firmware: ${e.message}")
            }
        }
    }

    suspend fun uploadFirmwareFromStream(inputStream: InputStream, onProgress: ((Int) -> Unit)? = null) {
        val reader = BufferedReader(InputStreamReader(inputStream))
        val lines = reader.readLines()

        Log.d("RCRIEmulator", "Firmware line count: ${lines.size}")

        withContext(Dispatchers.IO) {
            for ((index, rawLine) in lines.withIndex()) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue

                val success = sendFirmwareLine(line)
                if (!success) {
                    Log.e("RCRIEmulator", "Firmware upload failed at line $index: $line")
                    throw Exception("Firmware upload failed at line $index: $line")
                }

                // Calculate and report progress
                val progress = ((index + 1) * 100) / lines.size
                onProgress?.invoke(progress)

                delay(100) // pacing delay
            }

            // Send reboot after firmware upload
            sendCommand(
                DSSCommand.createRBCommand(getDestination(), getSource())
            )

            // Report 100% completion
            onProgress?.invoke(100)

            Log.d("RCRIEmulator", "Firmware upload complete, sent reboot.")
        }
    }

    private suspend fun sendFirmwareLine(line: String): Boolean {
        val maxRetries = 5
        var attempts = 0

        while (attempts < maxRetries) {
            val command = DSSCommand.createLDCommand(getDestination(), getSource(), listOf(line))
            val deferredAck = CompletableDeferred<Boolean>()

            setPendingAckCallback { response ->
                if (response.command == StandardResponse.OK.toString() || response.command == "ACK") {
                    deferredAck.complete(true)
                } else {
                    deferredAck.complete(false)
                }
            }

            sendCommand(command)

            val result = withTimeoutOrNull(3000) { deferredAck.await() } ?: false
            if (result) return true

            Log.w("RCRIEmulator", "Retrying firmware line: $line (attempt ${attempts + 1})")
            attempts++
        }

        return false
    }

    // VALIDATION HELPERS
    private fun validateState(expectedStates: List<ReleaseState>, operation: String) {
        if (this.rState !in expectedStates) {
            throw IllegalStateException("Cannot perform $operation in current state: ${this.rState}. Expected one of: $expectedStates")
        }
    }

    // DEBUGGING/LOGGING HELPERS
    fun getDetailedStateInfo(): String {
        return """
            Current State: ${this.rState}
            RSTATE_REQ: ${Registers.RSTATE_REQ.getValue()}
            RSTATE_RPT: ${Registers.RSTATE_RPT.getValue()}
            RR_CTR: ${Registers.RR_CTR.getValue()}
            RR_MISS: ${Registers.RR_MISS.getValue()}
            RRR_VAL: ${Registers.RRR_VAL.getValue()}
            Ready for Ranging: ${isReadyForRanging()}
            Ready for Trigger: ${isReadyForTrigger()}
            Ready for Public Interrogate: ${isReadyForPublicInterrogate()}
        """.trimIndent()
    }
}