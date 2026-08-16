package app.talevane.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

internal object BookFileImporter {
    private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    fun import(context: Context, uri: Uri): ImportedBook {
        val name = displayName(context, uri)
        val extension = name.substringAfterLast('.', "").lowercase()
        val mimeType = context.contentResolver.getType(uri)

        return if (extension == "docx" || mimeType == DOCX_MIME) {
            DocxImporter.import(context, uri, name)
        } else {
            Importers.import(context, uri)
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return uri.lastPathSegment ?: "Libro"
    }
}
