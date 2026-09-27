package com.sydear.printertest

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.*
import java.io.File
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
    private lateinit var tsplButton: Button
    private lateinit var tsplContinuousButton: Button
    private lateinit var rawTextButton: Button
    private lateinit var readBackButton: Button
    private lateinit var updateButton: Button
    private lateinit var installButton: Button

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private var devices: List<BluetoothDevice> = emptyList()
    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private val targetMac = "66:32:8E:84:F6:84"
    private val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // Self-update: เช็คไฟล์ latest.json บน GitHub (อัพโหลดพร้อม APK ทุกครั้งที่ออกเวอร์ชันใหม่)
    private val updateApiUrl = "https://raw.githubusercontent.com/Techfrontiers/SydearPrinterTest/main/updates/latest.json"
    private val updateFileName = "sydear-printer-test-update.apk"
    private var downloadId: Long = -1

    companion object { private const val REQUEST_BT = 9001 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        ensureBluetoothPermissions()
        checkForUpdate(manual = false) // เช็คอัพเดทเงียบๆ ตอนเปิดแอป
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
        tsplButton = Button(this).apply {
            text = "TEST: TSPL สติ๊กเกอร์"; isEnabled = false; setOnClickListener { sendTsplTest() }
        }
        root.addView(tsplButton, lp())
        tsplContinuousButton = Button(this).apply {
            text = "TEST: TSPL 80mm CONTINUOUS"; isEnabled = false; setOnClickListener { sendTsplContinuousTest() }
        }
        root.addView(tsplContinuousButton, lp())
        rawTextButton = Button(this).apply {
            text = "TEST: RAW TEXT ล้วน"; isEnabled = false; setOnClickListener { sendRawTextTest() }
        }
        root.addView(rawTextButton, lp())
        readBackButton = Button(this).apply {
            text = "TEST: อ่านข้อมูลกลับ"; isEnabled = false; setOnClickListener { readBackTest() }
        }
        root.addView(readBackButton, lp())
        updateButton = Button(this).apply {
            text = "เช็คอัพเดท"; setOnClickListener { checkForUpdate(manual = true) }
        }
        root.addView(updateButton, lp())
        installButton = Button(this).apply {
            text = "ติดตั้งไฟล์ที่โหลดไว้"; setOnClickListener { installApk() }
        }
        root.addView(installButton, lp())
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

    // ---------- Self-update ----------

    private fun checkForUpdate(manual: Boolean) {
        thread {
            try {
                // เติม ?t= กัน cache เก่าของ CDN
                val conn = java.net.URL(updateApiUrl + "?t=" + System.currentTimeMillis()).openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "sydear-printer-test")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                if (conn.responseCode != 200) {
                    if (manual) log("เช็คอัพเดท: server ตอบ ${conn.responseCode}")
                    conn.disconnect()
                    return@thread
                }
                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val tag = Regex("\"version\"\\s*:\\s*\"v?([^\"]+)\"").find(json)?.groupValues?.get(1)
                val apkUrl = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                if (tag == null || apkUrl == null) {
                    if (manual) log("เช็คอัพเดท: อ่านข้อมูล release ไม่ได้")
                    return@thread
                }
                val installed = try {
                    packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
                } catch (_: Exception) { "0.0.0" }
                if (isNewerVersion(tag, installed)) {
                    log("พบเวอร์ชันใหม่: $tag (ติดตั้งอยู่ $installed)")
                    runOnUiThread { askToUpdate(tag, apkUrl) }
                } else if (manual) {
                    log("เป็นเวอร์ชันล่าสุดแล้ว ($installed)")
                }
            } catch (e: Exception) {
                if (manual) log("เช็คอัพเดทล้มเหลว: ${e.message}")
            }
        }
    }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        fun parts(v: String) = v.split(".", "-").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val r = parts(remote); val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun askToUpdate(tag: String, apkUrl: String) {
        AlertDialog.Builder(this)
            .setTitle("มีเวอร์ชันใหม่")
            .setMessage("พบเวอร์ชัน $tag\nต้องการดาวน์โหลดและติดตั้งเลยไหม?")
            .setPositiveButton("อัพเดทเลย") { _, _ -> downloadUpdate(tag, apkUrl) }
            .setNegativeButton("ไว้ก่อน", null)
            .show()
    }

    private fun downloadUpdate(tag: String, apkUrl: String) {
        try {
            // Android 8+ ต้องอนุญาต "ติดตั้งแอปที่ไม่รู้จัก" ให้แอปนี้ก่อน (ทำครั้งเดียว)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                log("กรุณาเปิด 'อนุญาตติดตั้งแอปที่ไม่รู้จัก' ให้แอปนี้ แล้วกดเช็คอัพเดทอีกครั้ง")
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return
            }
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), updateFileName).delete()
            val req = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("SYDEAR Printer Test $tag")
                .setDescription("กำลังดาวน์โหลดอัพเดท...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, updateFileName)
            downloadId = dm.enqueue(req)
            log("กำลังดาวน์โหลดอัพเดท $tag ...")
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != downloadId) return
                    try { unregisterReceiver(this) } catch (_: Exception) {}
                    dm.query(DownloadManager.Query().setFilterById(downloadId)).use { c ->
                        if (c.moveToFirst()) {
                            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                log("ดาวน์โหลดเสร็จ กำลังเปิดตัวติดตั้ง...")
                                installApk()
                            } else {
                                val reason = try {
                                    c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                                } catch (_: Exception) { -1 }
                                log("ดาวน์โหลดล้มเหลว (reason=$reason)")
                            }
                        } else log("ไม่พบข้อมูลดาวน์โหลด")
                    }
                }
            }
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            log("เริ่มดาวน์โหลดไม่ได้: ${e.message}")
        }
    }

    // คืน true ถ้าเรียกตัวติดตั้งสำเร็จ, false ถ้าไม่ (ให้ผู้ใช้กดปุ่มติดตั้งเอง)
    private fun installApk(): Boolean {
        try {
            val f = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), updateFileName)
            if (!f.isFile || f.length() == 0L) {
                log("ยังไม่มีไฟล์อัพเดทให้ติดตั้ง")
                return false
            }
            log("พบไฟล์อัพเดท ${f.length() / 1024} KB กำลังเปิดตัวติดตั้ง...")
            val uri = Uri.parse("content://$packageName.apkprovider/$updateFileName")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
            return true
        } catch (e: Exception) {
            log("เปิดตัวติดตั้งไม่ได้ (${e.javaClass.simpleName}): ${e.message}")
            log("กดปุ่ม 'ติดตั้งไฟล์ที่โหลดไว้' เพื่อลองอีกครั้ง")
            return false
        }
    }

    // ---------- Bluetooth (เดิม) ----------

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
                    tsplButton.isEnabled = true; rawTextButton.isEnabled = true; readBackButton.isEnabled = true
                    tsplContinuousButton.isEnabled = true
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
        tsplButton.isEnabled = false; rawTextButton.isEnabled = false; readBackButton.isEnabled = false
        tsplContinuousButton.isEnabled = false
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

    // ---------- TSPL (ภาษาเครื่องพิมพ์สติ๊กเกอร์) ----------
    // ---------- Printer Profile: 80mm Continuous Thermal ----------
    // โปรไฟล์เครื่องพิมพ์กระดาษต่อเนื่อง 80mm (แบบเดียวกับที่ตั้งใน Windows driver)
    data class PrinterProfile(
        val name: String,
        val widthMm: Int,
        val defaultHeightMm: Int,
        val media: String,      // Continuous
        val gapMm: Int,         // 0 = กระดาษต่อเนื่อง ไม่มีช่องว่าง
        val gapOffset: Int,
        val speed: Int,         // 8
        val density: Int,       // 8
        val direction: Int,     // 0
        val peel: Boolean,      // false = OFF
        val cutter: Boolean,    // false = OFF
        val tear: Boolean       // false = OFF
    )

    private val profile80mmContinuous = PrinterProfile(
        name = "80mm Continuous Thermal",
        widthMm = 80,
        defaultHeightMm = 100,
        media = "Continuous",
        gapMm = 0,
        gapOffset = 0,
        speed = 8,
        density = 8,
        direction = 0,
        peel = false,
        cutter = false,
        tear = false
    )

    private fun onOff(b: Boolean) = if (b) "ON" else "OFF"

    // บล็อกตั้งค่า TSPL ที่ต้องส่งก่อนเนื้อหาทุกครั้ง
    private fun tsplConfigBlock(p: PrinterProfile): String = buildString {
        append("SIZE ${p.widthMm} mm,${p.defaultHeightMm} mm\r\n")
        append("GAP ${p.gapMm},${p.gapOffset}\r\n")
        append("SPEED ${p.speed}\r\n")
        append("DENSITY ${p.density}\r\n")
        append("DIRECTION ${p.direction}\r\n")
        append("REFERENCE 0,0\r\n")
        append("SET PEEL ${onOff(p.peel)}\r\n")
        append("SET CUTTER ${onOff(p.cutter)}\r\n")
        append("SET TEAR ${onOff(p.tear)}\r\n")
        append("CLS\r\n")
    }

    private fun sendTsplContinuousTest() {
        val p = profile80mmContinuous
        val tspl = buildString {
            append(tsplConfigBlock(p))
            append("TEXT 30,30,\"TSS24.BF2\",0,1,1,\"SYDEAR TSPL TEST\"\r\n")
            append("TEXT 30,70,\"TSS24.BF2\",0,1,1,\"ES-9910UB\"\r\n")
            append("TEXT 30,110,\"TSS24.BF2\",0,1,1,\"80mm CONTINUOUS\"\r\n")
            append("TEXT 30,150,\"TSS24.BF2\",0,1,1,\"SPEED 8\"\r\n")
            append("TEXT 30,190,\"TSS24.BF2\",0,1,1,\"DENSITY 8\"\r\n")
            append("TEXT 30,230,\"TSS24.BF2\",0,1,1,\"GAP 0\"\r\n")
            append("PRINT 1\r\n")
        }
        log("ใช้โปรไฟล์: ${p.name}")
        sendBytes(tspl.toByteArray(Charsets.US_ASCII), "TSPL 80mm CONTINUOUS")
    }

    private fun sendTsplTest() {
        val tspl = buildString {
            append("SIZE 80 mm,40 mm\r\n")
            append("GAP 3 mm,0 mm\r\n")
            append("DIRECTION 1\r\n")
            append("CLS\r\n")
            append("TEXT 30,30,\"TSS24.BF2\",0,1,1,\"SYDEAR TSPL TEST\"\r\n")
            append("TEXT 30,70,\"TSS24.BF2\",0,1,1,\"1234567890\"\r\n")
            append("BARCODE 30,110,\"128\",60,1,0,2,2,\"TEST123\"\r\n")
            append("PRINT 1\r\n")
        }
        sendBytes(tspl.toByteArray(Charsets.US_ASCII), "TSPL label")
    }

    private fun sendRawTextTest() {
        val raw = "SYDEAR RAW TEST\n1234567890\n\n\n"
        sendBytes(raw.toByteArray(Charsets.UTF_8), "RAW TEXT")
    }

    // อ่านข้อมูลที่เครื่องส่งกลับ (ถ้ามี) เพื่อดูว่าช่องสื่อสารสองทางหรือไม่
    private fun readBackTest() {
        val s = socket
        if (s == null) { log("ยังไม่ได้เชื่อมต่อ"); return }
        log("รอฟังข้อมูลจากเครื่อง 3 วินาที...")
        thread {
            try {
                val inp = s.inputStream
                val buf = ByteArray(256)
                var total = 0
                val deadline = System.currentTimeMillis() + 3000
                while (System.currentTimeMillis() < deadline && total < buf.size) {
                    val avail = inp.available()
                    if (avail > 0) {
                        val n = inp.read(buf, total, minOf(avail, buf.size - total))
                        if (n <= 0) break
                        total += n
                    } else {
                        Thread.sleep(100)
                    }
                }
                if (total > 0) {
                    val hex = buf.take(total).joinToString(" ") { "%02X".format(it) }
                    log("เครื่องตอบกลับ $total bytes: $hex")
                } else {
                    log("เครื่องไม่ส่งข้อมูลกลับมาเลย")
                }
            } catch (e: Exception) {
                log("อ่านข้อมูลล้มเหลว: ${e.message}")
            }
        }
    }

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
