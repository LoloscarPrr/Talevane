package app.talevane.reader.library

import app.talevane.reader.data.BookEntity

data class BookPresentation(
    val title: String,
    val author: String
)

object BookPresenter {
    fun present(book: BookEntity): BookPresentation {
        var title = cleanTitle(book.title)
        var author = book.author.trim().ifBlank { "Autor desconocido" }

        if (author.equals("Autor desconocido", ignoreCase = true)) {
            parseAuthorPrefix(title)?.let { parsed ->
                author = parsed.first
                title = parsed.second
            }
        }

        return BookPresentation(
            title = title.ifBlank { "Libro" },
            author = author.ifBlank { "Autor desconocido" }
        )
    }

    fun cleanTitle(value: String): String {
        var result = value
            .replace(Regex("[_-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        result = result.replace(
            Regex("(?i)\\s+(copia|copy)(?:\\s*\\(\\d+\\))?$"),
            ""
        ).trim()

        if (result.length > 1 && result.count { it.isUpperCase() } <= 1) {
            result = result.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
        return result
    }

    private fun parseAuthorPrefix(value: String): Pair<String, String>? {
        val match = Regex(
            "^([^,]{2,45}),\\s*([A-ZÁÉÍÓÚÜÑ][a-záéíóúüñA-ZÁÉÍÓÚÜÑ.'-]{1,30})\\s+(.{3,})$"
        ).find(value) ?: return null

        val surname = match.groupValues[1].trim()
        val firstName = match.groupValues[2].trim()
        val title = cleanTitle(match.groupValues[3])
        if (surname.split(' ').size > 5 || title.isBlank()) return null

        return "$firstName $surname" to title
    }
}
