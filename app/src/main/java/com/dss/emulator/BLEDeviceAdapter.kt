package com.dss.emulator

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.dss.emulator.udb.R

class BLEDeviceAdapter(
    private val onDeviceSelected: (BLEDevice) -> Unit
) : RecyclerView.Adapter<BLEDeviceAdapter.BLEDeviceViewHolder>() {

    private val devices = mutableListOf<BLEDevice>()

    fun addDevice(device: BLEDevice) {
        devices.add(device)
        notifyItemInserted(devices.size - 1)
    }

    fun updateDeviceList(newDevices: List<BLEDevice>) {
        devices.clear()
        devices.addAll(newDevices)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BLEDeviceViewHolder {
        val view: View =
            LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return BLEDeviceViewHolder(view, onDeviceSelected)
    }

    override fun onBindViewHolder(holder: BLEDeviceViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    class BLEDeviceViewHolder(
        itemView: View, private val onDeviceSelected: (BLEDevice) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val deviceName: TextView = itemView.findViewById(R.id.deviceName)
        private val deviceAddress: TextView = itemView.findViewById(R.id.deviceAddress)
        private val deviceRssi: TextView = itemView.findViewById(R.id.deviceRssi)
        fun bind(device: BLEDevice) {
            deviceName.text = device.name
            deviceAddress.text = device.address
            deviceRssi.text = "RSSI: ${device.rssi}"
            itemView.setOnClickListener {
                onDeviceSelected(device)
            }
        }
    }
}