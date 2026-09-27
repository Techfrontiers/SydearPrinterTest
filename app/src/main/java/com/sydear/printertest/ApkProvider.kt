package com.sydear.printertest

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * เสิร์ฟไฟล์ APK อัพเดทให้ตัวติดตั้งของระบบ (แทน FileProvider ของ androidx
 * ที่โปรเจกต์นี้ไม่ได้ใช้) — อ่านได้อย่างเดียว เฉพาะไฟล์ .apk ในโฟลเดอร์
 * ดาวน์โหลดของแอปเท่านั้น
 */
class ApkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val name = uri.lastPathSegment ?: throw FileNotFoundException("bad uri")
        require("/" !in name && ".." !in name && name.endsWith(".apk")) { "bad file name" }
        val dir = context!!.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw FileNotFoundException("no download dir")
        val f = File(dir, name)
        if (!f.isFile) throw FileNotFoundException(name)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }
}
