// Enhanced Color Coding System for UDB Emulator
// Based on industry standards for status indication systems

package com.dss.emulator.ui

import android.graphics.Color
import com.dss.emulator.core.ReleaseState

object UDBColorCoding {

    object StatusColors {
        val SUCCESS = Color.parseColor("#4CAF50")      // Green - Success, OK, Normal
        val ERROR = Color.parseColor("#F44336")        // Red - Error, Failure, Critical
        val WARNING = Color.parseColor("#FF9800")      // Orange - Warning, Attention needed
        val PENDING = Color.parseColor("#FFC107")      // Yellow - Pending, In progress
        val INFO = Color.parseColor("#2196F3")         // Blue - Information, Special ops
        val ARMED = Color.parseColor("#9C27B0")        // Purple - Armed, Dangerous
        val IDLE = Color.parseColor("#757575")         // Gray - Idle, Neutral
        val CRITICAL = Color.parseColor("#D32F2F")     // Dark Red - Critical operations
        val ACTIVE = Color.parseColor("#00BCD4")       // Cyan - Active operations

        // ✅ Add these so code compiles
        val RED = ERROR
        val BLUE = INFO
        val YELLOW = PENDING
        val GREEN = SUCCESS
    }



    // Text colors for readability
    object TextColors {
        const val ON_DARK = Color.WHITE
        const val ON_LIGHT = Color.BLACK
        const val ON_COLORED = Color.WHITE
    }

    /**
     * Get the appropriate color for a release state
     * Based on official UDB emulator specification document
     */
    fun getStateColor(state: ReleaseState): Int {
        return when (state) {
            // IDLE STATES - Red (Initial state)
            ReleaseState.IDLE_REQ -> StatusColors.BLUE
            ReleaseState.IDLE_ACK -> StatusColors.BLUE

            // INITIALIZATION STATES
            ReleaseState.INIT_REQ -> StatusColors.BLUE
            ReleaseState.INIT_PENDING -> StatusColors.BLUE
            ReleaseState.INIT_OK -> StatusColors.YELLOW
            ReleaseState.INIT_FAIL -> StatusColors.BLUE

            // CONNECTION STATES
            ReleaseState.CON_REQ -> StatusColors.YELLOW
            ReleaseState.CON_ID1 -> StatusColors.YELLOW
            ReleaseState.CON_ID2 -> StatusColors.YELLOW
            ReleaseState.CON_OK -> StatusColors.GREEN

            // SINGLE RANGING STATES
            ReleaseState.RNG_SINGLE_REQ -> StatusColors.GREEN
            ReleaseState.RNG_SINGLE_PENDING -> StatusColors.GREEN
            ReleaseState.RNG_SINGLE_OK -> StatusColors.GREEN
            ReleaseState.RNG_SINGLE_FAIL -> StatusColors.YELLOW

            // CONTINUOUS RANGING STATES
            ReleaseState.RNG_CONT_REQ -> StatusColors.GREEN
            ReleaseState.RNG_CONT_PENDING -> StatusColors.GREEN
            ReleaseState.RNG_CONT_OK -> StatusColors.GREEN
            ReleaseState.RNG_CONT_FAIL -> StatusColors.GREEN

            // TRIGGER/ARM STATES
            ReleaseState.AT_REQ -> StatusColors.GREEN
            ReleaseState.AT_ARM_PENDING -> StatusColors.GREEN
            ReleaseState.AT_ARM_OK -> StatusColors.GREEN
            ReleaseState.AT_ARM_FAIL -> StatusColors.GREEN
            ReleaseState.AT_TRG_PENDING -> StatusColors.GREEN
            ReleaseState.AT_TRG_OK -> StatusColors.GREEN
            ReleaseState.AT_TRG_FAIL -> StatusColors.GREEN

            // BROADCAST STATES
            ReleaseState.BCR_REQ -> StatusColors.GREEN
            ReleaseState.BCR_PENDING -> StatusColors.GREEN
            ReleaseState.BCR_OK -> StatusColors.GREEN

            // PUBLIC INTERROGATE QUICK ID STATES
            ReleaseState.PI_QID_REQ -> StatusColors.GREEN
            ReleaseState.PI_QID_PENDING -> StatusColors.GREEN
            ReleaseState.PI_QID_DETECT -> StatusColors.GREEN
            ReleaseState.PI_QID_NODETECT -> StatusColors.GREEN

            // PUBLIC INTERROGATE FULL ID STATES
            ReleaseState.PI_ID_REQ -> StatusColors.GREEN
            ReleaseState.PI_ID_PENDING -> StatusColors.GREEN
            ReleaseState.PI_ID_DETECT -> StatusColors.GREEN
            ReleaseState.PI_ID_NODETECT -> StatusColors.GREEN

            // NOISE TEST STATES
            ReleaseState.NT_REQ -> StatusColors.YELLOW
            ReleaseState.NT_PENDING -> StatusColors.YELLOW
            ReleaseState.NT_OK -> StatusColors.YELLOW

            // REBOOT STATES
            ReleaseState.RB_OK -> StatusColors.YELLOW
            ReleaseState.RB_ACK -> StatusColors.BLUE
        }
    }

    /**
     * Get connection status color
     */
    fun getConnectionColor(isConnected: Boolean): Int {
        return if (isConnected) StatusColors.SUCCESS else StatusColors.ERROR
    }

    /**
     * Get retry count color based on number of retries
     */
    fun getRetryCountColor(retryCount: Int): Int {
        return when {
            retryCount == 0 -> StatusColors.SUCCESS
            retryCount < 5 -> StatusColors.WARNING
            retryCount < 10 -> StatusColors.ERROR
            else -> StatusColors.CRITICAL
        }
    }

