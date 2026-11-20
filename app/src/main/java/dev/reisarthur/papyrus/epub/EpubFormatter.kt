package dev.reisarthur.papyrus.epub

/**
 * Converte o XHTML de um capítulo em blocos limpos e aplica a formatação automática:
 * - descarta o CSS do livro (fica só itálico/negrito/alinhamento);
 * - normaliza espaços, remove hifens invisíveis e caracteres de largura zero;
 * - junta parágrafos quebrados no meio da frase (comum em EPUB convertido de PDF);
 * - converte travessão de diálogo ("- Olá" -> "— Olá") e aspas retas em curvas;
 * - transforma "***", "* * *" e <hr> em separador de cena;
 * - tira o recuo do primeiro parágrafo depois de título/separador/imagem.
 */
class EpubFormatter(
    private val css: CssHints,
    /** Resolve o src de uma imagem (relativo ao capítulo) para um bloco de imagem já extraído. */
    private val resolveImage: (String) -> Block.Image?,
) {
    private val blocks = ArrayList<Block>()
    private val runs = ArrayList<Run>()
    private val text = StringBuilder()
    private var runStyle = 0

    /** Pilha de elementos abertos: nome + estilo herdado + tipo de bloco. */
    private data class Frame(val name: String, val style: Int, val kind: Block.Kind?)
    private val stack = ArrayList<Frame>()
    private var skipDepth = 0
    private var pendingFlags = 0
    /** id -> índice do bloco onde a âncora cai (para o sumário apontar ao lugar certo). */
    val anchors = HashMap<String, Int>()

    fun format(html: String, fallbackTitle: String): Chapter {
        val tokens = HtmlTokenizer.tokenize(html)
        var inBody = tokens.none { it is Token.Start && it.name == "body" }
        for (t in tokens) {
            when (t) {
                is Token.Start -> {
                    if (t.name == "body") { inBody = true; continue }
                    if (!inBody) continue
                    onStart(t)
                }
                is Token.End -> if (inBody) onEnd(t.name)
                is Token.Text -> if (inBody && skipDepth == 0) appendText(t.text)
            }
        }
        flushParagraph()
        val (cleaned, indexMap) = Cleanup.run(blocks)
        // a limpeza junta/remove blocos: as âncoras precisam apontar para os novos índices
        for (key in anchors.keys.toList()) {
            val old = anchors.getValue(key)
            anchors[key] = if (old < indexMap.size) indexMap[old] else cleaned.size
        }
        val title = cleaned.firstNotNullOfOrNull { b ->
            (b as? Block.Para)?.takeIf { it.kind in HEADINGS }?.text?.take(80)
        } ?: fallbackTitle
        return Chapter(title, cleaned)
    }

    private fun currentStyle() = stack.lastOrNull()?.style ?: 0
    private fun currentKind(): Block.Kind =
        stack.asReversed().firstNotNullOfOrNull { it.kind } ?: Block.Kind.BODY

    private fun onStart(t: Token.Start) {
        val name = t.name
        if (skipDepth > 0) {
            if (!t.selfClosing && name !in VOID) skipDepth++
            return
        }
        if (name in SKIP) {
            if (!t.selfClosing) skipDepth = 1
            return
        }
        // âncoras: id em qualquer elemento e o estilo antigo <a name="...">
        t.attr("id")?.let { anchors[it] = blocks.size }
        if (t.name == "a") t.attr("name")?.let { anchors.putIfAbsent(it, blocks.size) }

        when (name) {
            "br" -> { breakLine(); return }
            "hr" -> { flushParagraph(); blocks.add(Block.Separator); return }
            "img", "image" -> {
                val src = t.attr("src", "xlink:href", "href") ?: return
                resolveImage(src)?.let { img ->
                    flushParagraph()
                    blocks.add(img)
                }
                if (name == "image" && !t.selfClosing) skipDepth = 1
                return
            }
        }
        if (name in VOID || t.selfClosing) return

        var style = currentStyle() or css.inlineStyle(t.attrs["class"], t.attrs["style"])
        style = style or when (name) {
            "i", "em", "cite", "var", "dfn" -> Style.ITALIC
            "b", "strong" -> Style.BOLD
            "sup" -> Style.SUP
            "sub" -> Style.SUB
            "code", "tt", "kbd", "samp" -> Style.MONO
            else -> 0
        }
        var kind: Block.Kind? = when (name) {
            "h1" -> Block.Kind.H1
            "h2" -> Block.Kind.H2
            "h3", "h4", "h5", "h6" -> Block.Kind.H3
            "blockquote" -> Block.Kind.QUOTE
            "pre" -> Block.Kind.PRE
            "li", "dd" -> Block.Kind.LIST
            "center" -> Block.Kind.CENTER
            else -> null
        }
        if (name in BLOCK) {
            flushParagraph()
            if (kind == null || kind == Block.Kind.QUOTE) {
                css.alignment(t.attrs["class"], t.attrs["style"], t.attrs["align"])?.let { kind = it }
            }
        }
        stack.add(Frame(name, style, kind))
    }

    private fun onEnd(name: String) {
        if (skipDepth > 0) {
            skipDepth--
            return
        }
        val idx = stack.indexOfLast { it.name == name }
        if (idx < 0) return
        if (name in BLOCK) flushParagraph() else flushRun()
        while (stack.size > idx) stack.removeAt(stack.lastIndex)
        // células de tabela viram texto separado por barra
        if (name == "td" || name == "th") appendText(" | ")
    }

    /** Espaços pendentes (ainda não escritos) desde o último caractere visível. */
    private var spaceRun = 0
    /** Quantos desses espaços são "visuais" (&nbsp;, espaço largo…), não indentação do código HTML. */
    private var visualSpaceRun = 0
    private var hardBreak: Char? = null

    private fun appendText(input: String) {
        // acentos sempre na forma composta (ê = 1 caractere): seleção, busca e dicionário dependem disso
        val raw = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFC)
        val style = currentStyle()
        if (style != runStyle) flushRun()
        runStyle = style
        if (currentKind() == Block.Kind.PRE) {
            text.append(raw.replace("\r", ""))
            return
        }
        for (c in raw) {
            when {
                c in INVISIBLE -> Unit
                // separadores de linha/parágrafo Unicode viram quebra de verdade
                c == '\u2028' || c == '\u2029' -> hardBreak = c
                // qualquer espaço (inclusive &nbsp;, tabulação, espaço largo) só é escrito depois,
                // já colapsado; sequências longas usadas como "recuo" falso não chegam ao texto
                c.isWhitespace() -> {
                    spaceRun++
                    if (c != ' ' && c != '\n' && c != '\t' && c != '\r') visualSpaceRun++
                }
                else -> {
                    emitPendingSpace(c)
                    text.append(c)
                }
            }
        }
    }

    /**
     * Decide o que fazer com os espaços acumulados antes de [next]:
     * - separador Unicode -> quebra de linha/parágrafo;
     * - 3+ espaços visuais (&nbsp;) depois de fim de frase e antes de maiúscula/travessão -> o conversor usou
     *   espaços para separar parágrafos ("sofistas.      Um dia,"): vira parágrafo novo;
     * - qualquer outro caso -> um espaço comum (nunca no começo do parágrafo).
     */
    private fun emitPendingSpace(next: Char) {
        val brk = hardBreak
        val run = spaceRun
        val visual = visualSpaceRun
        hardBreak = null
        spaceRun = 0
        visualSpaceRun = 0
        val previous = text.lastOrNull() ?: runs.lastOrNull()?.text?.lastOrNull()
        when {
            brk == '\u2029' -> flushParagraph()
            brk == '\u2028' -> breakLine()
            visual >= 3 && previous != null && previous in SENTENCE_END && (next.isUpperCase() || next in DIALOGUE_START) -> flushParagraph()
            run > 0 && previous != null && previous != ' ' -> {
                // o espaço fica no trecho anterior (não "herda" itálico/negrito da próxima palavra)
                if (text.isEmpty() && runs.isNotEmpty()) runs[runs.lastIndex] = runs.last().copy(text = runs.last().text + " ")
                else text.append(' ')
            }
        }
    }

    private fun flushRun() {
        if (text.isNotEmpty()) {
            runs.add(Run(text.toString(), runStyle))
            text.setLength(0)
        }
    }

    /** <br>: termina a linha atual sem espaço extra e a próxima começa sem recuo. */
    private fun breakLine() {
        flushRun()
        if (runs.any { it.text.isNotBlank() }) {
            flushParagraph(extraFlags = Block.TIGHT)
            pendingFlags = pendingFlags or Block.NO_INDENT
        }
    }

    private fun flushParagraph(extraFlags: Int = 0) {
        spaceRun = 0
        visualSpaceRun = 0
        hardBreak = null
        flushRun()
        val trimmed = trimRuns(runs)
        runs.clear()
        if (trimmed.isEmpty()) return
        val kind = currentKind()
        if (kind == Block.Kind.PRE) {
            // cada linha de <pre> vira um parágrafo sem recuo para manter a forma
            trimmed.joinToString("") { it.text }.split('\n').forEach { line ->
                blocks.add(Block.Para(listOf(Run(line.ifEmpty { " " }, Style.MONO)), kind, Block.NO_INDENT or Block.TIGHT))
            }
        } else {
            blocks.add(Block.Para(trimmed, kind, pendingFlags or extraFlags))
        }
        pendingFlags = 0
    }

    private fun trimRuns(src: List<Run>): List<Run> {
        val list = src.filter { it.text.isNotEmpty() }.toMutableList()
        while (list.isNotEmpty() && list.first().text.isBlank()) list.removeAt(0)
        while (list.isNotEmpty() && list.last().text.isBlank()) list.removeAt(list.lastIndex)
        if (list.isEmpty()) return list
        list[0] = list[0].copy(text = list[0].text.trimStart())
        list[list.lastIndex] = list.last().copy(text = list.last().text.trimEnd())
        // descarta parágrafos feitos só de espaço inquebrável
        if (list.all { it.text.replace(' ', ' ').isBlank() }) return emptyList()
        return list.filter { it.text.isNotEmpty() }
    }

    private companion object {
        const val INVISIBLE = "\u00AD\u200B\uFEFF\u200C\u200D\u2060"
        const val SENTENCE_END = ".!?…\"”»"
        const val DIALOGUE_START = "—–-\"“«"
        val HEADINGS = setOf(Block.Kind.H1, Block.Kind.H2, Block.Kind.H3)
        val SKIP = setOf("script", "style", "head", "title", "noscript", "math", "nav", "rt", "rp")
        val VOID = setOf("br", "hr", "img", "meta", "link", "input", "col", "area", "base", "wbr", "source")
        val BLOCK = setOf(
            "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre", "section",
            "article", "header", "footer", "figure", "figcaption", "table", "tr", "dd", "dt", "ul", "ol",
            "center", "aside", "main", "dl", "caption", "address", "body", "svg",
        )
    }
}

