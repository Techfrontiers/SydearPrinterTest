package com.sydear.printertest

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * เสิร์ฟไฟล์รูปถ่ายให้แอปกล้อง (ACTION_IMAGE_CAPTURE + EXTRA_OUTPUT)
 * เขียนได้อย่างเดียว เฉพาะไฟล์ .jpg ในโฟลเดอร์ Pictures ของแอปเท่านั้น
 * (ไม่ต้องขอ permission กล้อง/สตอเรจ เพราะใช้ intent ของระบบ + โฟลเดอร์ของแอปเอง)
 */
class PhotoProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun photoFile(name: String): File {
        require("/" !in name && ".." !in name && name.endsWith(".jpg")) { "bad file name" }
        val dir = context!!.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: throw FileNotFoundException("no pictures dir")
        return File(dir, name)
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? {
        return try {
            val f = photoFile(uri.lastPathSegment ?: return null)
            if (!f.isFile) return null
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), 1).apply {
                addRow(arrayOf<Any>(f.name, f.length()))
            }
        } catch (_: Exception) { null }
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = photoFile(uri.lastPathSegment ?: throw FileNotFoundException("bad uri"))
        val pfdMode = if ('w' in mode)
            ParcelFileDescriptor.MODE_WRITE_ONLY or
                    ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE
        else ParcelFileDescriptor.MODE_READ_ONLY
        return ParcelFileDescriptor.open(f, pfdMode)
    }
}
