package app.talevane.reader.data

/**
 * File types accepted by Talevane's picker and importer.
 *
 * Some older Android file managers label modern .docx files as application/msword,
 * application/zip or application/octet-stream. Those aliases are intentionally offered by
 * the picker; the importer still validates the file contents before treating one as DOCX.
 */
internal object SupportedBookFiles {
    const val DOCX_MIME =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val LEGACY_WORD_MIME = "application/msword"
    const val GENERIC_BINARY_MIME = "application/octet-stream"
    const val ZIP_MIME = "application/zip"

    val pickerMimeTypes: Array<String>
        get() = arrayOf(
            "text/plain",
            "application/pdf",
            "application/epub+zip",
            "application/epub",
            DOCX_MIME,
            LEGACY_WORD_MIME,
            GENERIC_BINARY_MIME,
            ZIP_MIME
        )

    fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").trim().lowercase()

    fun isDeclaredDocx(name: String, mimeType: String?): Boolean =
        extensionOf(name) == "docx" || mimeType.equals(DOCX_MIME, ignoreCase = true)

    fun shouldInspectAsPossibleDocx(name: String, mimeType: String?): Boolean {
        val extension = extensionOf(name)
        return extension == "doc" ||
            extension.isBlank() ||
            mimeType.equals(LEGACY_WORD_MIME, ignoreCase = true) ||
            mimeType.equals(GENERIC_BINARY_MIME, ignoreCase = true) ||
            mimeType.equals(ZIP_MIME, ignoreCase = true)
    }

    fun isLegacyDoc(name: String, mimeType: String?): Boolean =
        extensionOf(name) == "doc" || mimeType.equals(LEGACY_WORD_MIME, ignoreCase = true)
}