/** Pós-processamento do capítulo inteiro, onde dá pra olhar parágrafos vizinhos. */
internal object Cleanup {
    private val SCENE_BREAK = Regex("^[\\s*•·~#⁂◆◇❖✦✧○●◦_=—–-]{1,15}$")
    private const val BREAK_SYMBOLS = "*•·~#⁂◆◇❖✦✧○●◦_="
    private val TERMINAL =setOf('.', '!', '?', '…', ':', ';', '"', '”', '»', ')', '’', '\'', '—', ']')

    /** Devolve os blocos limpos e, para cada bloco original, o índice do bloco resultante. */
    fun run(input: List<Block>): Pair<List<Block>, IntArray> {
        val out = ArrayList<Block>(input.size)
        val indexMap = IntArray(input.size)
        for ((i, b) in input.withIndex()) {
            indexMap[i] = out.size
            if (b is Block.Para && b.kind != Block.Kind.PRE) {
                val txt = b.text.trim()
                val isSceneBreak = txt.isNotEmpty() && SCENE_BREAK.matches(txt) &&
                    (txt.any { it in BREAK_SYMBOLS } || txt.length >= 3)
                if (isSceneBreak) {
                    if (out.lastOrNull() != Block.Separator) out.add(Block.Separator) else indexMap[i] = out.lastIndex
                    continue
                }
                val fixed = fixParagraph(b)
                val prev = out.lastOrNull()
                if (prev is Block.Para && shouldMerge(prev, fixed)) {
                    out[out.lastIndex] = merge(prev, fixed)
                    indexMap[i] = out.lastIndex
                    continue
                }
                out.add(fixed)
            } else {
                out.add(b)
            }
        }
        for (i in out.indices) {
            val b = out[i]
            if (b is Block.Para) out[i] = fixTinyRuns(glueStrayLetters(normalizeAlignment(b)))
        }
        return removeIndentAfterBreaks(out) to indexMap
    }

