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
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.ceil

class MainActivity : Activity() {
    // ---------- UI: tabs ----------
    private lateinit var tabPrint: Button
    private lateinit var tabPrinter: Button
    private lateinit var tabLog: Button
    private lateinit var printScreen: LinearLayout
    private lateinit var printerScroll: ScrollView
    private lateinit var logScreen: LinearLayout
    private lateinit var status: TextView
    private lateinit var logView: TextView

    // ---------- UI: print tab ----------
    private lateinit var printInput: EditText
    private lateinit var printButton: Button
    private lateinit var quickTestButton: Button

    // ---------- UI: printer tab ----------
    private lateinit var deviceSpinner: Spinner
    private lateinit var connectButton: Button
    private lateinit var webhookToggle: Button
    private lateinit var webhookInfo: TextView
    private lateinit var webhookKeyView: TextView
    private lateinit var updateButton: Button
    private lateinit var installButton: Button

    // ---------- Bluetooth ----------
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private var devices: List<BluetoothDevice> = emptyList()
    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private val targetMac = "66:32:8E:84:F6:84"
    private val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // ---------- Webhook ----------
    private var webhookServer: WebhookServer? = null
    private val webhookPort = 8080
    private var webhookKey: String = ""

    // ---------- Self-update ----------
    private val updateApiUrl = "https://raw.githubusercontent.com/Techfrontiers/SydearPrinterTest/main/updates/latest.json"
    private val updateFileName = "sydear-printer-test-update.apk"
    private var downloadId: Long = -1

    companion object { private const val REQUEST_BT = 9001 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webhookKey = loadOrCreateWebhookKey()
        buildUi()
        ensureBluetoothPermissions()
        checkForUpdate(manual = false)
    }

    // ================= UI =================

