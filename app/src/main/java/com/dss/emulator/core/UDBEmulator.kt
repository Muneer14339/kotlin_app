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


    private val blePeripheralController: BLEPeripheralController;
    private val firmwareLines: MutableList<String> = mutableListOf()
    private val appContext: Context

    constructor(
        context: Context, blePeripheralController: BLEPeripheralController
    ) : super(context) {
        this.blePeripheralController = blePeripheralController
        this.appContext = context.applicationContext
        this.setSource("UDB")
        this.setDestination("RC-RI")

        // add random firmware lines
//        for (i in 0..100) {
//            firmwareLines.add("Line $i: ${Random.nextInt(1000)}")
//        }
    }

    fun getFirmwareLines(): List<String> {
        return firmwareLines
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


    // Function to parse the GET (GT) command
    private fun parseGTCommand(command: DSSCommand) {
        require(command.command == StandardRequest.GT.toString()) { "Invalid command: Expected GET (GT) command" }
        require(command.data.size == 1) { "Invalid data size: Expected exactly one data entry" }

        val registerName = command.data[0]
        val register = registerMap[registerName]
            ?: throw IllegalArgumentException("Invalid register: '$registerName' not found in register map")
        val registerValue = register.getValue().toString()

        Log.d("UDBEmulator", "parseRegisterGTCom: $registerName $registerValue")

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

    // Function to parse the SET (ST) command
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

    // Function to parse the GET ID (GI) command
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

    // Function to parse the SET ID (SI) command
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

    // Function to parse the SET Protected Register (SP) command
    private fun parseSPCommand(command: DSSCommand) {
        require(command.command == StandardRequest.SP.toString()) { "Invalid command: Expected SET Protected Register (SP) command" }
        require(command.data.size == 3) { "Invalid data size: Expected exactly three data entries" }
        val password = command.data[0]
        val registerName = command.data[1]
        val registerValue = command.data[2]

        Log.d(
            "UDBEmulator", "parseSPCommand: $password $registerName $registerValue"
        )

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
        }
        this.sendCommand(
            DSSCommand.createNOResponse(
                this.getDestination(), this.getSource(), command.commandId
            )
        )
    }

    // Function to parse the Factory Test (FT) command
    private fun parseFTCommand(command: DSSCommand) {
        require(command.command == StandardRequest.FT.toString()) { "Invalid command: Expected Factory Test (FT) command" }
        // Implement the factory test initiation logic here
        // For demonstration, we'll return an OK response

        Log.d("UDBEmulator", "parseFactoryTestCommand")
    }

    // Function to parse the firmware load (LD) command
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

    // Function to parse the Reboot (RB) command
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

//        firmwareLines.clear()

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

        Thread.sleep(50)
        Registers.REG_MAP.setValue(0L)
    }

    private fun dispatchRegisterChange(register: Register) {
        var regMap = Registers.REG_MAP.getValue() as Long
        regMap = regMap or (1L shl register.regMapBit)
        Registers.REG_MAP.setValue(regMap)
    }

    private fun handleRStateREQValueChange() {
        var rStateREQ = Registers.RSTATE_REQ.getValue()

        Log.d("UDBEmulator", "handleRStateREQValueChange: $rStateREQ")

        when (rStateREQ) {
            0x00 -> { // IDLE_REQ
                Log.d("UDBEmulator", "handleRStateREQValueChange: IDLE_REQ")
                Registers.RSTATE_MAP.setValue(1 shl 0)
                dispatchRegisterChange(Registers.MODEL)
                dispatchRegisterChange(Registers.SN)
                dispatchRegisterChange(Registers.FIRMWARE)
                sendRMCommand()


                Thread.sleep(1000)
                Registers.RSTATE_RPT.setValue(0x1) // IDLE_ACK
                dispatchRegisterChange(Registers.RSTATE_RPT)
                sendRMCommand()
            }

            0x10 -> { // INIT_REQ
                Log.d("UDBEmulator", "handleRStateREQValueChange: INIT_REQ")
                Registers.RSTATE_MAP.setValue(1 shl 1)
                Registers.RSTATE_RPT.setValue(0x11) // INIT_PENDING
                dispatchRegisterChange(Registers.RSTATE_RPT)
                sendRMCommand()

                Thread.sleep(1000)
                Registers.RR1_LGD.setValue("RR1_LGD")
                Registers.RR2_LGD.setValue("RR2_LGD")
                Registers.RR3_LGD.setValue("RR3_LGD")
                Registers.RR4_LGD.setValue("RR4_LGD")

                // Simulate initialization process
                dispatchRegisterChange(Registers.RSTATE_RPT)
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
                sendRMCommand()

                Thread.sleep(2000)
                Registers.RSTATE_RPT.setValue(0x12) // INIT_PENDING
                dispatchRegisterChange(Registers.RSTATE_RPT)
                sendRMCommand()
            }

            0x20 -> { // CON_REQ
                Log.d("UDBEmulator", "handleRStateREQValueChange: CON_REQ")
                Registers.RSTATE_MAP.setValue(1 shl 2)
                Registers.RSTATE_RPT.setValue(0x21) // CON_ID1
                dispatchRegisterChange(Registers.RSTATE_RPT)
                sendRMCommand()


                Thread.sleep(2000)
                // Simulate connection process
                Registers.RSTATE_RPT.setValue(0x22) // CON_ID2
                dispatchRegisterChange(Registers.RSTATE_RPT)
                sendRMCommand()


                Thread.sleep(2000)
                Registers.RSTATE_RPT.setValue(0x23) // CON_OK
                dispatchRegisterChange(Registers.RSTATE_RPT)
                sendRMCommand()
            }

            0x30 -> { // RNG_SINGLE_REQ
                Log.d("UDBEmulator", "handleRStateREQValueChange: RNG_SINGLE_REQ")
                Registers.RSTATE_MAP.setValue(1 shl 3)
                Registers.RSTATE_RPT.setValue(0x31) // RNG_SINGLE_PENDING
                dispatchRegisterChange(Registers.RSTATE_RPT)
                sendRMCommand()

                // Simulate ranging process
                Registers.RSTATE_RPT.setValue(0x32) // RNG_SINGLE_OK
//                dispatchRegisterChange(Registers.RRR_VAL)
//                dispatchRegisterChange(Registers.RRx_VAL)
                dispatchRegisterChange(Registers.RR_CTR)
                dispatchRegisterChange(Registers.RSTATE_RPT)
                sendRMCommand()
            }

            0x40 -> { // RNG_CONT_REQ
                Log.d("UDBEmulator", "handleRStateREQValueChange: RNG_CONT_REQ")
                Registers.RSTATE_MAP.setValue(1 shl 4)
                Registers.RSTATE_RPT.setValue(0x41) // RNG_CONT_PENDING
                dispatchRegisterChange(Registers.RSTATE_RPT)
                sendRMCommand()

                // Simulate continuous ranging process
                Registers.RSTATE_RPT.setValue(0x42) // RNG_CONT_OK
                dispatchRegisterChange(Registers.RRR_VAL)
//                dispatchRegisterChange(Registers.RRx_VAL)
                dispatchRegisterChange(Registers.RR_CTR)
                dispatchRegisterChange(Registers.RSTATE_RPT)
                sendRMCommand()
            }

            0x50 -> { // AT_REQ
                Log.d("UDBEmulator", "handleRStateREQValueChange: AT_REQ")
                Registers.RSTATE_MAP.setValue(1 shl 5)
                Registers.RSTATE_RPT.setValue(0x51) // AT_ARM_PENDING
                dispatchRegisterChange(Registers.RSTATE_RPT)
                sendRMCommand()

                // Simulate arm process
                Registers.RSTATE_RPT.setValue(0x52) // AT_ARM_OK
                dispatchRegisterChange(Registers.RSTATE_RPT)
                sendRMCommand()

                // Simulate trigger process
                Registers.RSTATE_RPT.setValue(0x54) // AT_TRG_PENDING
                dispatchRegisterChange(Registers.RSTATE_RPT)
                sendRMCommand()

                Registers.RSTATE_RPT.setValue(0x55) // AT_TRG_OK
                dispatchRegisterChange(Registers.RSTATE_RPT)
                sendRMCommand()
            }

            0x60 -> { // BCR_REQ
                Log.d("UDBEmulator", "handleRStateREQValueChange: BCR_REQ")
                Registers.RSTATE_MAP.setValue(1 shl 6)
                Registers.RSTATE_RPT.setValue(0x61) // BCR_PENDING
                dispatchRegisterChange(Registers.RSTATE_RPT)
                sendRMCommand()

                Registers.RSTATE_RPT.setValue(0x62) // BCR_OK
                dispatchRegisterChange(Registers.RR_CTR)
                dispatchRegisterChange(Registers.RSTATE_RPT)
                sendRMCommand()
            }

            0x70 -> { // PI_QID_REQ
                Log.d("UDBEmulator", "handleRStateREQValueChange: PI_QID_REQ")
                Registers.RSTATE_MAP.setValue(1 shl 7)
                Registers.RSTATE_RPT.setValue(0x71) // PI_QID_PENDING
                dispatchRegisterChange(Registers.RSTATE_RPT)
                sendRMCommand()

                // Simulate QID detection
                Registers.RSTATE_RPT.setValue(0x72) // PI_QID_DETECT
                dispatchRegisterChange(Registers.RR_CTR)
                dispatchRegisterChange(Registers.RRR_VAL)
                dispatchRegisterChange(Registers.PUBLIC_QID)
                dispatchRegisterChange(Registers.RSTATE_RPT)
                sendRMCommand()
            }

            0x80 -> { // PI_ID_REQ
                Log.d("UDBEmulator", "handleRStateREQValueChange: PI_ID_REQ")
                Registers.RSTATE_MAP.setValue(1 shl 8)
                Registers.RSTATE_RPT.setValue(0x81) // PI_ID_PENDING
                dispatchRegisterChange(Registers.RSTATE_RPT)
                sendRMCommand()

                // Simulate ID detection
                Registers.RSTATE_RPT.setValue(0x82) // PI_ID_DETECT
                dispatchRegisterChange(Registers.RR_CTR)
                dispatchRegisterChange(Registers.RRR_VAL)
                dispatchRegisterChange(Registers.PUBLIC_ID)
                dispatchRegisterChange(Registers.RSTATE_RPT)
                sendRMCommand()
            }

            0x90 -> { // NT_REQ
                Log.d("UDBEmulator", "handleRStateREQValueChange: NT_REQ")
                Registers.RSTATE_MAP.setValue(1 shl 9)
                Registers.RSTATE_RPT.setValue(0x91) // NT_PENDING
                dispatchRegisterChange(Registers.RSTATE_RPT)
                sendRMCommand()

                Registers.RSTATE_RPT.setValue(0x92) // NT_OK
                dispatchRegisterChange(Registers.RR_CTR)
                dispatchRegisterChange(Registers.AR_NOISE_DB)
                dispatchRegisterChange(Registers.RSTATE_RPT)
                sendRMCommand()
            }

            0x64 -> { // RB_REQ
                Log.d("UDBEmulator", "handleRStateREQValueChange: RB_REQ")
                Registers.RSTATE_MAP.setValue(1 shl 10)
                Registers.RSTATE_RPT.setValue(0x65) // RB_ACK
                dispatchRegisterChange(Registers.RSTATE_RPT)
                sendRMCommand()
            }
        }
    }
}
