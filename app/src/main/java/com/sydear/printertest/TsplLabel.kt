package com.sydear.printertest

import kotlin.math.ceil

/**
 * ตัววางเลย์เอาต์ฉลาก TSPL แบบคำนวณความสูงอัตโนมัติ
 *
 * หลักการ: renderer วาง element ทีละชิ้น (ตอนนี้รองรับ TEXT) แล้วจด bounding box
 * จริงของแต่ละชิ้นไว้ (ขอบบน / ขอบล่าง) — ความสูงเนื้อหา = กล่องสี่เหลี่ยมที่ครอบ
 * ทุกชิ้นพอดี ไม่ใช่การเดาจากจำนวนบรรทัด
 *
 * หน่วยภายใน = dot ; 8 dots = 1 mm (หัวพิมพ์ 203 DPI)
 */
class TsplLabel {
    companion object {
        const val DOTS_PER_MM = 8
    }

    private data class PlacedBox(val top: Int, val bottom: Int)

    private val boxes = mutableListOf<PlacedBox>()
    private val body = StringBuilder()
    private var cursorY = 0

    /** เริ่มวางเนื้อหาที่ระยะ top margin (หน่วย dot) */
    fun start(topMarginDots: Int) {
        cursorY = topMarginDots
    }

    /**
     * วางข้อความ 1 บรรทัด แล้วเลื่อน cursor ลง
     * @param fontDots ความสูงฟอนต์ (dot) เช่น TSS24.BF2 = 24
     * @param lineGapDots ช่องว่างระหว่างบรรทัด — ไม่นับเป็นส่วนของบรรทัดสุดท้าย
     *                    (เลยไม่มีความสูงส่วนเกินต่อท้าย content)
     */
    fun addTextLine(
        text: String,
        xDots: Int = 30,
        font: String = "TSS24.BF2",
        scaleX: Int = 1,
        scaleY: Int = 1,
        fontDots: Int = 24,
        lineGapDots: Int = 16
    ) {
        val safe = text.replace("\"", "")
        val h = fontDots * scaleY
        body.append("TEXT $xDots,$cursorY,\"$font\",0,$scaleX,$scaleY,\"$safe\"\r\n")
        boxes.add(PlacedBox(cursorY, cursorY + h))
        cursorY += h + lineGapDots
    }

    /** ความสูงเนื้อหาจริง (dot) = กล่องที่ครอบทุก element พอดี */
    fun contentHeightDots(): Int {
        if (boxes.isEmpty()) return 0
        return boxes.maxOf { it.bottom } - boxes.minOf { it.top }
    }

    /** ความสูงเนื้อหา (mm) แบบทศนิยม */
    fun contentHeightMm(): Double = contentHeightDots().toDouble() / DOTS_PER_MM

    fun contentTspl(): String = body.toString()
}
