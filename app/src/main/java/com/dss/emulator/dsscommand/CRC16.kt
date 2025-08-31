package com.dss.emulator.dsscommand

object CRC16 {
    private const val CRC16_POLY: Int = 0x8408
    private const val INITIAL_CRC: Int = 0xFFFF
    fun compute(data: String): Int {
        val bytes = data.toByteArray(Charsets.US_ASCII)
        return compute(bytes, bytes.size)
    }

    private fun compute(data: ByteArray, length: Int): Int {
        var crc = INITIAL_CRC
        var dataIndex = 0
        var len = length

        if (length == 0) {
            crc = crc.inv()
        } else {
            while (len-- > 0) {
                var currentByte = (data[dataIndex].toInt() and 0xFF)
                dataIndex++

                for (i in 0 until 8) {
                    val bit = (currentByte and 0x01)
                    val crcBit = (crc and 0x0001)
                    val xor = bit xor crcBit

                    crc = crc ushr 1
                    if (xor != 0) {
                        crc = crc xor CRC16_POLY
                    }

                    currentByte = currentByte ushr 1
                }
            }
            crc = crc.inv()
            crc = (crc shl 8) or ((crc ushr 8) and 0xFF)
        }

        // Ensure CRC is within 16 bits
        return crc and 0xFFFF
    }
}
