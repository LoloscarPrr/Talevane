package app.talevane.reader.data

import org.w3c.dom.Node

/**
 * Converts native Word OMML equations into Spanish speech-friendly text.
 *
 * Talevane is primarily an audio reader, so this deliberately preserves mathematical meaning rather
 * than attempting to reproduce Word's visual equation layout. Unknown OMML constructs fall back to
 * their readable descendants instead of dropping the formula.
 */
internal object OmmlMathReader {
    fun read(node: Node): String = normalize(render(node))

    private fun render(node: Node): String = when (node.localName) {
        "oMath", "oMathPara" -> joinChildren(node)
        "r" -> joinChildren(node)
        "t" -> verbalizePlainMath(node.textContent.orEmpty())

        "f" -> {
            val numerator = directChild(node, "num")?.let(::renderContent).orEmpty()
            val denominator = directChild(node, "den")?.let(::renderContent).orEmpty()
            phrase("fracción", numerator, "sobre", denominator)
        }

        "sSup" -> {
            val base = directChild(node, "e")?.let(::renderContent).orEmpty()
            val exponent = directChild(node, "sup")?.let(::renderContent).orEmpty()
            when (normalize(exponent)) {
                "2" -> phrase(base, "al cuadrado")
                "3" -> phrase(base, "al cubo")
                else -> phrase(base, "elevado a", exponent)
            }
        }

        "sSub" -> {
            val base = directChild(node, "e")?.let(::renderContent).orEmpty()
            val subscript = directChild(node, "sub")?.let(::renderContent).orEmpty()
            phrase(base, "subíndice", subscript)
        }

        "sSubSup" -> {
            val base = directChild(node, "e")?.let(::renderContent).orEmpty()
            val subscript = directChild(node, "sub")?.let(::renderContent).orEmpty()
            val exponent = directChild(node, "sup")?.let(::renderContent).orEmpty()
            phrase(base, "subíndice", subscript, "elevado a", exponent)
        }

        "rad" -> {
            val degree = directChild(node, "deg")?.let(::renderContent).orEmpty()
            val expression = directChild(node, "e")?.let(::renderContent).orEmpty()
            if (degree.isBlank() || normalize(degree) == "2") {
                phrase("raíz cuadrada de", expression)
            } else {
                phrase("raíz de índice", degree, "de", expression)
            }
        }

        "nary" -> renderNary(node)

        "limLow" -> {
            val expression = directChild(node, "e")?.let(::renderContent).orEmpty()
            val limit = directChild(node, "lim")?.let(::renderContent).orEmpty()
            phrase(expression, "con límite inferior", limit)
        }

        "limUpp" -> {
            val expression = directChild(node, "e")?.let(::renderContent).orEmpty()
            val limit = directChild(node, "lim")?.let(::renderContent).orEmpty()
            phrase(expression, "con límite superior", limit)
        }

        "func" -> {
            val name = directChild(node, "fName")?.let(::renderContent).orEmpty()
            val argument = directChild(node, "e")?.let(::renderContent).orEmpty()
            phrase(name, "de", argument)
        }

        "d" -> {
            val start = descendantAttribute(node, "begChr", "val").orEmpty().ifBlank { "(" }
            val end = descendantAttribute(node, "endChr", "val").orEmpty().ifBlank { ")" }
            val expressions = directChildren(node, "e").map(::renderContent).filter { it.isNotBlank() }
            phrase(delimiterName(start, opening = true), expressions.joinToString("; "), delimiterName(end, opening = false))
        }

        "m" -> renderMatrix(node)

        "acc" -> {
            val accent = descendantAttribute(node, "chr", "val").orEmpty()
            val expression = directChild(node, "e")?.let(::renderContent).orEmpty()
            when (accent) {
                "^", "ˆ" -> phrase(expression, "con acento circunflejo")
                "¯", "̅" -> phrase(expression, "con barra superior")
                "→", "⃗" -> phrase("vector", expression)
                else -> phrase(expression, "con acento", verbalizePlainMath(accent))
            }
        }

        "bar" -> {
            val expression = directChild(node, "e")?.let(::renderContent).orEmpty()
            phrase(expression, "con barra")
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
            "∑" -> "sumatoria"
            "∏" -> "productoria"
            "∐" -> "coproducto"
            "∫" -> "integral"
            "∬" -> "integral doble"
            "∭" -> "integral triple"
            "∮" -> "integral de contorno"
            else -> verbalizePlainMath(symbol)
        }

        return when {
            lower.isNotBlank() && upper.isNotBlank() ->
                phrase(operator, "desde", lower, "hasta", upper, "de", expression)
            lower.isNotBlank() -> phrase(operator, "desde", lower, "de", expression)
            upper.isNotBlank() -> phrase(operator, "hasta", upper, "de", expression)
            else -> phrase(operator, "de", expression)
        }
    }

    private fun renderMatrix(node: Node): String {
        val rows = directChildren(node, "mr")
        if (rows.isEmpty()) return joinChildren(node)

        return rows.mapIndexed { rowIndex, row ->
            val cells = directChildren(row, "e").map(::renderContent)
            "fila ${rowIndex + 1}: ${cells.joinToString(", ") { "${it.ifBlank { "vacío" }}" }}"
        }.joinToString("; ", prefix = "matriz, ", postfix = ", fin de matriz")
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
        "(" -> if (opening) "abre paréntesis" else "cierra paréntesis"
        ")" -> if (opening) "abre paréntesis" else "cierra paréntesis"
        "[" -> if (opening) "abre corchete" else "cierra corchete"
        "]" -> if (opening) "abre corchete" else "cierra corchete"
        "{" -> if (opening) "abre llave" else "cierra llave"
        "}" -> if (opening) "abre llave" else "cierra llave"
        "|" -> "barra vertical"
        else -> verbalizePlainMath(symbol)
    }

    private fun verbalizePlainMath(text: String): String {
        if (text.isBlank()) return ""
        val out = StringBuilder(text.length + 16)
        text.forEach { char ->
            val word = when (char) {
                '+' -> " más "
                '-', '−' -> " menos "
                '=', '＝' -> " igual a "
                '×', '·', '⋅' -> " por "
                '÷' -> " dividido por "
                '<' -> " menor que "
                '>' -> " mayor que "
                '≤' -> " menor o igual que "
                '≥' -> " mayor o igual que "
                '≠' -> " distinto de "
                '±' -> " más o menos "
                '∞' -> " infinito "
                'π', 'Π' -> " pi "
                'θ', 'Θ' -> " theta "
                'λ', 'Λ' -> " lambda "
                'μ', 'Μ' -> " mu "
                'σ', 'Σ' -> " sigma "
                'Δ', 'δ' -> " delta "
                '∂' -> " derivada parcial "
                '∇' -> " nabla "
                '∈' -> " pertenece a "
                '∉' -> " no pertenece a "
                '∪' -> " unión "
                '∩' -> " intersección "
                '→' -> " tiende a "
                else -> char.toString()
            }
            out.append(word)
        }
        return normalize(out.toString())
    }

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
