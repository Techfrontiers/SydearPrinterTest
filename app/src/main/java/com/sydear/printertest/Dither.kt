package com.sydear.printertest

/**
 * แปลงรูปภาพเป็น 1-bit สำหรับเครื่องพิมพ์ความร้อน
 * ใช้ Floyd–Steinberg dithering แบบ serpentine (สลับทิศทุกแถว)
 * ให้ลายจุดแบบหนังสือพิมพ์ — รูปถ่ายออกมาดูดีกว่า threshold ธรรมดาเยอะ
 *
 * ไฟล์นี้ไม่มี dependency ของ Android เลย เทสบน JVM ได้ตรงๆ
 * bit 1 = จุดดำ, MSB = พิกเซลซ้ายสุด (แบบเดียวกับ renderBitmap)
 */
object Dither {
    data class DitherResult(val widthBytes: Int, val heightDots: Int, val data: ByteArray)

    /**
     * @param pixels พิกเซล ARGB เรียงแถวละ w ตัว, ครบ w*h ตัว
     * @param invert true = XOR ทั้งบัฟเฟอร์ (สำหรับเครื่องที่อ่าน 0=ดำ แบบ ES-9910UB)
     */
    fun floydSteinberg(pixels: IntArray, w: Int, h: Int, invert: Boolean): DitherResult {
        require(w > 0 && h > 0 && pixels.size == w * h) { "bad image size" }
        val gray = FloatArray(w * h) { i ->
            val p = pixels[i]
            ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000f
        }
        val rowBytes = (w + 7) / 8
        val mono = ByteArray(rowBytes * h)
        for (y in 0 until h) {
            val ltr = y % 2 == 0
            for (xi in 0 until w) {
                val x = if (ltr) xi else w - 1 - xi
                val i = y * w + x
                val old = gray[i].coerceIn(0f, 255f)
                val nv = if (old < 128f) 0f else 255f
                gray[i] = nv
                if (nv == 0f) {
                    val o = y * rowBytes + x / 8
                    mono[o] = (mono[o].toInt() or (0x80 shr (x % 8))).toByte()
                }
                val err = old - nv
                val xa = if (ltr) x + 1 else x - 1   // พิกเซลถัดไปในทิศที่เดิน
                val xb = if (ltr) x - 1 else x + 1   // พิกเซลก่อนหน้า
                if (xa in 0 until w) gray[y * w + xa] += err * 7f / 16f
                if (y + 1 < h) {
                    if (xb in 0 until w) gray[(y + 1) * w + xb] += err * 3f / 16f
                    gray[(y + 1) * w + x] += err * 5f / 16f
                    if (xa in 0 until w) gray[(y + 1) * w + xa] += err * 1f / 16f
                }
            }
        }
        if (invert) for (i in mono.indices) mono[i] = (mono[i].toInt() xor 0xFF).toByte()
        return DitherResult(rowBytes, h, mono)
    }
}
