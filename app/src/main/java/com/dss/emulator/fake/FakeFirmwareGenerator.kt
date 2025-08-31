package com.dss.emulator.fake

import java.io.ByteArrayInputStream
import java.io.InputStream


object FakeFirmwareGenerator {

    fun generate(): InputStream {
        val firmwareContent = buildString {
            for (i in 1..100) {
                append("Firmware Update: Line: $i\n")
            }
        }
        return ByteArrayInputStream(firmwareContent.toByteArray())
    }
}