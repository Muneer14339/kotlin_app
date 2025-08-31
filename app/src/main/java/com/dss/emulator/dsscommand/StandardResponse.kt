package com.dss.emulator.dsscommand

enum class StandardResponse(val value: Int) {
    OK(0), // Acknowledge Response
    NO(1), // No-Acknowledge Response
    ID(2), // ID Response
    RT(3),  // Return Field Response
    ACK(4),
    NAK(5)
}