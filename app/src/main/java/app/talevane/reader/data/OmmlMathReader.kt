package app.talevane.reader.data

import app.talevane.reader.language.BookLanguage
import org.w3c.dom.Node

/**
 * Converts native Word OMML equations into speech-friendly text in the book language.
 *
 * Talevane is primarily an audio reader, so this deliberately preserves mathematical meaning rather
 * than attempting to reproduce Word's visual equation layout. Unknown OMML constructs fall back to
 * their readable descendants instead of dropping the formula.
 */
internal object OmmlMathReader {
    fun read(node: Node, language: BookLanguage = BookLanguage.SPANISH): String =
        Renderer(language).read(node)

    private class Renderer(private val language: BookLanguage) {
        fun read(node: Node): String = normalize(render(node))

        private fun render(node: Node): String = when (node.localName) {
            "oMath", "oMathPara" -> joinChildren(node)
            "r" -> joinChildren(node)
            "t" -> verbalizePlainMath(node.textContent.orEmpty())

            "f" -> {
                val numerator = directChild(node, "num")?.let(::renderContent).orEmpty()
                val denominator = directChild(node, "den")?.let(::renderContent).orEmpty()
                phrase(term("fracción", "fraction"), numerator, term("sobre", "over"), denominator)
            }

            "sSup" -> {
                val base = directChild(node, "e")?.let(::renderContent).orEmpty()
                val exponent = directChild(node, "sup")?.let(::renderContent).orEmpty()
                when (normalize(exponent)) {
                    "2" -> phrase(base, term("al cuadrado", "squared"))
                    "3" -> phrase(base, term("al cubo", "cubed"))
                    else -> phrase(base, term("elevado a", "to the power of"), exponent)
                }
            }

            "sSub" -> {
                val base = directChild(node, "e")?.let(::renderContent).orEmpty()
                val subscript = directChild(node, "sub")?.let(::renderContent).orEmpty()
                phrase(base, term("subíndice", "subscript"), subscript)
            }

            "sSubSup" -> {
                val base = directChild(node, "e")?.let(::renderContent).orEmpty()
                val subscript = directChild(node, "sub")?.let(::renderContent).orEmpty()
                val exponent = directChild(node, "sup")?.let(::renderContent).orEmpty()
                phrase(
                    base,
                    term("subíndice", "subscript"),
                    subscript,
                    term("elevado a", "to the power of"),
                    exponent
                )
            }

            "rad" -> {
                val degree = directChild(node, "deg")?.let(::renderContent).orEmpty()
                val expression = directChild(node, "e")?.let(::renderContent).orEmpty()
                if (degree.isBlank() || normalize(degree) == "2") {
                    phrase(term("raíz cuadrada de", "square root of"), expression)
                } else {
                    phrase(
                        term("raíz de índice", "root with index"),
                        degree,
                        term("de", "of"),
                        expression
                    )
                }
            }

            "nary" -> renderNary(node)

            "limLow" -> {
                val expression = directChild(node, "e")?.let(::renderContent).orEmpty()
                val limit = directChild(node, "lim")?.let(::renderContent).orEmpty()
                phrase(expression, term("con límite inferior", "with lower limit"), limit)
            }

            "limUpp" -> {
                val expression = directChild(node, "e")?.let(::renderContent).orEmpty()
                val limit = directChild(node, "lim")?.let(::renderContent).orEmpty()
                phrase(expression, term("con límite superior", "with upper limit"), limit)
            }

            "func" -> {
                val name = directChild(node, "fName")?.let(::renderContent).orEmpty()
                val argument = directChild(node, "e")?.let(::renderContent).orEmpty()
                phrase(name, term("de", "of"), argument)
            }

            "d" -> {
                val start = descendantAttribute(node, "begChr", "val").orEmpty().ifBlank { "(" }
                val end = descendantAttribute(node, "endChr", "val").orEmpty().ifBlank { ")" }
                val expressions = directChildren(node, "e").map(::renderContent).filter { it.isNotBlank() }
                phrase(
                    delimiterName(start, opening = true),
                    expressions.joinToString("; "),
                    delimiterName(end, opening = false)
                )
            }

            "m" -> renderMatrix(node)

            "acc" -> {
                val accent = descendantAttribute(node, "chr", "val").orEmpty()
                val expression = directChild(node, "e")?.let(::renderContent).orEmpty()
                when (accent) {
                    "^", "ˆ" -> phrase(expression, term("con acento circunflejo", "with circumflex accent"))
                    "¯", "̅" -> phrase(expression, term("con barra superior", "with overbar"))
                    "→", "⃗" -> phrase(term("vector", "vector"), expression)
                    else -> phrase(
                        expression,
                        term("con acento", "with accent"),
                        verbalizePlainMath(accent)
                    )
                }
            }

            "bar" -> {
                val expression = directChild(node, "e")?.let(::renderContent).orEmpty()
                phrase(expression, term("con barra", "with bar"))
            }

            "groupChr" -> directChild(node, "e")?.let(::renderContent).orEmpty()
            "box", "borderBox", "phant" -> directChild(node, "e")?.let(::renderContent).orEmpty()
            "eqArr" -> directChildren(node, "e").joinToString("; ") { renderContent(it) }

            // Property/control nodes describe layout and are not mathematical operands.
            "oMathParaPr", "ctrlPr", "fPr", "sSupPr", "sSubPr", "sSubSupPr", "radPr",
            "naryPr", "limLowPr", "limUppPr", "funcPr", "dPr", "mPr", "mrPr", "accPr",
            "barPr", "groupChrPr", "boxPr", "borderBoxPr", "phantPr", "eqArrPr", "rPr" -> ""

            else -> joinChildren(node)
        }

