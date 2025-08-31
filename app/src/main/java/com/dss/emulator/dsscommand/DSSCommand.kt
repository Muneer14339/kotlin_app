package com.dss.emulator.dsscommand

data class DSSCommand(
    var type: Char = 'I', // 'I' for input, 'R' for response
    var commandId: Int = 0, // Command ID as a number
    var source: String = "",
    var destination: String = "",
    var command: String = "",
    var data: List<String> = emptyList(),
    var checksum: String = ""
) : Cloneable {

    // Companion Object for Static Members
    companion object {
        // Maximum constraints as constants
        private const val MAX_DATA_POINTS = 3
        private const val MAX_ID_WIDTH = 10
        private const val MAX_DATA_FIELD_WIDTH = 100
        private const val MAX_CHECKSUM_WIDTH = 9
        private const val MAX_COMMAND_ID_WIDTH = 10

        // Auto-incrementing command ID
        private var lastCommandId: Int = 0
        private fun getNextCommandId(): Int = ++lastCommandId

        // Regex Components
        private const val REGEX_START_PART = "\\#"
        private const val REGEX_TYPE_PART = "(?<Type>[IR])"
        private const val REGEX_COMMAND_ID_PART = "(?<CommandId>\\d{1,$MAX_COMMAND_ID_WIDTH})"
        private const val REGEX_DESTINATION_PART = "(?<Destination>[\\w-]{1,$MAX_ID_WIDTH})"
        private const val REGEX_SOURCE_PART = "(?<Source>[\\w-]{1,$MAX_ID_WIDTH})"
        private const val REGEX_COMMAND_PART = "(?<Command>([A-Z]{2,3}))"
        private const val REGEX_DATA_PART = """(?:,(?<Data>(?:[^*,]+(?:,[^*,]*)*)?))?"""
        private const val REGEX_CHECKSUM_PART = """(?:,\*(?<Checksum>\d{1,$MAX_CHECKSUM_WIDTH}))?"""
        private const val REGEX_END_PART = "[\r][\n]"

        // Compiled Regex Patterns
        private val COMMAND_REGEX = Regex(
            "$REGEX_START_PART$REGEX_TYPE_PART$REGEX_COMMAND_ID_PART,$REGEX_DESTINATION_PART,$REGEX_SOURCE_PART,$REGEX_COMMAND_PART$REGEX_DATA_PART$REGEX_CHECKSUM_PART$REGEX_END_PART"
        )
        private val COMMAND_REGEX_NO_END = Regex(
            "$REGEX_START_PART$REGEX_TYPE_PART$REGEX_COMMAND_ID_PART,$REGEX_DESTINATION_PART,$REGEX_SOURCE_PART,$REGEX_COMMAND_PART$REGEX_DATA_PART$REGEX_CHECKSUM_PART"
        )

        fun createGTCommand(
            source: String, destination: String, field: String, commandId: Int = getNextCommandId()
        ): DSSCommand {
            val args = listOf(field)
            return DSSCommand(
                type = 'I',
                commandId = commandId,
                source = source,
                destination = destination,
                command = StandardRequest.GT.name,
                data = args
            )
        }

        fun createSTCommand(
            source: String,
            destination: String,
            field: String,
            data: String,
            commandId: Int = getNextCommandId()
        ): DSSCommand {
            val args = listOf(field) + data
            return DSSCommand(
                type = 'I',
                commandId = commandId,
                source = source,
                destination = destination,
                command = StandardRequest.ST.name,
                data = args
            )
        }

        fun createSPCommand(
            source: String,
            destination: String,
            password: String,
            field: String,
            data: String,
            commandId: Int = getNextCommandId()
        ): DSSCommand {
            val args = listOf(password, field) + data
            return DSSCommand(
                type = 'I',
                commandId = commandId,
                source = source,
                destination = destination,
                command = StandardRequest.SP.name,
                data = args
            )
        }

        fun createGICommand(
            source: String, destination: String, commandId: Int = getNextCommandId()
        ): DSSCommand {
            return DSSCommand(
                type = 'I',
                commandId = commandId,
                source = source,
                destination = destination,
                command = StandardRequest.GI.name,
            )
        }

        fun createSICommand(
            source: String,
            destination: String,
            password: String,
            newSN: String,
            commandId: Int = getNextCommandId()
        ): DSSCommand {
            val args = listOf(password, newSN)
            return DSSCommand(
                type = 'I',
                commandId = commandId,
                source = source,
                destination = destination,
                command = StandardRequest.SI.name,
                data = args
            )
        }

        fun createFTCommand(
            source: String, destination: String, commandId: Int = getNextCommandId()
        ): DSSCommand {
            return DSSCommand(
                type = 'I',
                commandId = commandId,
                source = source,
                destination = destination,
                command = StandardRequest.FT.name,
            )
        }

        fun createRBCommand(
            source: String, destination: String, commandId: Int = getNextCommandId()
        ): DSSCommand {
            return DSSCommand(
                type = 'I',
                commandId = commandId,
                source = source,
                destination = destination,
                command = "RB"
            )
        }

        fun createRMCommand(
            source: String,
            destination: String,
            registerMap: Long,
            commandId: Int = getNextCommandId()
        ): DSSCommand {
            return DSSCommand(
                type = 'I',
                commandId = commandId,
                source = source,
                destination = destination,
                command = StandardRequest.RM.name,
                data = arrayListOf(registerMap.toString())
            )
        }

        fun createOKResponse(source: String, destination: String, commandId: Int = 0): DSSCommand {
            return DSSCommand(
                type = 'R',
                commandId = commandId,
                source = source,
                destination = destination,
                command = StandardResponse.OK.name
            )
        }

        fun createAckResponse(source: String, destination: String, commandId: Int = 0): DSSCommand {
            return DSSCommand(
                type = 'R',
                commandId = commandId,
                source = source,
                destination = destination,
                command = StandardResponse.ACK.name
            )
        }

        fun createRTResponse(
            source: String,
            destination: String,
            registerName: String,
            registerVaule: String,
            commandId: Int = 0
        ): DSSCommand {
            return DSSCommand(
                type = 'R',
                commandId = commandId,
                source = source,
                destination = destination,
                command = StandardResponse.RT.name,
                data = arrayListOf(registerName, registerVaule)
            )
        }

        fun createIDResponse(
            source: String, destination: String, serialNumber: String, commandId: Int = 0
        ): DSSCommand {
            return DSSCommand(
                type = 'R',
                commandId = commandId,
                source = source,
                destination = destination,
                command = StandardResponse.ID.name,
                data = listOf(serialNumber)
            )
        }

        fun createNOResponse(source: String, destination: String, commandId: Int = 0): DSSCommand {
            return DSSCommand(
                type = 'R',
                commandId = commandId,
                source = source,
                destination = destination,
                command = StandardResponse.NO.name
            )
        }

        fun createLDCommand(
            source: String,
            destination: String,
            listOf: List<String>,
            commandId: Int = getNextCommandId()
        ): DSSCommand {
            return DSSCommand(
                type = 'I',
                commandId = commandId,
                source = source,
                destination = destination,
                command = StandardRequest.LD.name,
                data = listOf
            )
        }

        fun createCustomCommand(
            destination: String,
            source: String,
            cmd: String,
            listOf: List<String>,
            commandId: Int = getNextCommandId()
        ): DSSCommand {
            return DSSCommand(
                type = 'I',
                commandId = commandId,
                source = source,
                destination = destination,
                command = cmd,
                data = listOf
            )
        }
    }

    // Computed Checksum using CRC16
    private val computedChecksum: String
        get() = CRC16.compute(commandTextBeforeCheckSum).toString()

    private val commandTextBeforeCheckSum: String
        get() = buildString {
            append("#${type}${commandId},${destination},${source},${command}")
            if (data.isNotEmpty()) {
                append(",${data.joinToString(",")}")
            }
        }

    // Command Text with End
    val commandText: String
        get() = buildString {
            append("${commandTextNoEnd}\r\n")
        }

    // Command Text without End
    val commandTextNoEnd: String
        get() = buildString {
            append("${commandTextBeforeCheckSum},*${computedChecksum}")
        }

    // Checksum Validation
    val isChecksumValid: Boolean
        get() = checksum == computedChecksum

    // Secondary Constructor from Sentence String
    constructor(sentence: String) : this() {
        val match = COMMAND_REGEX.matchEntire(sentence) ?: COMMAND_REGEX_NO_END.find(sentence)
        ?: throw IllegalArgumentException("Invalid Sentence")
        type = match.groups["Type"]?.value?.first() ?: 'I'
        commandId = match.groups["CommandId"]?.value?.toIntOrNull() ?: 0
        source = match.groups["Source"]?.value ?: ""
        destination = match.groups["Destination"]?.value ?: ""
        command = match.groups["Command"]?.value ?: ""
        checksum = match.groups["Checksum"]?.value ?: computedChecksum

        val dataGroup = match.groups["Data"]?.value ?: ""
        data = if (dataGroup.isNotEmpty()) {
            dataGroup.split(",").filter { it.isNotEmpty() }
        } else {
            emptyList()
        }

        if (checksum.isEmpty()) {
            checksum = computedChecksum
        }
    }
}
