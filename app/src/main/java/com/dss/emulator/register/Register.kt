package com.dss.emulator.register

import com.dss.emulator.dsscommand.DSSCommand
import com.dss.emulator.dsscommand.StandardRequest

enum class Direction(private val description: String) {
    UDB_TO_GUI("UDB->GUI"),
    GUI_TO_UDB("GUI->UDB"),
    BOTH("Both");

    override fun toString(): String = description
}

// Enum class
enum class DataType(private val description: String) {
    BITMAP_64("64-bit Bitmap"),
    UINT32("32-bit Unsigned Int"),
    SIGNED_INT32("32-bit Signed Int"),
    STRING("Zero Terminated String");
    override fun toString(): String = description
}

// Register class with union-like behavior
data class Register(
    val name: String,
    val dataType: DataType,
    val regMapBit: Int,
    val description: String,
    val direction: Direction
) {
    private var intValue: Int? = null
    private var longValue: Long? = null
    private var stringValue: String = ""

    init {
        when (dataType) {
            DataType.SIGNED_INT32, DataType.UINT32 -> intValue = 0
            DataType.BITMAP_64 -> longValue = 0L
            DataType.STRING -> stringValue = ""
        }
    }

    fun setValueString(value: String) {
        when (dataType) {
            DataType.SIGNED_INT32, DataType.UINT32 -> intValue = value.toIntOrNull()
            DataType.BITMAP_64 -> longValue = value.toLongOrNull()
            DataType.STRING -> stringValue = value
        }
    }

    fun getValue(): Any? {
        return when (dataType) {
            DataType.SIGNED_INT32, DataType.UINT32 -> intValue
            DataType.BITMAP_64 -> longValue
            DataType.STRING -> stringValue
        }
    }

    fun getValueString(): String? {
        return when(dataType) {
            DataType.SIGNED_INT32, DataType.UINT32 -> "0x" + intValue?.toString(16)
            DataType.BITMAP_64 -> "0x" + longValue?.toString(16)
            DataType.STRING -> stringValue
        }
    }

    fun setValue(value: Any) {
        when (dataType) {
            DataType.SIGNED_INT32, DataType.UINT32 -> {
                if (value is Int) {
                    intValue = value
                } else {
                    throw IllegalArgumentException("Expected an Int for $dataType, got ${value::class.simpleName}")
                }
            }

            DataType.BITMAP_64 -> {
                if (value is Long) {
                    longValue = value
                } else {
                    throw IllegalArgumentException("Expected a Long for $dataType, got ${value::class.simpleName}")
                }
            }

            DataType.STRING -> {
                if (value is String) {
                    stringValue = value
                } else {
                    throw IllegalArgumentException("Expected a String for $dataType, got ${value::class.simpleName}")
                }
            }
        }
    }
}

object Registers {
    val REG_MAP = Register(
        name = "REG_MAP",
        dataType = DataType.BITMAP_64,
        regMapBit = 0,
        description = """
            Bitmap indicates updated UDB registers. When the GUI reads this via a GT command, any pending '1' bits are automatically cleared. 
            Individual bits are also cleared when a GT command is issued for the corresponding register. 
            See REG_MAP bitmap definition in column C.
        """.trimIndent(),
        direction = Direction.UDB_TO_GUI
    )

    val MODEL = Register(
        name = "MODEL",
        dataType = DataType.UINT32,
        regMapBit = 1,
        description = "Deck box model number",
        direction = Direction.UDB_TO_GUI
    )

    val SN = Register(
        name = "SN",
        dataType = DataType.UINT32,
        regMapBit = 2,
        description = "Deck box serial number",
        direction = Direction.UDB_TO_GUI
    )

    val FIRMWARE = Register(
        name = "FIRMWARE",
        dataType = DataType.UINT32,
        regMapBit = 3,
        description = "Installed deck box firmware version. For example 123 is V1.23.",
        direction = Direction.UDB_TO_GUI
    )

