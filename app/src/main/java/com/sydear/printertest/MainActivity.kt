package com.sydear.printertest

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.*
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var connectButton: Button
    private lateinit var testButton: Button
    private lateinit var bitmapButton: Button
    private lateinit var feedButton: Button

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private var devices: List<BluetoothDevice> = emptyList()
    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private val targetMac = "66:32:8E:84:F6:84"
    private val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    companion object { private const val REQUEST_BT = 9001 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        ensureBluetoothPermissions()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
        }
        root.addView(TextView(this).apply {
            text = "SYDEAR Printer Test"; textSize = 25f; typeface = Typeface.DEFAULT_BOLD
        }, lp())
        root.addView(TextView(this).apply {
            text = "ES-9910UB Bluetooth / protocol test"; textSize = 15f
        }, lp())
        status = TextView(this).apply {
            text = "สถานะ: ยังไม่ได้เชื่อมต่อ"; textSize = 17f; setPadding(0,18,0,12)
        }
        root.addView(status, lp())
        root.addView(TextView(this).apply {
            text = "Target MAC: $targetMac"; textSize = 14f
        }, lp())
        deviceSpinner = Spinner(this)
        root.addView(deviceSpinner, lp())
        root.addView(Button(this).apply {
            text = "โหลดเครื่องที่จับคู่ไว้"; setOnClickListener { loadPairedDevices() }
        }, lp())
        connectButton = Button(this).apply {
            text = "เชื่อมต่อ"; setOnClickListener { connectSelected() }
        }
        root.addView(connectButton, lp())
        testButton = Button(this).apply {
            text = "TEST: ESC/POS Text"; isEnabled = false; setOnClickListener { sendTextTest() }
        }
        root.addView(testButton, lp())
        bitmapButton = Button(this).apply {
            text = "TEST: Bitmap / Thai"; isEnabled = false; setOnClickListener { sendBitmapTest() }
        }
        root.addView(bitmapButton, lp())
        feedButton = Button(this).apply {
            text = "TEST: Feed กระดาษ"; isEnabled = false
            setOnClickListener { sendBytes(byteArrayOf(0x1B,0x64,0x03), "Feed") }
        }
        root.addView(feedButton, lp())
        root.addView(TextView(this).apply {
            text = "Log"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setPadding(0,18,0,6)
        }, lp())
        logView = TextView(this).apply {
            textSize = 13f; setTextIsSelectable(true); text = "รอเริ่มทดสอบ...\n"
        }
        root.addView(ScrollView(this).apply { addView(logView) }, LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
    }

    private fun lp() = LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun ensureBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val missing = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_BT) else loadPairedDevices()
        } else loadPairedDevices()
    }

    override fun onRequestPermissionsResult(requestCode:Int, permissions:Array<out String>, grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BT) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) loadPairedDevices()
            else log("ไม่ได้รับ Bluetooth permission")
        }
    }

    private fun loadPairedDevices() {
        val adapter = bluetoothAdapter ?: run { log("เครื่องนี้ไม่มี Bluetooth"); return }
        if (!adapter.isEnabled) { log("Bluetooth ยังไม่เปิด"); return }
        try {
            devices = adapter.bondedDevices.toList().sortedBy { it.name ?: it.address }
            if (devices.isEmpty()) {
                log("ไม่พบอุปกรณ์ที่จับคู่ไว้")
                deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf("ไม่พบอุปกรณ์"))
                return
            }
            val labels = devices.map { d ->
                val name = d.name ?: "Unknown"
                if (d.address.equals(targetMac,true)) "$name  (${d.address})  ★" else "$name  (${d.address})"
            }
            deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
            devices.indexOfFirst { it.address.equals(targetMac,true) }.takeIf { it >= 0 }?.let { deviceSpinner.setSelection(it) }
            log("พบ paired devices ${devices.size} เครื่อง")
            log("Target: $targetMac")
        } catch (e: SecurityException) { log("Bluetooth permission error: ${e.message}") }
    }

    private fun connectSelected() {
        if (socket?.isConnected == true) { disconnect(); return }
        if (devices.isEmpty()) { log("ยังไม่มี paired device"); return }
        val device = devices.getOrNull(deviceSpinner.selectedItemPosition) ?: return
        connectButton.isEnabled = false
        status.text = "สถานะ: กำลังเชื่อมต่อ..."
        log("Connecting ${device.name} ${device.address}")
        thread {
            var connected: BluetoothSocket? = null
            var lastError: Exception? = null
            try {
                connected = device.createRfcommSocketToServiceRecord(sppUuid)
                connected.connect()
            } catch (e: Exception) {
                lastError = e
                try { connected?.close() } catch (_: Exception) {}
                connected = null
                log("Secure SPP failed: ${e.javaClass.simpleName}: ${e.message}")
                try {
                    connected = device.createInsecureRfcommSocketToServiceRecord(sppUuid)
                    connected.connect()
                } catch (e2: Exception) {
                    lastError = e2
                    try { connected?.close() } catch (_: Exception) {}
                    connected = null
                    log("Insecure SPP failed: ${e2.javaClass.simpleName}: ${e2.message}")
                }
            }
            runOnUiThread {
                connectButton.isEnabled = true
                if (connected != null && connected!!.isConnected) {
                    socket = connected; output = connected!!.outputStream
                    status.text = "สถานะ: Connected ✅"; connectButton.text = "ตัดการเชื่อมต่อ"
                    testButton.isEnabled = true; bitmapButton.isEnabled = true; feedButton.isEnabled = true
                    log("CONNECTED via RFCOMM/SPP")
                } else {
                    status.text = "สถานะ: Connect failed ❌"
                    log("CONNECT FAILED: ${lastError?.message ?: "unknown error"}")
                }
            }
        }
    }

    private fun disconnect() {
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        output = null; socket = null
        status.text = "สถานะ: ยังไม่ได้เชื่อมต่อ"; connectButton.text = "เชื่อมต่อ"
        testButton.isEnabled = false; bitmapButton.isEnabled = false; feedButton.isEnabled = false
        log("Disconnected")
    }

    private fun sendTextTest() {
        val out = output ?: run { log("ยังไม่ได้ connect"); return }
        thread {
            try {
                val data = Builder().bytes(0x1B,0x40).bytes(0x1B,0x61,0x01).bytes(0x1B,0x45,0x01)
                    .text("SYDEAR TEST\n").bytes(0x1B,0x45,0x00).bytes(0x1B,0x61,0x00)
                    .text("ES-9910UB\n").text("Bluetooth SPP OK?\n").bytes(0x1B,0x64,0x04).build()
                synchronized(out) { out.write(data); out.flush() }
                log("ส่ง ESC/POS text test แล้ว (${data.size} bytes)")
            } catch (e: Exception) { log("Text test failed: ${e.message}") }
        }
    }

    private fun sendBitmapTest() { log("Bitmap test: รอผล Text test ก่อน เพื่อยืนยัน protocol") }

    private fun sendBytes(bytes:ByteArray, label:String) {
        val out = output ?: run { log("ยังไม่ได้ connect"); return }
        thread {
            try { synchronized(out) { out.write(bytes); out.flush() }; log("ส่ง $label แล้ว (${bytes.size} bytes)") }
            catch (e:Exception) { log("$label failed: ${e.message}") }
        }
    }

    private fun log(message:String) {
        runOnUiThread {
            val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            logView.append("[$stamp] $message\n")
        }
    }

    override fun onDestroy() { disconnect(); super.onDestroy() }

    private class Builder {
        private val bytes = java.io.ByteArrayOutputStream()
        fun bytes(vararg values:Int):Builder { values.forEach { bytes.write(it and 0xFF) }; return this }
        fun text(value:String):Builder { bytes.write(value.toByteArray(Charsets.US_ASCII)); return this }
        fun build():ByteArray = bytes.toByteArray()
    }
}
