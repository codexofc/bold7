package dev.bold7.messages

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * The files attached in Messages' field, read only, as
 * content://dev.bold7.messages.attachments/<uuid>/<name>: Messages' copies in its cache
 * (AndroidAttachmentStore). Not exported: only a grant for one URI lets another app in, given to
 * Google Messages for the SendMessage call that sends it (GrpcMessagingApi). Nothing is written
 * through it.
 */
class AttachmentProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "dev.bold7.messages.attachments"
        private val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /** Where the copies live: one folder per attachment. */
        fun root(context: Context) = File(context.cacheDir, "attach")

        fun uri(id: String, name: String): Uri = Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(id).appendPath(name).build()

        /** [uri]'s file, only one of ours ([ID]/a safe name); null for anything else. */
        fun file(context: Context, uri: Uri): File? {
            if (uri.authority != AUTHORITY) return null
            val segments = uri.pathSegments
            if (segments.size != 2) return null
            val (id, name) = segments
            if (!ID.matches(id) || safeName(name) != name) return null
            return File(File(root(context), id), name)
        }
    }

    override fun onCreate() = true

    private fun existing(uri: Uri): File = context?.let { file(it, uri) }?.takeIf { it.isFile } ?: throw FileNotFoundException("no attachment")

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        return ParcelFileDescriptor.open(existing(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? =
        context?.let { file(it, uri) }?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.extension.lowercase()) }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, order: String?): Cursor {
        val f = existing(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(columns.map { if (it == OpenableColumns.SIZE) f.length() else if (it == OpenableColumns.DISPLAY_NAME) f.name else null })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int = throw UnsupportedOperationException()
}