    val SELFTEST = Register(
        name = "SELFTEST",
        dataType = DataType.UINT32,
        regMapBit = 4,
        description = """
            UDB selftest report. A selftest is conducted during the initialization (INIT_REQ).
            Test results are indicated in the bitmap. A '1' bit indicates a failed test and results in an INIT_NAK instead of INIT_ACK.
            0x0001: UDB Controller fail
            0x0002: UDB RX fail
            0x0004: UDB TX fail
            0x0008: reserved
            0x0010: UDB TDCR1 (LF) fail
            0x0020: UDB TDCR2 (MF) fail
            0x0040: UDB TDCR3 (HF) fail
            0x0080: UDB TDCR4 (reserved) fail
        """.trimIndent(),
        direction = Direction.UDB_TO_GUI
    )

    val SOUNDSPEED = Register(
        name = "SOUNDSPEED",
        dataType = DataType.UINT32,
        regMapBit = 5,
        description = "Computed sound speed (m/s)",
        direction = Direction.GUI_TO_UDB
    )

    val RANGE_MAX = Register(
        name = "RANGE_MAX",
        dataType = DataType.UINT32,
        regMapBit = 6,
        description = "Maximum supported range (units 0.01 m).",
        direction = Direction.GUI_TO_UDB
    )

    val AR_MFG = Register(
        name = "AR_MFG",
        dataType = DataType.STRING,
        regMapBit = 7,
        description = """
            Abbreviations for supported manufacturers:
            Ashored: ASH
            Desert Star: DSS
            Edgetech: EDG
            FioBuoy: FIO
            Ropeless Systems: ROP
            Subsea Sonics: SUB
        """.trimIndent(),
        direction = Direction.GUI_TO_UDB
    )

    val AR_MODEL = Register(
        name = "AR_MODEL",
        dataType = DataType.STRING,
        regMapBit = 8,
        description = """
            Abbreviations for supported manufacturer's models:
            For DSS: ARC1-38, ARC1-12, ARC2-38
            For SUB: AR4RT, DAR4RT
            For ASH:
            For EDG:
            For FIO:
            For ROP:
        """.trimIndent(),
        direction = Direction.GUI_TO_UDB
    )

    val AR_NOISE_DB = Register(
        name = "AR_NOISE_DB",
        dataType = DataType.UINT32,
        regMapBit = 9,
        description = "Observed peak acoustic noise level in dB re. 1 uPa. Set",
        direction = Direction.UDB_TO_GUI
    )

    val AR_THOLD_DB = Register(
        name = "AR_THOLD_DB",
        dataType = DataType.UINT32,
        regMapBit = 10,
        description = "Acoustic signal detection threshold in dB re. 1 uPa",
        direction = Direction.BOTH
    )

    val AR_ACP = Register(
        name = "AR_ACP",
        dataType = DataType.UINT32,
        regMapBit = 11,
        description = """
            Acoustic communication protocol and band identifier. The ID is specified by UDB based on the AR_MFG and AR_MODEL definition. 
            A zero value indicates that the manufacturer or model is not recognized and communication cannot be established.
            Format: 
            00-99: Frequency band designator.
                00: Simulation band. No acoustic transmissions. Instead, test values are returned. For software test purposes.
                01: Low Frequency (LF) band, 8-16 KHz nominal
                02: Medium Frequency band (MF): 16-24 kHz nominal
                03: High Frequency band (HF): 34-42 KHz nominal
                04-99: Not currently defined
            Acoustic communication protocol designators:
                100: ARC-1 individual and broadcast release protocol
                200: ARC-2 broadcast release only protocol
                300-900: Reserved for DSS protocols
                1000-1900: Reserved for Ashored protocols
                2000-2900: Reserved for Edgetech protocols
                3000-3900: Reserved for Fiomarine protocols
                4000-4900: Reserved for Ropeless Systems protocols
                5000-5900: Reserved for Subsea Sonics protocols
        """.trimIndent(),
        direction = Direction.UDB_TO_GUI
    )

    val PIN_ID = Register(
        name = "PIN_ID",
        dataType = DataType.UINT32,
        regMapBit = 12,
        description = "PIN ID for individual release trigger. Interpretation and use is manufacturer specific.",
        direction = Direction.GUI_TO_UDB
    )

    val GROUP_ID = Register(
        name = "GROUP_ID",
        dataType = DataType.UINT32,
        regMapBit = 13,
        description = """
            GROUP ID for group trigger of a set of releases via broadcast command. One or possibly several group IDs are typically assigned to each fisher. 
            Interpretation and use is manufacturer specific.
        """.trimIndent(),
        direction = Direction.GUI_TO_UDB
    )