    /**
     * Get range distance color based on distance and quality
     */
    fun getRangeColor(rangeMeters: Double): Int {
        return when {
            rangeMeters < 0.5 -> StatusColors.ERROR  // Too close
            rangeMeters < 10.0 -> StatusColors.SUCCESS  // Good range
            rangeMeters < 50.0 -> StatusColors.WARNING  // Far but OK
            else -> StatusColors.ERROR  // Too far
        }
    }

    /**
     * Get noise level color based on dB level
     */
    fun getNoiseColor(noiseDB: Int): Int {
        return when {
            noiseDB < 30 -> StatusColors.SUCCESS  // Low noise
            noiseDB < 60 -> StatusColors.WARNING  // Medium noise
            else -> StatusColors.ERROR  // High noise
        }
    }

    /**
     * Get battery level color
     */
    fun getBatteryColor(voltageV: Double): Int {
        return when {
            voltageV > 13.0 -> StatusColors.SUCCESS  // Good
            voltageV > 11.5 -> StatusColors.WARNING  // Low
            else -> StatusColors.ERROR  // Critical
        }
    }

    /**
     * Get temperature color (for underwater operations)
     */
    fun getTemperatureColor(tempC: Double): Int {
        return when {
            tempC < 0.0 -> StatusColors.ERROR  // Freezing
            tempC < 5.0 -> StatusColors.WARNING  // Very cold
            tempC < 30.0 -> StatusColors.SUCCESS  // Normal
            else -> StatusColors.WARNING  // Too warm for typical underwater
        }
    }

    /**
     * Get operation status message with appropriate formatting
     */
    fun getStatusMessage(state: ReleaseState): String {
        return when (state) {
            ReleaseState.IDLE_REQ -> "Requesting Idle State"
            ReleaseState.IDLE_ACK -> "✓ Ready - Device Idle"
            ReleaseState.INIT_REQ -> "Requesting Initialization"
            ReleaseState.INIT_PENDING -> "⏳ Initializing System..."
            ReleaseState.INIT_OK -> "✓ Initialization Complete"
            ReleaseState.INIT_FAIL -> "✗ Initialization Failed"
            ReleaseState.CON_REQ -> "Requesting Connection"
            ReleaseState.CON_ID1 -> "📡 ID Exchange 1/2"
            ReleaseState.CON_ID2 -> "📡 ID Exchange 2/2"
            ReleaseState.CON_OK -> "✓ Connected to Release Device"
            ReleaseState.RNG_SINGLE_REQ -> "Requesting Single Range"
            ReleaseState.RNG_SINGLE_PENDING -> "📏 Measuring Range..."
            ReleaseState.RNG_SINGLE_OK -> "✓ Range Measurement Complete"
            ReleaseState.RNG_SINGLE_FAIL -> "✗ Range Measurement Failed"
            ReleaseState.RNG_CONT_REQ -> "Requesting Continuous Range"
            ReleaseState.RNG_CONT_PENDING -> "📏 Starting Continuous Range..."
            ReleaseState.RNG_CONT_OK -> "📏 Continuous Ranging Active"
            ReleaseState.RNG_CONT_FAIL -> "✗ Continuous Range Failed"
            ReleaseState.AT_REQ -> "Requesting Arm/Trigger"
            ReleaseState.AT_ARM_PENDING -> "⚠️ ARMING DEVICE..."
            ReleaseState.AT_ARM_OK -> "⚠️ DEVICE ARMED - READY"
            ReleaseState.AT_ARM_FAIL -> "✗ Device Arm Failed"
            ReleaseState.AT_TRG_PENDING -> "🚨 TRIGGERING RELEASE..."
            ReleaseState.AT_TRG_OK -> "🚨 DEVICE RELEASED!"
            ReleaseState.AT_TRG_FAIL -> "✗ Trigger Failed"
            ReleaseState.BCR_REQ -> "Requesting Broadcast"
            ReleaseState.BCR_PENDING -> "📢 Broadcasting Signal..."
            ReleaseState.BCR_OK -> "✓ Broadcast Complete"
            ReleaseState.PI_QID_REQ -> "Requesting Public Quick ID"
            ReleaseState.PI_QID_PENDING -> "🔍 Searching Quick ID..."
            ReleaseState.PI_QID_DETECT -> "✓ Quick ID Detected"
            ReleaseState.PI_QID_NODETECT -> "⚠️ No Quick ID Response"
            ReleaseState.PI_ID_REQ -> "Requesting Public Full ID"
            ReleaseState.PI_ID_PENDING -> "🔍 Searching Full ID..."
            ReleaseState.PI_ID_DETECT -> "✓ Full ID Detected"
            ReleaseState.PI_ID_NODETECT -> "⚠️ No Full ID Response"
            ReleaseState.NT_REQ -> "Requesting Noise Test"
            ReleaseState.NT_PENDING -> "🔊 Measuring Noise Level..."
            ReleaseState.NT_OK -> "✓ Noise Test Complete"
            ReleaseState.RB_OK -> "⏳ Rebooting Device..."
            ReleaseState.RB_ACK -> "✓ Device Rebooted"
        }
    }

    /**
     * Get text color that contrasts well with background color
     */
    fun getTextColor(backgroundColor: Int): Int {
        val red = Color.red(backgroundColor)
        val green = Color.green(backgroundColor)
        val blue = Color.blue(backgroundColor)
        val brightness = (red * 299 + green * 587 + blue * 114) / 1000
        return if (brightness > 128) TextColors.ON_LIGHT else TextColors.ON_DARK
    }
}