        private fun renderNary(node: Node): String {
            val symbol = descendantAttribute(directChild(node, "naryPr"), "chr", "val")
                .orEmpty()
                .ifBlank { "∫" }
            val lower = directChild(node, "sub")?.let(::renderContent).orEmpty()
            val upper = directChild(node, "sup")?.let(::renderContent).orEmpty()
            val expression = directChild(node, "e")?.let(::renderContent).orEmpty()

            val operator = when (symbol) {
                "∑" -> term("sumatoria", "summation")
                "∏" -> term("productoria", "product")
                "∐" -> term("coproducto", "coproduct")
                "∫" -> term("integral", "integral")
                "∬" -> term("integral doble", "double integral")
                "∭" -> term("integral triple", "triple integral")
                "∮" -> term("integral de contorno", "contour integral")
                else -> verbalizePlainMath(symbol)
            }

            return when {
                lower.isNotBlank() && upper.isNotBlank() ->
                    phrase(
                        operator,
                        term("desde", "from"),
                        lower,
                        term("hasta", "to"),
                        upper,
                        term("de", "of"),
                        expression
                    )
                lower.isNotBlank() ->
                    phrase(operator, term("desde", "from"), lower, term("de", "of"), expression)
                upper.isNotBlank() ->
                    phrase(operator, term("hasta", "to"), upper, term("de", "of"), expression)
                else -> phrase(operator, term("de", "of"), expression)
            }
        }

        private fun renderMatrix(node: Node): String {
            val rows = directChildren(node, "mr")
            if (rows.isEmpty()) return joinChildren(node)

            return rows.mapIndexed { rowIndex, row ->
                val cells = directChildren(row, "e").map(::renderContent)
                val empty = term("vacío", "empty")
                "${term("fila", "row")} ${rowIndex + 1}: ${cells.joinToString(", ") { it.ifBlank { empty } }}"
            }.joinToString(
                "; ",
                prefix = "${term("matriz", "matrix")}, ",
                postfix = ", ${term("fin de matriz", "end matrix")}"
            )
        }

        private fun renderContent(node: Node): String = normalize(joinChildren(node))

        private fun joinChildren(node: Node): String {
            val out = StringBuilder()
            val children = node.childNodes
            for (index in 0 until children.length) {
                val rendered = render(children.item(index))
                if (rendered.isBlank()) continue
                if (out.isNotEmpty() && needsSpace(out.last(), rendered.first())) out.append(' ')
                out.append(rendered)
            }
            return out.toString()
        }