    val PUBLIC_ID = Register(
        name = "PUBLIC_ID",
        dataType = DataType.UINT32,
        regMapBit = 14,
        description = """
            PUBLIC Gear Owner Unique Identifier. 
            Acoustically transmitted by a release in response to a public interrogate with full ID request.
        """.trimIndent(),
        direction = Direction.UDB_TO_GUI
    )

    val PUBLIC_QID = Register(
        name = "PUBLIC_QID",
        dataType = DataType.UINT32,
        regMapBit = 15,
        description = """
            Abbreviated Public 'Quick ID'. This is generally the lower 8-bits of the full public ID. 
            Used for efficient acoustic telemetry of the Public ID. ID is not necessarily unique. 
            Acoustically transmitted by a release in response to a public interrogate with Quick ID request.
        """.trimIndent(),
        direction = Direction.UDB_TO_GUI
    )

    val RSTATE_MAP = Register(
        name = "RSTATE_MAP",
        dataType = DataType.UINT32,
        regMapBit = 16,
        description = "32-bit bitmap of available release states for the selected ACOMM protocol. Indicates available release state requests for the selected ACOMM protocol. See RSTATE tab.",
        direction = Direction.UDB_TO_GUI
    )

    val RSTATE_REQ = Register(
        name = "RSTATE_REQ",
        dataType = DataType.UINT32,
        regMapBit = 17,
        description = """
            Release state request. GUI requested state. See RSTATE tab.
            1. GUI sets the new state request.
            2. UDB clears the state request AFTER setting RSTATE_RPT
            3. GUI can set a new state request as soon as RSTATE_REQ is cleared again.
            4. UDB will complete the current request action before accepting a new state request.
        """.trimIndent(),
        direction = Direction.BOTH
    )

    val RSTATE_RPT = Register(
        name = "RSTATE_RPT",
        dataType = DataType.UINT32,
        regMapBit = 18,
        description = "Release state report. Indicates the current state. See RSTATE tab.",
        direction = Direction.UDB_TO_GUI
    )

    val RR_CTR = Register(
        name = "RR_CTR",
        dataType = DataType.UINT32,
        regMapBit = 19,
        description = """
            Ranging and Reporting Interrogate Counter:
            1. Set to zero when a new state is requested.
            2. Increments when an acoustic interrogate has resulted in a reply. 
               Or when no reply is expected (broadcast interrogate)
        """.trimIndent(),
        direction = Direction.UDB_TO_GUI
    )

    val RR_MISS = Register(
        name = "RR_MISS",
        dataType = DataType.UINT32,
        regMapBit = 20,
        description = """
            Ranging and Reporting Interrogate No-Reply
            1. Set to zero when a new state is requested.
            2. Set to zero when an interrogate reply has been received.
            3. Increments when no reply has been received in response to an interrogate
        """.trimIndent(),
        direction = Direction.UDB_TO_GUI
    )

    val RR_MAP = Register(
        name = "RR_MAP",
        dataType = DataType.UINT32,
        regMapBit = 21,
        description = """
            32-bit bitmap of available status reports for the selected ACOMM protocol.
            Indicates available reports for the selected ACOMM protocol. See RR tab.
        """.trimIndent(),
        direction = Direction.UDB_TO_GUI
    )

    val RRR_VAL = Register(
        name = "RRR_VAL",
        dataType = DataType.SIGNED_INT32,
        regMapBit = 22,
        description = "Acoustic slant range to selected release. Units are 0.01m. The value always reflects the last successful ranging.",
        direction = Direction.UDB_TO_GUI
    )

    val RR1_VAL = Register(
        name = "RR1_VAL",
        dataType = DataType.SIGNED_INT32,
        regMapBit = 23,
        description = """
            Release status report #1: Value. This may be a voltage, temperature, depth, trap content etc.
        """.trimIndent(),
        direction = Direction.UDB_TO_GUI
    )

    val RR1_DIV = Register(
        name = "RR1_DIV",
        dataType = DataType.UINT32,
        regMapBit = 24,
        description = "Value divisor for display purposes",
        direction = Direction.UDB_TO_GUI
    )

