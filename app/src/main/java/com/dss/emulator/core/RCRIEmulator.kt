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

    private val bleCentralController: BLECentralController;
    private var pendingAckCallback: ((DSSCommand) -> Unit)? = null;

    constructor(context: Context, bleCentralController: BLECentralController) : super(context) {
        this.bleCentralController = bleCentralController
        this.setSource("RC-RI")
        this.setDestination("UDB")
    }

    fun setPendingAckCallback(callback: (DSSCommand) -> Unit) {
        pendingAckCallback = callback
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
    }


    // Function to parse the Register Map change report (RM) command
    private fun parseRMCommand(command: DSSCommand) {
        require(command.command == StandardRequest.RM.toString()) { "Invalid command: Expected Register Map Change Report (RM) command" }
        require(command.data.size == 1) { "Invalid data size: Expected one data entry here" }

        Log.d("UDBEmulator", "parseRMCommand")

        val registerMapBit = command.data[0].toLong()

        for (register in registerList) {
            if ((1L shl register.regMapBit) and registerMapBit != 0L) {
                Log.d("UDBEmulator", "parseRMCommand: ${register.name}")

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
        }

    private fun updateRState(newRState: ReleaseState) {
        this.rState = newRState
    }

    fun popupIdle() {
        this.logHistory("------ popupIdle ------\r\n")

        Registers.RSTATE_REQ.setValue(0)
        this.sendSTCommand(Registers.RSTATE_REQ)
        this.rState = ReleaseState.IDLE_REQ
    }

    fun popupInit() {
        this.logHistory("------ popupInit ------\r\n")

        require(this.rState == ReleaseState.IDLE_ACK)

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
        require(this.rState == ReleaseState.INIT_OK)

        this.rState = ReleaseState.CON_REQ
        Registers.RSTATE_REQ.setValue(0x20)
        this.sendSTCommand(Registers.RSTATE_REQ)
    }

    fun popupSrange() {
        this.logHistory("------ popupSrange ------\r\n")
        require(this.rState == ReleaseState.CON_OK || this.rState == ReleaseState.RNG_SINGLE_OK || this.rState == ReleaseState.RNG_CONT_OK)

        this.rState = ReleaseState.RNG_SINGLE_REQ

        Registers.RSTATE_REQ.setValue(0x30)
        this.sendSTCommand(Registers.RSTATE_REQ)
    }

    fun popupCrange() {
        this.logHistory("------ popupCrange ------\r\n")
        require(this.rState == ReleaseState.CON_OK || this.rState == ReleaseState.RNG_SINGLE_OK || this.rState == ReleaseState.RNG_CONT_OK)

        Registers.RSTATE_REQ.setValue(0x40)
        this.sendSTCommand(Registers.RSTATE_REQ)
        this.rState = ReleaseState.RNG_CONT_REQ
    }

    fun popupTrigger() {
        this.logHistory("------ popupTrigger ------\r\n")
        require(this.rState == ReleaseState.CON_OK || this.rState == ReleaseState.RNG_SINGLE_OK || this.rState == ReleaseState.RNG_CONT_OK)

        Registers.RSTATE_REQ.setValue(0x50)
        this.sendSTCommand(Registers.RSTATE_REQ)
        this.rState = ReleaseState.AT_REQ
    }

    fun popupBroadcast() {
        this.logHistory("------ popupBroadcast ------\r\n")
        require(this.rState == ReleaseState.INIT_OK)

        Registers.RSTATE_REQ.setValue(0x60)
        this.sendSTCommand(Registers.RSTATE_REQ)
        this.rState = ReleaseState.BCR_REQ
    }

    fun popupPIQID() {
        this.logHistory("------ popupPIQID ------\r\n")
        require(this.rState == ReleaseState.INIT_OK)

        Registers.RSTATE_REQ.setValue(0x70)
        this.sendSTCommand(Registers.RSTATE_REQ)
        this.rState = ReleaseState.PI_QID_REQ
    }

    fun popupPIID() {
        this.logHistory("------ popupPIID ------\r\n")
        require(this.rState == ReleaseState.INIT_OK)

        Registers.RSTATE_REQ.setValue(0x80)
        this.sendSTCommand(Registers.RSTATE_REQ)
        this.rState = ReleaseState.PI_ID_REQ
    }

    fun popupNT() {
        this.logHistory("------ popupNT ------\r\n")
        require(this.rState == ReleaseState.IDLE_ACK)

        Registers.RSTATE_REQ.setValue(0x90)
        this.sendSTCommand(Registers.RSTATE_REQ)
        this.rState = ReleaseState.NT_REQ
    }

    fun popupRB() {
        this.logHistory("------ popupRB ------\r\n")
        require(this.rState == ReleaseState.IDLE_ACK)

        Registers.RSTATE_REQ.setValue(0x64)
        this.sendSTCommand(Registers.RSTATE_REQ)
        this.rState = ReleaseState.RB_OK
    }

    fun handleRStateRPTValueChange() {
        var rptValue = Registers.RSTATE_RPT.getValue()

        Log.d("RCRIEmulator", "handleRStateRPTValueChange: $rptValue")
        Log.d("RCRIEmulator", "rState: ${this.rState.toString()}")

        when (this.rState) {
            ReleaseState.IDLE_REQ -> {
                if (rptValue == 0x1) {
                    this.rState = ReleaseState.IDLE_ACK
                }
            }

            ReleaseState.INIT_REQ -> {
                if (rptValue == 0x11) {
                    this.rState = ReleaseState.INIT_PENDING
                }
            }

            ReleaseState.INIT_PENDING -> {
                if (rptValue == 0x12) {
                    this.rState = ReleaseState.INIT_OK
                } else if (rptValue == 0x13) {
                    this.rState = ReleaseState.INIT_FAIL
                }
            }

            ReleaseState.CON_REQ -> {
                if (rptValue == 0x21) {
                    this.rState = ReleaseState.CON_ID1
                }
            }

            ReleaseState.CON_ID1 -> {
                if (rptValue == 0x22) {
                    this.rState = ReleaseState.CON_ID2
                } else if (rptValue == 0x23) {
                    this.rState = ReleaseState.CON_OK
                }
            }

            ReleaseState.CON_ID2 -> {
                if (rptValue == 0x23) {
                    this.rState = ReleaseState.CON_OK
                }
            }

            ReleaseState.RNG_SINGLE_REQ -> {
                if (rptValue == 0x31) {
                    this.rState = ReleaseState.RNG_SINGLE_PENDING
                }
            }

            ReleaseState.RNG_SINGLE_PENDING -> {
                if (rptValue == 0x32) {
                    this.rState = ReleaseState.RNG_SINGLE_OK
                } else if (rptValue == 0x33) {
                    this.rState = ReleaseState.RNG_SINGLE_FAIL
                }
            }

            ReleaseState.RNG_CONT_REQ -> {
                if (rptValue == 0x41) {
                    this.rState = ReleaseState.RNG_CONT_PENDING
                }
            }

            ReleaseState.RNG_CONT_PENDING -> {
                if (rptValue == 0x42) {
                    this.rState = ReleaseState.RNG_CONT_OK
                } else if (rptValue == 0x43) {
                    this.rState = ReleaseState.RNG_CONT_FAIL
                }
            }

            ReleaseState.AT_REQ -> {
                if (rptValue == 0x51) {
                    this.rState = ReleaseState.AT_ARM_PENDING
                }
            }

            ReleaseState.AT_ARM_PENDING -> {
                if (rptValue == 0x52) {
                    this.rState = ReleaseState.AT_ARM_OK
                } else if (rptValue == 0x53) {
                    this.rState = ReleaseState.AT_ARM_FAIL
                }
            }

            ReleaseState.AT_ARM_OK -> {
                if (rptValue == 0x54) {
                    this.rState = ReleaseState.AT_TRG_PENDING
                }
            }

            ReleaseState.AT_TRG_PENDING -> {
                if (rptValue == 0x55) {
                    this.rState = ReleaseState.AT_TRG_OK
                } else if (rptValue == 0x56) {
                    this.rState = ReleaseState.AT_TRG_FAIL
                }
            }

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

            ReleaseState.PI_QID_REQ -> {
                if (rptValue == 0x71) {
                    this.rState = ReleaseState.PI_QID_PENDING
                }
            }

            ReleaseState.PI_QID_PENDING -> {
                if (rptValue == 0x72) {
                    this.rState = ReleaseState.PI_QID_DETECT
                } else if (rptValue == 0x73) {
                    this.rState = ReleaseState.PI_QID_NODETECT
                }
            }

            ReleaseState.PI_ID_REQ -> {
                if (rptValue == 0x81) {
                    this.rState = ReleaseState.PI_ID_PENDING
                }
            }

            ReleaseState.PI_ID_PENDING -> {
                if (rptValue == 0x82) {
                    this.rState = ReleaseState.PI_ID_DETECT
                } else if (rptValue == 0x83) {
                    this.rState = ReleaseState.PI_ID_NODETECT
                }
            }

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

            ReleaseState.RB_OK -> {
                if (rptValue == 0x65) {
                    this.rState = ReleaseState.RB_ACK
                }
            }

            else -> {}
        }

        Log.d("RCRIEmulator", "new rState: ${this.rState.toString()}")
    }

    fun getReleaseState(): ReleaseState {
        return this.rState
    }

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

            // Send reboot
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
}
