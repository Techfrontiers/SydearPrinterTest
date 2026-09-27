package com.sydear.printertest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream
import kotlin.math.ceil

/**
 * เรนเดอร์ข้อความ (รองรับตัวหนา/เอียง/ขีดเส้นใต้/ขนาด/จัดแนว + ภาษาไทย)
 * เป็นบิตแมป 1-bit แล้วห่อเป็นคำสั่ง TSPL BITMAP
 *
 * ใช้ text layout ของ Android วาดลง bitmap ตรงๆ — ได้ bounding box จริงจาก
 * layout.height ไม่ต้องเดาความสูง ความสูงกระดาษคำนวณอัตโนมัติ:
 *   TOTAL = top margin + ความสูงบิตแมป + bottom margin
 *
 * สเปก BITMAP (ตามคู่มือ TSC TSPL2):
 *   BITMAP X,Y,width,height,mode,data…
 *   - width หน่วยเป็น byte, height หน่วยเป็น dot
 *   - binary ต่อท้าย comma ทันที (ไม่มี CRLF คั่น)
 *   - bit 1 = จุดดำ, MSB มาก่อน
 */
object PrintRenderer {
    const val DOTS_PER_MM = 8
    const val PAPER_WIDTH_DOTS = 80 * DOTS_PER_MM       // 640 dots
    const val SIDE_MARGIN_DOTS = 16                     // ขอบซ้าย/ขวา 2 mm
    const val CONTENT_WIDTH_DOTS = PAPER_WIDTH_DOTS - SIDE_MARGIN_DOTS * 2
    const val BASE_TEXT_DOTS = 30f                      // ขนาดพื้นฐาน ~3.75 mm
    const val LINE_SPACING_DOTS = 8f

    data class RenderedJob(
        val tspl: ByteArray,
        val logLines: List<String>,
        val contentMm: Double,
        val totalMm: Int,
        val widthDots: Int,
        val heightDots: Int
    )

    /** คืน null ถ้าไม่มีข้อความให้พิมพ์ */
    fun render(profile: MainActivity.PrinterProfile, spanned: Spanned): RenderedJob? {
        if (spanned.isNullOrBlank()) return null

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = BASE_TEXT_DOTS
        }
        val layout = if (Build.VERSION.SDK_INT >= 23) {
            StaticLayout.Builder.obtain(spanned, 0, spanned.length, paint, CONTENT_WIDTH_DOTS)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(LINE_SPACING_DOTS, 1f)
                .setIncludePad(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(spanned, 0, spanned.length, paint, CONTENT_WIDTH_DOTS,
                Layout.Alignment.ALIGN_NORMAL, 1f, LINE_SPACING_DOTS, true)
        }

        val w = CONTENT_WIDTH_DOTS
        val h = layout.height.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            layout.draw(this)
        }

        // → 1-bit: bit 1 = ดำ, MSB = พิกเซลซ้ายสุด
        val rowBytes = (w + 7) / 8
        val mono = ByteArray(rowBytes * h)
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        bmp.recycle()
        for (y in 0 until h) {
            val rowOff = y * w
            val outOff = y * rowBytes
            for (x in 0 until w) {
                val px = pixels[rowOff + x]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                if ((r * 299 + g * 587 + b * 114) / 1000 < 128) {
                    val i = outOff + x / 8
                    mono[i] = (mono[i].toInt() or (0x80 shr (x % 8))).toByte()
                }
            }
        }
        // กลับขั้ว: ทดสอบบน ES-9910UB จริงพบว่าเครื่องอ่าน bit กลับด้าน
        // (0=พิมพ์ดำ, 1=ไม่พิมพ์) ไม่ตรงคู่มือ — XOR ทั้งบัฟเฟอร์ให้พื้นขาวไม่พิมพ์
        // (bit padding ขอบขวากลายเป็น 1 = ไม่พิมพ์ ถูกต้องแล้ว)
        for (i in mono.indices) mono[i] = (mono[i].toInt() xor 0xFF).toByte()

        val dpm = DOTS_PER_MM
        val topDots = profile.topMarginMm * dpm
        val bottomDots = profile.bottomMarginMm * dpm
        val totalMm = ceil((topDots + h + bottomDots).toDouble() / dpm).toInt()
        val contentMm = h.toDouble() / dpm

        val out = ByteArrayOutputStream()
        fun a(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun onOff(b: Boolean) = if (b) "ON" else "OFF"
        a("SIZE ${profile.widthMm} mm,$totalMm mm\r\n")
        a("GAP ${profile.gapMm},${profile.gapOffset}\r\n")
        a("SPEED ${profile.speed}\r\n")
        a("DENSITY ${profile.density}\r\n")
        a("DIRECTION ${profile.direction}\r\n")
        a("REFERENCE 0,0\r\n")
        a("SET PEEL ${onOff(profile.peel)}\r\n")
        a("SET CUTTER ${onOff(profile.cutter)}\r\n")
        a("SET TEAR ${onOff(profile.tear)}\r\n")
        a("CLS\r\n")
        a("BITMAP $SIDE_MARGIN_DOTS,$topDots,$rowBytes,$h,0,")
        out.write(mono)
        a("\r\nPRINT 1\r\n")

        val logs = listOf(
            "TSPL AUTO HEIGHT",
            "Content: ${"%.1f".format(contentMm)} mm",
            "Top: ${profile.topMarginMm} mm",
            "Bottom: ${profile.bottomMarginMm} mm",
            "Total: $totalMm mm",
            "Speed: ${profile.speed}",
            "Density: ${profile.density}",
            "Bitmap: ${w}x${h} dots"
        )
        return RenderedJob(out.toByteArray(), logs, contentMm, totalMm, w, h)
    }
}