    val RR1_LGD = Register(
        name = "RR1_LGD",
        dataType = DataType.STRING,
        regMapBit = 25,
        description = "Report legend, such as units of measure",
        direction = Direction.UDB_TO_GUI
    )

    val RR2_VAL = Register(
        name = "RR2_VAL",
        dataType = DataType.SIGNED_INT32,
        regMapBit = 26,
        description = """
            Release status report #2: Value. This may be a voltage, temperature, depth, trap content etc.
        """.trimIndent(),
        direction = Direction.UDB_TO_GUI
    )

    val RR2_DIV = Register(
        name = "RR2_DIV",
        dataType = DataType.UINT32,
        regMapBit = 27,
        description = "Value divisor for display purposes",
        direction = Direction.UDB_TO_GUI
    )

    val RR2_LGD = Register(
        name = "RR2_LGD",
        dataType = DataType.STRING,
        regMapBit = 28,
        description = "Report legend, such as units of measure",
        direction = Direction.UDB_TO_GUI
    )

    val RR3_VAL = Register(
        name = "RR3_VAL",
        dataType = DataType.SIGNED_INT32,
        regMapBit = 29,
        description = """
            Release status report #3: Value. This may be a voltage, temperature, depth, trap content etc.
        """.trimIndent(),
        direction = Direction.UDB_TO_GUI
    )

    val RR3_DIV = Register(
        name = "RR3_DIV",
        dataType = DataType.UINT32,
        regMapBit = 30,
        description = "Value divisor for display purposes",
        direction = Direction.UDB_TO_GUI
    )

    val RR3_LGD = Register(
        name = "RR3_LGD",
        dataType = DataType.STRING,
        regMapBit = 31,
        description = "Report legend, such as units of measure",
        direction = Direction.UDB_TO_GUI
    )

    val RR4_VAL = Register(
        name = "RR4_VAL",
        dataType = DataType.SIGNED_INT32,
        regMapBit = 32,
        description = """
            Release status report #4: Value. This may be a voltage, temperature, depth, trap content etc.
        """.trimIndent(),
        direction = Direction.UDB_TO_GUI
    )

    val RR4_DIV = Register(
        name = "RR4_DIV",
        dataType = DataType.UINT32,
        regMapBit = 33,
        description = "Value divisor for display purposes",
        direction = Direction.UDB_TO_GUI
    )

    val RR4_LGD = Register(
        name = "RR4_LGD",
        dataType = DataType.STRING,
        regMapBit = 34,
        description = "Report legend, such as units of measure",
        direction = Direction.UDB_TO_GUI
    )

    init {
        SN.setValue(0x00000000)
        FIRMWARE.setValue(0x1231)
        SOUNDSPEED.setValue(0x00000000)
    }
}

val registerList: List<Register> = listOf(
    Registers.REG_MAP,
    Registers.MODEL,
    Registers.SN,
    Registers.FIRMWARE,
    Registers.SELFTEST,
    Registers.SOUNDSPEED,
    Registers.RANGE_MAX,
    Registers.AR_MFG,
    Registers.AR_MODEL,
    Registers.AR_NOISE_DB,
    Registers.AR_THOLD_DB,
    Registers.AR_ACP,
    Registers.PIN_ID,
    Registers.GROUP_ID,
    Registers.PUBLIC_ID,
    Registers.PUBLIC_QID,
    Registers.RSTATE_MAP,
    Registers.RSTATE_REQ,
    Registers.RSTATE_RPT,
    Registers.RR_CTR,
    Registers.RR_MISS,
    Registers.RR_MAP,
    Registers.RRR_VAL,
    Registers.RR1_VAL,
    Registers.RR1_DIV,
    Registers.RR1_LGD,
    Registers.RR2_VAL,
    Registers.RR2_DIV,
    Registers.RR2_LGD,
    Registers.RR3_VAL,
    Registers.RR3_DIV,
    Registers.RR3_LGD,
    Registers.RR4_VAL,
    Registers.RR4_DIV,
    Registers.RR4_LGD
)

val registerMap: Map<String, Register> = registerList.associateBy { it.name }