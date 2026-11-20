package dev.reisarthur.papyrus.epub

/**
 * Tokenizador de (X)HTML tolerante a erros. Muitos EPUBs têm XHTML inválido
 * (entidades não declaradas, tags mal fechadas), então não usamos um parser XML estrito.
 */
sealed class Token {
    data class Start(val name: String, val attrs: Map<String, String>, val selfClosing: Boolean) : Token() {
        fun attr(vararg names: String): String? = names.firstNotNullOfOrNull { attrs[it] }
    }
    data class End(val name: String) : Token()
    data class Text(val text: String) : Token()
}

object HtmlTokenizer {

    fun tokenize(src: String): List<Token> {
        val out = ArrayList<Token>(src.length / 20)
        var i = 0
        val n = src.length
        while (i < n) {
            val lt = src.indexOf('<', i)
            if (lt < 0) {
                out.addText(src.substring(i))
                break
            }
            if (lt > i) out.addText(src.substring(i, lt))
            i = when {
                src.startsWith("<!--", lt) -> skipPast(src, lt, "-->")
                src.startsWith("<![CDATA[", lt) -> {
                    val end = src.indexOf("]]>", lt).let { if (it < 0) n else it }
                    out.add(Token.Text(src.substring(lt + 9, end)))
                    minOf(n, end + 3)
                }
                src.startsWith("<!", lt) || src.startsWith("<?", lt) -> skipPast(src, lt, ">")
                lt + 1 < n && src[lt + 1] == '/' -> {
                    val gt = src.indexOf('>', lt).let { if (it < 0) n else it }
                    out.add(Token.End(localName(src.substring(lt + 2, gt).trim())))
                    gt + 1
                }
                lt + 1 < n && (src[lt + 1].isLetter()) -> parseStart(src, lt, out)
                else -> { out.addText("<"); lt + 1 }
            }
        }
        return out
    }

    private fun MutableList<Token>.addText(raw: String) {
        if (raw.isNotEmpty()) add(Token.Text(decodeEntities(raw)))
    }

    private fun skipPast(src: String, from: Int, marker: String): Int {
        val idx = src.indexOf(marker, from)
        return if (idx < 0) src.length else idx + marker.length
    }

    private fun parseStart(src: String, lt: Int, out: MutableList<Token>): Int {
        val n = src.length
        var i = lt + 1
        val nameStart = i
        while (i < n && !src[i].isWhitespace() && src[i] != '>' && src[i] != '/') i++
        val name = localName(src.substring(nameStart, i))
        val attrs = HashMap<String, String>()
        var selfClosing = false
        while (i < n) {
            while (i < n && src[i].isWhitespace()) i++
            if (i >= n) break
            val c = src[i]
            if (c == '>') { i++; break }
            if (c == '/') { selfClosing = true; i++; continue }
            val aStart = i
            while (i < n && !src[i].isWhitespace() && src[i] != '=' && src[i] != '>' && src[i] != '/') i++
            val aName = src.substring(aStart, i).lowercase()
            while (i < n && src[i].isWhitespace()) i++
            var value = ""
            if (i < n && src[i] == '=') {
                i++
                while (i < n && src[i].isWhitespace()) i++
                if (i < n && (src[i] == '"' || src[i] == '\'')) {
                    val q = src[i]
                    val end = src.indexOf(q, i + 1).let { if (it < 0) n else it }
                    value = src.substring(i + 1, end)
                    i = minOf(n, end + 1)
                } else {
                    val vStart = i
                    while (i < n && !src[i].isWhitespace() && src[i] != '>') i++
                    value = src.substring(vStart, i)
                }
            }
            if (aName.isNotEmpty()) attrs[aName] = decodeEntities(value)
        }
        out.add(Token.Start(name, attrs, selfClosing))
        // conteúdo de script/style é texto cru até a tag de fechamento
        if (!selfClosing && (name == "script" || name == "style")) {
            val close = src.indexOf("</", i).let { idx ->
                var j = idx
                while (j >= 0 && !src.regionMatches(j + 2, name, 0, name.length, ignoreCase = true)) j = src.indexOf("</", j + 2)
                j
            }
            val end = if (close < 0) n else close
            out.add(Token.Text(src.substring(i, end)))
            return end
        }
        return i
    }

    /** "dc:title" -> "title", "svg:image" -> "image"; sempre minúsculo. */
    private fun localName(raw: String): String = raw.substringAfter(':').lowercase()

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "lsquo" to "‘", "rsquo" to "’",
        "ldquo" to "“", "rdquo" to "”", "laquo" to "«", "raquo" to "»", "copy" to "©",
        "reg" to "®", "trade" to "™", "deg" to "°", "middot" to "·", "bull" to "•",
        "shy" to "­", "ordf" to "ª", "ordm" to "º", "sect" to "§", "para" to "¶",
        "iexcl" to "¡", "iquest" to "¿", "times" to "×", "divide" to "÷", "euro" to "€",
        "thinsp" to " ", "ensp" to " ", "emsp" to " ", "zwnj" to "", "zwj" to "",
        "aacute" to "á", "Aacute" to "Á", "agrave" to "à", "Agrave" to "À", "acirc" to "â", "Acirc" to "Â",
        "atilde" to "ã", "Atilde" to "Ã", "auml" to "ä", "eacute" to "é", "Eacute" to "É", "egrave" to "è",
        "ecirc" to "ê", "Ecirc" to "Ê", "iacute" to "í", "Iacute" to "Í", "icirc" to "î", "oacute" to "ó",
        "Oacute" to "Ó", "ocirc" to "ô", "Ocirc" to "Ô", "otilde" to "õ", "Otilde" to "Õ", "ouml" to "ö",
        "uacute" to "ú", "Uacute" to "Ú", "uuml" to "ü", "Uuml" to "Ü", "ccedil" to "ç", "Ccedil" to "Ç",
        "ntilde" to "ñ", "Ntilde" to "Ñ",
    )

    fun decodeEntities(s: String): String {
        if (s.indexOf('&') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i)
                if (semi in (i + 2)..(i + 10)) {
                    val ent = s.substring(i + 1, semi)
                    val decoded = when {
                        ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let(::codePoint)
                        ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let(::codePoint)
                        else -> NAMED[ent] ?: NAMED[ent.lowercase()]
                    }
                    if (decoded != null) {
                        sb.append(decoded)
                        i = semi + 1
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun codePoint(cp: Int): String? =
        if (Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else null
}