    /** Tipos de parágrafo que podem ser pedaços do mesmo parágrafo (alinhamento costuma vir errado em EPUB convertido). */
    private val MERGEABLE = setOf(Block.Kind.BODY, Block.Kind.CENTER, Block.Kind.RIGHT, Block.Kind.QUOTE)

    /** Palavras que nunca terminam uma frase: se o pedaço acaba nelas, o texto continua no próximo. */
    private val FUNCTION_WORDS = setOf(
        "a", "o", "as", "os", "e", "de", "do", "da", "dos", "das", "em", "no", "na", "nos", "nas", "um", "uma",
        "uns", "umas", "que", "se", "por", "para", "pra", "com", "sem", "ao", "aos", "à", "às", "pelo", "pela",
        "pelos", "pelas", "num", "numa", "seu", "sua", "seus", "suas", "meu", "minha", "nosso", "nossa", "este",
        "esta", "esse", "essa", "aquele", "aquela", "isso", "como", "mas", "ou", "nem", "quando", "onde", "porque",
        "the", "of", "and", "to", "in", "a", "an",
    )

    /**
     * Decide se [b] é continuação de [a]. Acontece em EPUBs convertidos de PDF, onde cada linha
     * (ou pedaço de linha) vira um parágrafo próprio, às vezes com alinhamento diferente.
     */
    private fun shouldMerge(a: Block.Para, b: Block.Para): Boolean {
        if (a.kind !in MERGEABLE || b.kind !in MERGEABLE) return false
        val at = a.text.trimEnd()
        val bt = b.text.trimStart()
        if (at.isEmpty() || bt.isEmpty()) return false
        val last = at.last()
        if (last in TERMINAL || !(last.isLetterOrDigit() || last == ',' || last == '-')) return false
        // quebras com <br> (TIGHT) costumam ser versos: só junta se as linhas forem longas como prosa,
        // ou se os pedaços têm alinhamentos diferentes (sinal claro de texto picado por conversor)
        val misaligned = a.kind != b.kind || a.kind == Block.Kind.CENTER || a.kind == Block.Kind.RIGHT
        if (a.flags and Block.TIGHT != 0 && at.length < 45 && !misaligned) return false
        val lastWord = at.substringAfterLast(' ').lowercase()
        return bt.first().isLowerCase() || lastWord in FUNCTION_WORDS || last == ','
    }

