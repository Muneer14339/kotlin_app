package com.dss.emulator.dsscommand

enum class StandardRequest(val value: Int) {
    GT(2), // Get Field Request
    ST(1), // Set Field Request
    SP(3), // Set Protected Field
    GI(0), // Get ID Request
    SI(4), // Set UDB Serial Number
    FT(5), // Start Factory Test
    RB(6), // Reboot
    RM(7), // Register Map Change Report
    LD(8), // Firmware Line
}