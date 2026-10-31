package dev.scrcpy.update

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Read-only FileProvider limited to the single private, verified release APK. */
class UpdateFileProvider : ContentProvider() {
    private fun file(uri: Uri): File {
        val app = context ?: throw FileNotFoundException("Update file unavailable")
        if (uri.scheme != "content" || uri.authority != "${app.packageName}.updates" ||
            uri.path != "/verified.apk" || uri.query != null || uri.fragment != null)
            throw FileNotFoundException("Unknown update file")
        return File(File(app.cacheDir, "release_update"), "verified.apk").also {
            if (!it.isFile) throw FileNotFoundException("Update file unavailable")
        }
    }
    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
            ?: throw FileNotFoundException("Update file unavailable")
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = try {
        val file = file(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        MatrixCursor(columns).apply {
            addRow(columns.map { when (it) {
                OpenableColumns.DISPLAY_NAME -> "update.apk"
                OpenableColumns.SIZE -> file.length()
                else -> null
            } })
        }
    } catch (_: FileNotFoundException) { null }
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