    private fun merge(a: Block.Para, b: Block.Para): Block.Para {
        val runs = a.runs.toMutableList()
        val lastRun = runs.last()
        val endsWithHyphen = lastRun.text.endsWith("-") && lastRun.text.length > 1 && lastRun.text[lastRun.text.length - 2].isLetter()
        runs[runs.lastIndex] = if (endsWithHyphen) lastRun.copy(text = lastRun.text.dropLast(1))
        else lastRun.copy(text = lastRun.text + " ")
        runs.addAll(b.runs)
        // o pedaço "picado" herda o tipo do texto corrido; alinhamento especial só sobrevive se ambos o tinham
        val kind = if (a.kind == b.kind) a.kind else Block.Kind.BODY
        return a.copy(runs = runs, kind = kind, flags = a.flags and Block.TIGHT.inv())
    }

    /**
     * Texto centralizado/à direita de verdade é curto (epígrafe, assinatura). Parágrafos longos
     * com esse alinhamento vieram de CSS de conversor e viram texto corrido.
     */
    private fun normalizeAlignment(p: Block.Para): Block.Para =
        if ((p.kind == Block.Kind.CENTER || p.kind == Block.Kind.RIGHT) && p.text.length > 160) p.copy(kind = Block.Kind.BODY) else p

    /** Letras que existem sozinhas como palavra em português. */
    private const val STANDALONE_LETTERS = "aeoàéóôyAEOÀÉÓÔY"

    /**
     * Cola letras soltas na palavra anterior: "toda s as situações" -> "todas as situações".
     * Acontece quando o conversor partiu a palavra em dois elementos.
     */
    private fun glueStrayLetters(p: Block.Para): Block.Para {
        val text = p.text
        val toDelete = ArrayList<Int>()
        for (m in STRAY_LETTER.findAll(text)) {
            val letter = m.groupValues[2][0]
            if (letter !in STANDALONE_LETTERS) toDelete += m.groups[2]!!.range.first - 1 // o espaço antes da letra
        }
        if (toDelete.isEmpty()) return p
        val drop = toDelete.toHashSet()
        var pos = 0
        val runs = p.runs.map { r ->
            val sb = StringBuilder()
            for (c in r.text) {
                if (pos !in drop) sb.append(c)
                pos++
            }
            r.copy(text = sb.toString())
        }.filter { it.text.isNotEmpty() }
        return p.copy(runs = runs)
    }