        private fun directChild(node: Node, localName: String): Node? {
            val children = node.childNodes
            for (index in 0 until children.length) {
                val child = children.item(index)
                if (child.localName == localName) return child
            }
            return null
        }

        private fun directChildren(node: Node, localName: String): List<Node> {
            val result = ArrayList<Node>()
            val children = node.childNodes
            for (index in 0 until children.length) {
                val child = children.item(index)
                if (child.localName == localName) result += child
            }
            return result
        }

        private fun descendantAttribute(node: Node?, descendantName: String, attributeName: String): String? {
            if (node == null) return null
            if (node.localName == descendantName) {
                val attributes = node.attributes
                for (index in 0 until attributes.length) {
                    val attribute = attributes.item(index)
                    if (attribute.localName == attributeName || attribute.nodeName.endsWith(":$attributeName")) {
                        return attribute.nodeValue
                    }
                }
            }
            val children = node.childNodes
            for (index in 0 until children.length) {
                descendantAttribute(children.item(index), descendantName, attributeName)?.let { return it }
            }
            return null
        }

        private fun delimiterName(symbol: String, opening: Boolean): String = when (symbol) {
            "(", ")" -> if (opening) {
                term("abre paréntesis", "open parenthesis")
            } else {
                term("cierra paréntesis", "close parenthesis")
            }
            "[", "]" -> if (opening) {
                term("abre corchete", "open bracket")
            } else {
                term("cierra corchete", "close bracket")
            }
            "{", "}" -> if (opening) {
                term("abre llave", "open brace")
            } else {
                term("cierra llave", "close brace")
            }
            "|" -> term("barra vertical", "vertical bar")
            else -> verbalizePlainMath(symbol)
        }

        private fun verbalizePlainMath(text: String): String {
            if (text.isBlank()) return ""
            val out = StringBuilder(text.length + 16)
            text.forEach { char ->
                val word = when (char) {
                    '+' -> spaced("más", "plus")
                    '-', '−' -> spaced("menos", "minus")
                    '=', '＝' -> spaced("igual a", "equals")
                    '×', '·', '⋅' -> spaced("por", "times")
                    '÷' -> spaced("dividido por", "divided by")
                    '<' -> spaced("menor que", "less than")
                    '>' -> spaced("mayor que", "greater than")
                    '≤' -> spaced("menor o igual que", "less than or equal to")
                    '≥' -> spaced("mayor o igual que", "greater than or equal to")
                    '≠' -> spaced("distinto de", "not equal to")
                    '±' -> spaced("más o menos", "plus or minus")
                    '∞' -> spaced("infinito", "infinity")
                    'π', 'Π' -> " pi "
                    'θ', 'Θ' -> " theta "
                    'λ', 'Λ' -> " lambda "
                    'μ', 'Μ' -> " mu "
                    'σ', 'Σ' -> " sigma "
                    'Δ', 'δ' -> " delta "
                    '∂' -> spaced("derivada parcial", "partial derivative")
                    '∇' -> " nabla "
                    '∈' -> spaced("pertenece a", "element of")
                    '∉' -> spaced("no pertenece a", "not an element of")
                    '∪' -> spaced("unión", "union")
                    '∩' -> spaced("intersección", "intersection")
                    '→' -> spaced("tiende a", "approaches")
                    else -> char.toString()
                }
                out.append(word)
            }
            return normalize(out.toString())
        }

        private fun spaced(spanish: String, english: String): String = " ${term(spanish, english)} "

        private fun term(spanish: String, english: String): String =
            if (language == BookLanguage.ENGLISH) english else spanish

        private fun phrase(vararg parts: String): String =
            normalize(parts.filter { it.isNotBlank() }.joinToString(" "))

        private fun normalize(text: String): String = text
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" *\\n *"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        private fun needsSpace(left: Char, right: Char): Boolean =
            !left.isWhitespace() && !right.isWhitespace() &&
                left !in "([{" && right !in ")]},.;:"
    }
}
