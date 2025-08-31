package com.dss.emulator.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.dss.emulator.dsscommand.DSSCommand

abstract class IEmulator {
    private var commandHistory = ""
    private var source = ""
    private var destination = ""
    private lateinit var context: Context;

    constructor(context: Context) {
        this.context = context
    }

    abstract fun sendData(data: ByteArray)
    abstract fun parseDollarCommand(command: DSSCommand)
    abstract fun parseBinaryCommand(date: ByteArray)

    fun sendCommand(command: DSSCommand) {
        command.source = this.getSource()
        command.destination = this.getDestination()
        sendData(command.commandText.toByteArray())

        logHistory(">> " + command.commandText)

        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "<< " + command.commandTextNoEnd, Toast.LENGTH_SHORT).show()
        }
        Thread.sleep(200)
    }

    fun getCommandHistory(): String {
        return commandHistory
    }

    fun setSource(source: String) {
        this.source = source
    }

    fun setDestination(destination: String) {
        this.destination = destination
    }

    fun getSource(): String {
        return source
    }

    fun getDestination(): String {
        return destination
    }

    private fun checkSourceAndDestination(command: DSSCommand): Boolean {
        return command.source == destination && command.destination == source
    }

    fun onReceiveData(data: ByteArray?) {
        if (data == null || data.isEmpty()) {
            return
        }

        if (data.size > 1) {
            if (data[0].toCharCompat() == '#') {

                try {
                    val command = DSSCommand(String(data))

                    logHistory("<< " + command.commandText)

                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, ">> " + command.commandTextNoEnd , Toast.LENGTH_SHORT).show()
                    }

                    if (!checkSourceAndDestination(command)) {
                        Log.e("CommandController", "Invalid source or destination")
                        return
                    }

                    if (!command.isChecksumValid) {
                        Log.e("CommandController", "Invalid checksum")
                    }

                    Thread {
                        parseDollarCommand(command)
                    }.start()

                } catch (e: Exception) {
                    Log.e("CommandController", "Error parsing command", e)
                }
            } else {
                parseBinaryCommand(data)
            }
        }
    }

    private fun Byte.toCharCompat(): Char = this.toInt().toChar()

    fun logHistory(log: String) {
        Log.d("CommandController", log)
        commandHistory = log + commandHistory
    }
}
