package app.talevane.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.util.zip.ZipInputStream

internal object BookFileImporter {
    fun import(context: Context, uri: Uri): ImportedBook {
        val name = displayName(context, uri)
        val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val isDocx = SupportedBookFiles.isDeclaredDocx(name, mimeType) ||
            (SupportedBookFiles.shouldInspectAsPossibleDocx(name, mimeType) &&
                containsDocxDocument(context, uri))

        return when {
            isDocx -> DocxImporter.import(context, uri, name)
            SupportedBookFiles.isLegacyDoc(name, mimeType) -> error(
                "Este archivo usa el formato Word antiguo (.doc). " +
                    "Talevane puede leer documentos Word guardados como .docx."
            )
            else -> Importers.import(context, uri)
        }
    }

    /**
     * Verifies the OOXML package instead of trusting MIME metadata supplied by the file manager.
     * This is needed on older Huawei/Android providers that report a DOCX as application/msword.
     */
    private fun containsDocxDocument(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
            ZipInputStream(input).use { zip ->
                var hasContentTypes = false
                var hasWordDocument = false
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when (entry.name.lowercase()) {
                        "[content_types].xml" -> hasContentTypes = true
                        "word/document.xml" -> hasWordDocument = true
                    }
                    if (hasContentTypes && hasWordDocument) return@use true
                    zip.closeEntry()
                }
                hasContentTypes && hasWordDocument
            }
        } ?: false
    }.getOrDefault(false)

    private fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        return uri.lastPathSegment ?: "Libro"
    }
}