    /**
     * Um pedaço de 1 ou 2 letras grudado no fim de uma palavra com estilo diferente ("toda" + itálico "s")
     * é sobra de conversão: herda o estilo da palavra.
     */
    private fun fixTinyRuns(p: Block.Para): Block.Para {
        if (p.runs.size < 2) return p
        val runs = p.runs.toMutableList()
        for (i in 1 until runs.size) {
            val prev = runs[i - 1].text
            val cur = runs[i].text
            val tiny = cur.length <= 2 && cur.all { it.isLetter() }
            val glued = prev.isNotEmpty() && prev.last().isLetter()
            val nextOk = i + 1 >= runs.size || runs[i + 1].text.firstOrNull()?.isLetter() != true
            if (tiny && glued && nextOk && runs[i].style != runs[i - 1].style) runs[i] = runs[i].copy(style = runs[i - 1].style)
        }
        // junta trechos vizinhos que ficaram com o mesmo estilo
        val merged = ArrayList<Run>()
        for (r in runs) {
            val last = merged.lastOrNull()
            if (last != null && last.style == r.style) merged[merged.lastIndex] = last.copy(text = last.text + r.text) else merged += r
        }
        return p.copy(runs = merged)
    }

    private val STRAY_LETTER = Regex("(\\p{L}{2,}) (\\p{Ll})(?=[\\s,.;:!?…]|$)")

    private fun fixParagraph(p: Block.Para): Block.Para {
        val runs = p.runs.mapIndexed { i, r -> r.copy(text = fixSpacing(r.text, isFirst = i == 0)) }.toMutableList()
        // travessão de diálogo no começo do parágrafo
        if (runs.isNotEmpty()) {
            val first = runs[0].text
            val dialog = Regex("^(?:--|[-–−])\\s*(?=\\S)").find(first)
            if (dialog != null && p.kind == Block.Kind.BODY) {
                runs[0] = runs[0].copy(text = "— " + first.substring(dialog.range.last + 1))
            }
        }
        // sobra de células de tabela ("A | B |")
        val last = runs.lastIndex
        if (last >= 0 && runs[last].text.endsWith("|")) runs[last] = runs[last].copy(text = runs[last].text.trimEnd('|', ' '))
        val curly = curlyQuotes(runs.filter { it.text.isNotEmpty() }.toMutableList())
        return p.copy(runs = curly)
    }

    private fun fixSpacing(s: String, isFirst: Boolean): String {
        var t = s.replace(Regex(" {2,}"), " ")
            .replace(Regex(" +([,.;:!?…%)\\]])"), "$1")
            .replace(Regex("([(\\[]) +"), "$1")
            .replace("--", "—")
            .replace("...", "…")
        // apóstrofo reto entre letras vira tipográfico (d'água -> d’água)
        t = t.replace(Regex("(?<=\\p{L})'(?=\\p{L})"), "’")
        if (isFirst) t = t.trimStart()
        return t
    }

    /** Troca aspas retas por curvas alternando abre/fecha ao longo do parágrafo. */
    private fun curlyQuotes(runs: MutableList<Run>): MutableList<Run> {
        val total = runs.sumOf { r -> r.text.count { it == '"' } }
        if (total == 0 || total % 2 != 0) return runs
        var open = true
        return runs.map { r ->
            if ('"' !in r.text) r else {
                val sb = StringBuilder(r.text.length)
                for (c in r.text) {
                    if (c == '"') { sb.append(if (open) '“' else '”'); open = !open } else sb.append(c)
                }
                r.copy(text = sb.toString())
            }
        }.toMutableList()
    }

    private fun removeIndentAfterBreaks(list: MutableList<Block>): List<Block> {
        var afterBreak = true
        for (i in list.indices) {
            val b = list[i]
            if (b is Block.Para) {
                if (b.kind == Block.Kind.BODY && afterBreak) list[i] = b.copy(flags = b.flags or Block.NO_INDENT)
                afterBreak = b.kind in setOf(Block.Kind.H1, Block.Kind.H2, Block.Kind.H3, Block.Kind.CENTER)
            } else {
                afterBreak = true
            }
        }
        return list
    }
}