    private fun lp() = LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun fill() = LinearLayout.LayoutParams(-1, 0, 1f)

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, 20, 0, 6)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
        }
        root.addView(TextView(this).apply {
            text = "SYDEAR Printer"; textSize = 24f; typeface = Typeface.DEFAULT_BOLD
        }, lp())

        // แถบเมนู 3 ปุ่ม
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabPrint = Button(this).apply { text = "🖨️ พิมพ์"; setOnClickListener { showTab(0) } }
        tabPrinter = Button(this).apply { text = "🔵 เครื่องพิมพ์"; setOnClickListener { showTab(1) } }
        tabLog = Button(this).apply { text = "📋 Log"; setOnClickListener { showTab(2) } }
        val tabLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        tabs.addView(tabPrint, tabLp); tabs.addView(tabPrinter, tabLp); tabs.addView(tabLog, tabLp)
        root.addView(tabs, lp())

        status = TextView(this).apply {
            text = "สถานะ: ยังไม่ได้เชื่อมต่อ"; textSize = 15f; setPadding(0, 10, 0, 4)
        }
        root.addView(status, lp())

        printScreen = buildPrintScreen()
        printerScroll = ScrollView(this).apply { addView(buildPrinterScreen()) }
        logScreen = buildLogScreen()
        root.addView(printScreen, fill())
        root.addView(printerScroll, fill())
        root.addView(logScreen, fill())

        setContentView(root)
        showTab(0)
        updateWebhookInfo()
    }

    private fun showTab(i: Int) {
        printScreen.visibility = if (i == 0) View.VISIBLE else View.GONE
        printerScroll.visibility = if (i == 1) View.VISIBLE else View.GONE
        logScreen.visibility = if (i == 2) View.VISIBLE else View.GONE
        tabPrint.isEnabled = i != 0
        tabPrinter.isEnabled = i != 1
        tabLog.isEnabled = i != 2
    }

    // ---------- หน้าพิมพ์ ----------

    private fun buildPrintScreen(): LinearLayout {
        val s = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        s.addView(TextView(this).apply {
            text = "ข้อความที่จะพิมพ์"; textSize = 16f
            typeface = Typeface.DEFAULT_BOLD; setPadding(0, 8, 0, 4)
        }, lp())

        // แถบเครื่องมือจัดรูปแบบข้อความ
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(fmtButton("B", { toggleStyleSpan { StyleSpan(Typeface.BOLD) } }) {
            it.typeface = Typeface.DEFAULT_BOLD
        })
        bar.addView(fmtButton("I", { toggleStyleSpan { StyleSpan(Typeface.ITALIC) } }) {
            it.setTypeface(it.typeface, Typeface.ITALIC)
        })
        bar.addView(fmtButton("U", { toggleStyleSpan { UnderlineSpan() } }) {
            it.paintFlags = it.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        })
        bar.addView(fmtButton("S", onClick = { setSizeSpan(0.75f) }))
        bar.addView(fmtButton("M", onClick = { setSizeSpan(null) }))
        bar.addView(fmtButton("L", onClick = { setSizeSpan(1.5f) }))
        bar.addView(fmtButton("ซ้าย", onClick = { setAlignSpan(Layout.Alignment.ALIGN_NORMAL) }))
        bar.addView(fmtButton("กลาง", onClick = { setAlignSpan(Layout.Alignment.ALIGN_CENTER) }))
        bar.addView(fmtButton("ขวา", onClick = { setAlignSpan(Layout.Alignment.ALIGN_OPPOSITE) }))
        s.addView(HorizontalScrollView(this).apply { addView(bar) }, lp())

        printInput = EditText(this).apply {
            hint = "พิมพ์ข้อความที่นี่…\nลากเลือกข้อความแล้วกด B / I / U / ขนาด / จัดแนว\n(ไม่เลือก = ทั้งเอกสาร)"
            minLines = 6
            gravity = Gravity.TOP
            textSize = 18f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val inputLp = LinearLayout.LayoutParams(-1, 0, 1f)
        inputLp.setMargins(0, 8, 0, 8)
        s.addView(printInput, inputLp)

        printButton = Button(this).apply {
            text = "🖨️ พิมพ์ข้อความ"; textSize = 18f
            setOnClickListener { printStyledText() }
        }
        s.addView(printButton, lp())
        val invertCheck = CheckBox(this).apply {
            text = "กลับสีบิตแมป (ถ้าพื้นพิมพ์ดำให้ลองติ๊ก/เอาติ๊กออก)"
            isChecked = getSharedPreferences("sydear_printer", MODE_PRIVATE)
                .getBoolean("invert_bitmap", false)
            setOnCheckedChangeListener { _, v ->
                getSharedPreferences("sydear_printer", MODE_PRIVATE)
                    .edit().putBoolean("invert_bitmap", v).apply()
                log("กลับสีบิตแมป: ${if (v) "เปิด" else "ปิด"}")
            }
        }
        s.addView(invertCheck, lp())
        s.addView(Button(this).apply {
            text = "🔬 พิมพ์แพทเทิร์นทดสอบ"
            setOnClickListener { printDiagnosticPattern() }
        }, lp())
        quickTestButton = Button(this).apply {
            text = "ทดสอบด่วน (TSPL)"; setOnClickListener { sendTsplContinuousTest() }
        }
        s.addView(quickTestButton, lp())
        return s
    }

    private fun fmtButton(label: String, onClick: () -> Unit, style: (Button) -> Unit = {}): Button {
        return Button(this).apply {
            text = label; textSize = 14f
            style(this)
            setOnClickListener { onClick() }
        }
    }

    /** ช่วงข้อความเป้าหมาย: ที่เลือกไว้ หรือทั้งเอกสาร (ถ้าไม่ได้ลากเลือก) */
    private fun effectiveRange(): IntRange {
        val len = printInput.length()
        val s = printInput.selectionStart.coerceAtLeast(0).coerceAtMost(len)
        val e = printInput.selectionEnd.coerceAtLeast(0).coerceAtMost(len)
        return if (s == e) 0..len else minOf(s, e)..maxOf(s, e)
    }

    private inline fun <reified T> toggleStyleSpan(crossinline make: () -> T) {
        val text = printInput.text
        val r = effectiveRange()
        val existing = text.getSpans(r.first, r.last, T::class.java)
        if (existing.isNotEmpty()) existing.forEach { text.removeSpan(it) }
        else if (r.first < r.last) text.setSpan(make(), r.first, r.last, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** factor = null → กลับขนาดปกติ */
    private fun setSizeSpan(factor: Float?) {
        val text = printInput.text
        val r = effectiveRange()
        text.getSpans(r.first, r.last, RelativeSizeSpan::class.java).forEach { text.removeSpan(it) }
        if (factor != null && r.first < r.last)
            text.setSpan(RelativeSizeSpan(factor), r.first, r.last, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun setAlignSpan(alignment: Layout.Alignment) {
        val text = printInput.text
        val r = effectiveRange()
        // ขยายให้ครอบย่อหน้าที่แตะอยู่
        var s = r.first
        var e = r.last
        while (s > 0 && text[s - 1] != '\n') s--
        while (e < text.length && text[e] != '\n') e++
        if (s >= e) { s = 0; e = text.length }
        text.getSpans(s, e, AlignmentSpan::class.java).forEach { text.removeSpan(it) }
        if (s < e) text.setSpan(AlignmentSpan.Standard(alignment), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    // ---------- สั่งพิมพ์ ----------

    /** พิมพ์ข้อความจากกล่อง (เรนเดอร์เป็นบิตแมป 1-bit → TSPL BITMAP) */
    private fun printStyledText() {
        val spanned = printInput.text
        if (spanned.isNullOrBlank()) { log("พิมพ์ข้อความก่อน"); return }
        val invert = getSharedPreferences("sydear_printer", MODE_PRIVATE)
            .getBoolean("invert_bitmap", false)
        thread {
            try {
                val job = PrintRenderer.render(profile80mmContinuous, spanned, invert)
                if (job == null) { log("ไม่มีข้อความให้พิมพ์"); return@thread }
                job.logLines.forEach { log(it) }
                sendBytes(job.tspl, "BITMAP print")
            } catch (e: Exception) {
                log("พิมพ์ล้มเหลว: ${e.message}")
            }
        }
    }

    /**
     * แพทเทิร์นวินิจฉัย: พิมพ์ป้าย TEXT (เส้นทางที่พิสูจน์แล้วว่าพิมพ์ได้)
     * คู่กับโซน BITMAP ที่รู้ค่าไบต์แน่นอน (0x00 / 0xFF / 0xAA)
     * ดูผลแล้วจะรู้ทันทีว่าเครื่องอ่าน bit 0/1 เป็นสีอะไร
     */
    private fun printDiagnosticPattern() {
        thread {
            try {
                val p = profile80mmContinuous
                val dpm = PrintRenderer.DOTS_PER_MM
                val body = ByteArrayOutputStream()
                fun b(s: String) = body.write(s.toByteArray(Charsets.US_ASCII))
                val zoneW = 24; val zoneH = 48
                var y = p.topMarginMm * dpm
                fun zone(label: String, fill: Byte) {
                    b("TEXT 40,$y,\"TSS24.BF2\",0,1,1,\"$label\"\r\n")
                    y += 40
                    b("BITMAP 40,$y,$zoneW,$zoneH,0,")
                    body.write(ByteArray(zoneW * zoneH) { fill })
                    b("\r\n")
                    y += zoneH + 24
                }
                zone("A:00", 0x00)
                zone("B:FF", 0xFF.toByte())
                zone("C:AA", 0xAA.toByte())
                val totalMm = ceil((y + p.bottomMarginMm * dpm).toDouble() / dpm).toInt()
                val out = ByteArrayOutputStream()
                fun a(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
                a("SIZE ${p.widthMm} mm,$totalMm mm\r\n")
                a("GAP ${p.gapMm},${p.gapOffset}\r\n")
                a("SPEED ${p.speed}\r\n")
                a("DENSITY ${p.density}\r\n")
                a("DIRECTION ${p.direction}\r\n")
                a("REFERENCE 0,0\r\n")
                a("SET PEEL ${onOff(p.peel)}\r\n")
                a("SET CUTTER ${onOff(p.cutter)}\r\n")
                a("SET TEAR ${onOff(p.tear)}\r\n")
                a("CLS\r\n")
                out.write(body.toByteArray())
                a("PRINT 1\r\n")
                log("พิมพ์แพทเทิร์นวินิจฉัย: A=0x00 B=0xFF C=0xAA (สูง ${totalMm}mm)")
                sendBytes(out.toByteArray(), "diagnostic pattern")
            } catch (e: Exception) {
                log("พิมพ์แพทเทิร์นล้มเหลว: ${e.message}")
            }
        }
    }

    /** webhook สั่งพิมพ์: สร้าง span จาก style แล้วใช้ pipeline เดียวกัน */
    private fun webhookPrint(text: String, style: WebhookServer.PrintStyle): WebhookServer.PrintResult {
        if (output == null) return WebhookServer.PrintResult(false, "printer not connected")
        val invert = getSharedPreferences("sydear_printer", MODE_PRIVATE)
            .getBoolean("invert_bitmap", false)
        return try {
            val job = PrintRenderer.render(profile80mmContinuous, buildSpanned(text, style), invert)
                ?: return WebhookServer.PrintResult(false, "render failed")
            job.logLines.forEach { log("[webhook] $it") }
            log("[webhook] สั่งพิมพ์: ${text.take(50)}")
            sendBytes(job.tspl, "webhook print")
            WebhookServer.PrintResult(true, "sent ${job.tspl.size} bytes to printer")
        } catch (e: Exception) {
            WebhookServer.PrintResult(false, e.message ?: "print error")
        }
    }

    private fun buildSpanned(text: String, style: WebhookServer.PrintStyle): Spanned {
        val ss = SpannableString(text)
        val n = text.length
        if (n == 0) return ss
        if (style.bold) ss.setSpan(StyleSpan(Typeface.BOLD), 0, n, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (style.italic) ss.setSpan(StyleSpan(Typeface.ITALIC), 0, n, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (style.underline) ss.setSpan(UnderlineSpan(), 0, n, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val factor = when (style.size.uppercase()) {
            "S" -> 0.75f; "L" -> 1.5f; "XL" -> 2f; else -> null
        }
        if (factor != null) ss.setSpan(RelativeSizeSpan(factor), 0, n, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val align = when (style.align.lowercase()) {
            "center", "กลาง" -> Layout.Alignment.ALIGN_CENTER
            "right", "ขวา" -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        if (align != Layout.Alignment.ALIGN_NORMAL)
            ss.setSpan(AlignmentSpan.Standard(align), 0, n, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return ss
    }

    // ---------- หน้าเครื่องพิมพ์ ----------

    private fun buildPrinterScreen(): LinearLayout {
        val s = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        s.addView(sectionTitle("บลูทูธ"))
        s.addView(TextView(this).apply { text = "Target MAC: $targetMac"; textSize = 14f }, lp())
        deviceSpinner = Spinner(this)
        s.addView(deviceSpinner, lp())
        s.addView(Button(this).apply {
            text = "โหลดเครื่องที่จับคู่ไว้"; setOnClickListener { loadPairedDevices() }
        }, lp())
        connectButton = Button(this).apply {
            text = "เชื่อมต่อ"; setOnClickListener { connectSelected() }
        }
        s.addView(connectButton, lp())

        s.addView(sectionTitle("โปรไฟล์เครื่องพิมพ์"))
        s.addView(TextView(this).apply {
            text = "80mm Continuous Thermal\nกว้าง 80mm / สูงอัตโนมัติตามเนื้อหา\nGAP 0 / Speed 8 / Density 15"
            textSize = 14f
        }, lp())

        s.addView(sectionTitle("Webhook — สั่งพิมพ์ผ่านเน็ตเวิร์ก"))
        webhookToggle = Button(this).apply {
            text = "เปิด Webhook"; setOnClickListener { toggleWebhook() }
        }
        s.addView(webhookToggle, lp())
        webhookKeyView = TextView(this).apply { textSize = 14f; setTextIsSelectable(true) }
        s.addView(webhookKeyView, lp())
        s.addView(Button(this).apply {
            text = "สุ่มคีย์ใหม่"; setOnClickListener { regenerateWebhookKey() }
        }, lp())
        webhookInfo = TextView(this).apply { textSize = 13f; setTextIsSelectable(true) }
        s.addView(webhookInfo, lp())

        s.addView(sectionTitle("อัพเดทแอป"))
        updateButton = Button(this).apply {
            text = "เช็คอัพเดท"; setOnClickListener { checkForUpdate(manual = true) }
        }
        s.addView(updateButton, lp())
        installButton = Button(this).apply {
            text = "ติดตั้งไฟล์ที่โหลดไว้"; setOnClickListener { installApk() }
        }
        s.addView(installButton, lp())
        // กันปุ่มสุดท้ายชิดขอบล่างเกินไป
        s.addView(View(this).apply { minimumHeight = 40 }, lp())
        return s
    }

    private fun toggleWebhook() {
        val srv = webhookServer
        if (srv?.isRunning == true) {
            srv.stop()
            webhookToggle.text = "เปิด Webhook"
        } else {
            val s = WebhookServer(webhookPort, webhookKey, ::webhookPrint, ::webhookStatus, ::log)
            if (s.start()) {
                webhookServer = s
                webhookToggle.text = "ปิด Webhook"
            }
        }
        updateWebhookInfo()
    }

    private fun webhookStatus(): JSONObject {
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" }
                catch (_: Exception) { "?" }
        return JSONObject()
            .put("connected", output != null)
            .put("printer", "ES-9910UB")
            .put("printer_mac", targetMac)
            .put("version", v)
    }

    private fun updateWebhookInfo() {
        val running = webhookServer?.isRunning == true
        webhookKeyView.text = "คีย์: $webhookKey"
        val sb = StringBuilder()
        if (running) {
            sb.append("สถานะ: เปิดอยู่ ✅\n")
            for (ip in localIpAddresses()) sb.append("http://$ip:$webhookPort/print\n")
            sb.append("\nPOST /print ด้วย JSON:\n")
            sb.append("{\"text\":\"สวัสดี\",\"bold\":true,\"size\":\"L\",\"align\":\"center\"}\n")
            sb.append("แนบคีย์ทาง ?key= หรือ header X-Webhook-Key\n")
            sb.append("GET /status — เช็คสถานะเครื่องพิมพ์")
        } else {
            sb.append("สถานะ: ปิดอยู่ — เปิดแล้วอุปกรณ์ในเน็ตเวิร์กเดียวกัน\n")
            sb.append("(หรือผ่าน Tailscale) จะสั่งพิมพ์ได้")
        }
        webhookInfo.text = sb.toString()
    }

    private fun localIpAddresses(): List<String> {
        val out = mutableListOf<String>()
        try {
            val ifs = java.net.NetworkInterface.getNetworkInterfaces()
            while (ifs.hasMoreElements()) {
                val ni = ifs.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (!a.isLoopbackAddress && a is java.net.Inet4Address)
                        out.add("${a.hostAddress} (${ni.name})")
                }
            }
        } catch (_: Exception) {}
        return out.ifEmpty { listOf("<ไม่พบ IP>") }
    }

    private fun loadOrCreateWebhookKey(): String {
        val prefs = getSharedPreferences("sydear_printer", MODE_PRIVATE)
        val k = prefs.getString("webhook_key", null)
        if (!k.isNullOrBlank()) return k
        val nk = UUID.randomUUID().toString().replace("-", "").take(16)
        prefs.edit().putString("webhook_key", nk).apply()
        return nk
    }

    private fun regenerateWebhookKey() {
        val k = UUID.randomUUID().toString().replace("-", "").take(16)
        getSharedPreferences("sydear_printer", MODE_PRIVATE).edit().putString("webhook_key", k).apply()
        webhookKey = k
        webhookServer?.setApiKey(k)
        updateWebhookInfo()
        log("สุ่มคีย์ webhook ใหม่แล้ว")
    }

    // ---------- หน้า Log ----------

    private fun buildLogScreen(): LinearLayout {
        val s = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        s.addView(Button(this).apply {
            text = "ล้าง Log"; setOnClickListener { logView.text = "" }
        }, lp())
        logView = TextView(this).apply {
            textSize = 13f; setTextIsSelectable(true); text = "รอเริ่ม…\n"
        }
        s.addView(ScrollView(this).apply { addView(logView) }, fill())
        return s
    }

    // ================= Self-update =================

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

    // ================= Bluetooth =================

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
                    printButton.isEnabled = true
                    quickTestButton.isEnabled = true
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
        if (::printButton.isInitialized) printButton.isEnabled = false
        if (::quickTestButton.isInitialized) quickTestButton.isEnabled = false
        log("Disconnected")
    }

    // ================= Printer Profile: 80mm Continuous Thermal =================
    // โปรไฟล์กระดาษต่อเนื่อง 80mm — ความสูงกระดาษ "ไม่ตายตัว"
    // ระบบจะคำนวณจาก bounding box จริงของเนื้อหา:
    //   TOTAL = top margin (10mm) + CONTENT_HEIGHT + bottom margin (10mm)
    data class PrinterProfile(
        val name: String,
        val widthMm: Int,
        val media: String,      // Continuous
        val gapMm: Int,         // 0 = กระดาษต่อเนื่อง ไม่มีช่องว่าง
        val gapOffset: Int,
        val speed: Int,         // 8
        val density: Int,       // 15
        val direction: Int,     // 0
        val topMarginMm: Int,    // 10
        val bottomMarginMm: Int, // 10
        val peel: Boolean,      // false = OFF
        val cutter: Boolean,    // false = OFF
        val tear: Boolean       // false = OFF
    )

    private val profile80mmContinuous = PrinterProfile(
        name = "80mm Continuous Thermal",
        widthMm = 80,
        media = "Continuous",
        gapMm = 0,
        gapOffset = 0,
        speed = 8,
        density = 15,
        direction = 0,
        topMarginMm = 10,
        bottomMarginMm = 10,
        peel = false,
        cutter = false,
        tear = false
    )

    private fun onOff(b: Boolean) = if (b) "ON" else "OFF"

    /**
     * สร้างคำสั่ง TSPL ทั้งงานพิมพ์: คำนวณความสูงอัตโนมัติจากเนื้อหา
     * @param buildContent lambda ที่ใช้ TsplLabel วางเนื้อหา (renderer)
     */
    private fun buildAutoHeightTspl(p: PrinterProfile, buildContent: TsplLabel.() -> Unit): String {
        val dpm = TsplLabel.DOTS_PER_MM
        val label = TsplLabel()
        label.start(p.topMarginMm * dpm)
        label.buildContent()

        val contentDots = label.contentHeightDots()
        val totalDots = p.topMarginMm * dpm + contentDots + p.bottomMarginMm * dpm
        // ปัดขึ้นเป็น mm เต็มเสมอ (กันเนื้อหาโดนตัดที่ขอบ) — ไม่เติม padding อื่นเพิ่ม
        val totalMm = ceil(totalDots.toDouble() / dpm).toInt()
        val contentMm = label.contentHeightMm()

        log("TSPL AUTO HEIGHT")
        log("Content: ${"%.1f".format(contentMm)} mm")
        log("Top: ${p.topMarginMm} mm")
        log("Bottom: ${p.bottomMarginMm} mm")
        log("Total: $totalMm mm")
        log("Speed: ${p.speed}")
        log("Density: ${p.density}")

        return buildString {
            append("SIZE ${p.widthMm} mm,$totalMm mm\r\n")
            append("GAP ${p.gapMm},${p.gapOffset}\r\n")
            append("SPEED ${p.speed}\r\n")
            append("DENSITY ${p.density}\r\n")
            append("DIRECTION ${p.direction}\r\n")
            append("REFERENCE 0,0\r\n")
            append("SET PEEL ${onOff(p.peel)}\r\n")
            append("SET CUTTER ${onOff(p.cutter)}\r\n")
            append("SET TEAR ${onOff(p.tear)}\r\n")
            append("CLS\r\n")
            append(label.contentTspl())
            append("PRINT 1\r\n")
        }
    }

    /** ทดสอบด่วน: เส้นทาง TSPL TEXT ที่ยืนยันแล้วว่าพิมพ์ออกบนเครื่องจริง */
    private fun sendTsplContinuousTest() {
        val p = profile80mmContinuous
        log("ใช้โปรไฟล์: ${p.name}")
        val tspl = buildAutoHeightTspl(p) {
            addTextLine("SYDEAR TSPL TEST")
            addTextLine("ES-9910UB")
            addTextLine("80mm CONTINUOUS")
            addTextLine("SPEED 8")
            addTextLine("DENSITY 15")
            addTextLine("GAP 0")
        }
        sendBytes(tspl.toByteArray(Charsets.US_ASCII), "TSPL 80mm CONTINUOUS AUTO")
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

    override fun onDestroy() {
        try { webhookServer?.stop() } catch (_: Exception) {}
        disconnect()
        super.onDestroy()
    }
}
