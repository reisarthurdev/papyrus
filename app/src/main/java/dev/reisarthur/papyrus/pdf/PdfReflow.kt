package dev.reisarthur.papyrus.pdf

import android.graphics.RectF
import kotlin.math.abs

/**
 * "Zoom sem cortar palavras" para PDF: recorta cada palavra da página (pela caixa das letras)
 * e a reposiciona em linhas que cabem na largura da tela, como um texto refluído.
 * A aparência original (fonte, itálico, tamanho relativo) é mantida porque o que se move
 * são as imagens das palavras, não o texto redesenhado.
 *
 * Coordenadas de origem são normalizadas (0..1 da página); as de destino são pixels da tela.
 */
class PdfReflow(
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val marginX: Int,
    private val marginTop: Int,
    private val marginBottom: Int,
) {
    /** Um pedaço de palavra posicionado: caracteres [start, end) da página, origem e destino. */
    class Piece(val start: Int, val end: Int, val src: RectF, val dst: RectF)

    /** Faixa da página copiada inteira (figura/tabela entre linhas de texto). */
    class Figure(val src: RectF, val dst: RectF)

    /** Uma tela de conteúdo gerada a partir de uma página do PDF. */
    class SubPage(val pdfPage: Int, val startOffset: Int, val endOffset: Int, val pieces: List<Piece>, val figures: List<Figure>)

    private class Word(val start: Int, val end: Int, val box: RectF)
    private class Line(val words: List<Word>, val box: RectF)

    /**
     * Gera as subpáginas de uma página do PDF.
     * [figureRects]: regiões de imagem/desenho registradas na importação (0..1); [imageRects] são
     * as fotos entre elas. [scaleX]/[scaleY] = pixels de tela por unidade normalizada.
     */
    fun layoutPage(
        pdfPage: Int, text: PageText, figureRects: List<RectF>, imageRects: List<RectF>, scaleX: Float, scaleY: Float,
    ): List<SubPage> {
        val allLines = readLines(text)
        // altura de linha do corpo do texto (mediana): base para separar texto comum de título gigante
        val lineH = allLines.map { it.box.height() }.sorted().let { if (it.isEmpty()) 0.015f else it[it.size / 2] }
        val regions = figureRegions(figureRects, imageRects, allLines).toMutableList()
        // texto só vai para dentro da figura se estiver quase todo nela (rótulo de gráfico, célula de
        // tabela). Linha que só encosta numa figura continua no texto: melhor repetir que sumir.
        val textLines = allLines.filter { l -> regions.none { overlapRatio(l.box, it) >= 0.6f } }
        // letras de título decorativo (muito maiores que o corpo) viram uma imagem do bloco inteiro:
        // quebrá-las palavra por palavra espalhava letras soltas pela tela
        val display = textLines.filter { it.box.height() > lineH * 2.2f }
        regions += groupBlocks(display, lineH)
        val lines = textLines.filter { it !in display }
        if (lines.isEmpty() && regions.isEmpty()) return emptyList()
        val minLeft = lines.minOfOrNull { it.box.left } ?: 0f
        val maxRight = lines.maxOfOrNull { it.box.right } ?: 1f

        val contentW = (screenWidth - marginX * 2).toFloat()
        val pageBottom = (screenHeight - marginBottom).toFloat()
        val wordGap = lineH * scaleY * 0.32f
        val indent = lineH * scaleY * 1.6f
        val lineSpacing = 1.28f

        val subPages = ArrayList<SubPage>()
        var pieces = ArrayList<Piece>()
        var figures = ArrayList<Figure>()
        var y = marginTop.toFloat()
        var subStart = lines.firstOrNull()?.words?.first()?.start ?: 0

        // linha de saída sendo montada
        val pending = ArrayList<Pair<Word, Boolean>>() // palavra + "gruda na anterior" (hifenização)
        var pendingWidth = 0f

        fun flushSubPage(endOffset: Int) {
            if (pieces.isEmpty() && figures.isEmpty()) return
            subPages += SubPage(pdfPage, subStart, endOffset, pieces, figures)
            pieces = ArrayList()
            figures = ArrayList()
            y = marginTop.toFloat()
            subStart = endOffset
        }

        /** Escreve a linha pendente na tela; [justify] distribui o espaço que sobrar. */
        fun emitLine(justify: Boolean, firstOfParagraph: Boolean) {
            if (pending.isEmpty()) return
            val maxH = pending.maxOf { it.first.box.height() } * scaleY
            val h = maxH * lineSpacing
            if (y + h > pageBottom && (pieces.isNotEmpty() || figures.isNotEmpty())) flushSubPage(pending.first().first.start)
            val startX = marginX + if (firstOfParagraph) indent else 0f
            val gaps = pending.count { !it.second } - 1
            val extra = if (justify && gaps > 0) ((contentW - (startX - marginX) - pendingWidth) / gaps).coerceIn(0f, wordGap * 3) else 0f
            var x = startX
            val baseline = y + maxH
            pending.forEachIndexed { i, (w, glued) ->
                if (i > 0 && !glued) x += wordGap + extra
                val dw = w.box.width() * scaleX
                val dh = w.box.height() * scaleY
                pieces += Piece(w.start, w.end, RectF(w.box), RectF(x, baseline - dh, x + dw, baseline))
                x += dw
            }
            y += h
            pending.clear()
            pendingWidth = 0f
        }

        /** Figura inteira, centralizada, do tamanho do zoom mas nunca maior que a tela. */
        fun placeFigure(region: RectF, nextOffset: Int) {
            var w = region.width() * scaleX
            var h = region.height() * scaleY
            val fit = minOf(1f, contentW / w, (pageBottom - marginTop) / h)
            w *= fit; h *= fit
            if (y + h > pageBottom) flushSubPage(nextOffset)
            val x = (screenWidth - w) / 2
            figures += Figure(RectF(region), RectF(x, y, x + w, y + h))
            y += h + lineH * scaleY * 0.6f
        }

        // figuras entram no ponto da leitura em que aparecem: antes da primeira linha abaixo do topo delas
        val queue = regions.sortedBy { it.top }.toMutableList()

        fun isIndented(l: Line) = l.box.left - minLeft > lineH * 0.9f
        // recuo só onde o original tinha recuo (a página pode começar no meio de um parágrafo)
        var paraIndent = lines.firstOrNull()?.let(::isIndented) ?: false
        var paragraphLineCount = 0
        lines.forEachIndexed { li, line ->
            if (queue.isNotEmpty() && queue.first().top <= line.box.top) {
                emitLine(justify = false, firstOfParagraph = paraIndent && paragraphLineCount == 0)
                while (queue.isNotEmpty() && queue.first().top <= line.box.top) placeFigure(queue.removeAt(0), line.words.first().start)
                paraIndent = isIndented(line)
                paragraphLineCount = 0
            } else {
                val prev = lines.getOrNull(li - 1)
                if (prev != null) {
                    val gap = line.box.top - prev.box.bottom
                    val indented = isIndented(line)
                    val prevShort = maxRight - prev.box.right > lineH * 3f
                    val bigger = abs(line.box.height() - prev.box.height()) > lineH * 0.35f
                    if (gap > lineH * 0.8f || indented || prevShort || bigger) {
                        emitLine(justify = false, firstOfParagraph = paraIndent && paragraphLineCount == 0)
                        y += lineH * scaleY * 0.5f
                        paraIndent = indented
                        paragraphLineCount = 0
                    }
                }
            }
            line.words.forEachIndexed { wi, w ->
                // hifenização no fim da linha: "pala-" + "vra" vira uma palavra só (sem o hífen)
                val prevWord = pending.lastOrNull()?.first
                val glue = wi == 0 && prevWord != null && endsWithHyphen(text, prevWord) && text.text.getOrNull(w.start)?.isLowerCase() == true
                if (glue) {
                    val (pw, pg) = pending.removeAt(pending.lastIndex)
                    pendingWidth -= pw.box.width() * scaleX
                    val trimmed = trimLastChar(text, pw)
                    pending += trimmed to pg
                    pendingWidth += trimmed.box.width() * scaleX
                }
                val ww = w.box.width() * scaleX
                val needed = ww + if (pending.isEmpty() || glue) 0f else wordGap
                val available = contentW - if (paraIndent && paragraphLineCount == 0) indent else 0f
                if (pending.isNotEmpty() && pendingWidth + needed > available) {
                    if (glue && pending.size > 1) {
                        // a palavra hifenizada inteira não cabe: leva o pedaço anterior junto para a próxima linha
                        val (pw, _) = pending.removeAt(pending.lastIndex)
                        pendingWidth -= pw.box.width() * scaleX + wordGap
                        emitLine(justify = true, firstOfParagraph = paraIndent && paragraphLineCount == 0)
                        paragraphLineCount++
                        pending += pw to false
                        pendingWidth = pw.box.width() * scaleX
                    } else if (!glue) {
                        emitLine(justify = true, firstOfParagraph = paraIndent && paragraphLineCount == 0)
                        paragraphLineCount++
                    }
                }
                pending += w to (glue && pending.isNotEmpty())
                pendingWidth += ww + if (pending.size > 1 && !glue) wordGap else 0f
            }
        }
        emitLine(justify = false, firstOfParagraph = paraIndent && paragraphLineCount == 0)
        queue.forEach { placeFigure(it, text.length) }
        flushSubPage(text.length)
        return subPages
    }

    /**
     * Junta as formas desenhadas em figuras (um gráfico é feito de dezenas de retângulos e linhas)
     * e decide quais são figuras de verdade:
     * - molduras/fundos que envolvem quase todo o texto saem antes de juntar (senão engolem tudo);
     * - caixa com muito texto dentro (quadro de destaque, fundo colorido) não é figura: o texto
     *   dela continua sendo refluído. Fotos são sempre figura.
     */
    private fun figureRegions(rects: List<RectF>, images: List<RectF>, lines: List<Line>): List<RectF> {
        val totalLines = lines.size.coerceAtLeast(1)
        fun isFrame(r: RectF) = totalLines >= 4 && lines.count { r.contains(it.box.centerX(), it.box.centerY()) } >= totalLines * 0.6f
        val regions = rects.filterNot(::isFrame).map { RectF(it) }.toMutableList()
        mergeOverlapping(regions, pad = 0.012f)
        return regions.filter { r ->
            if (isFrame(r)) return@filter false
            val photo = images.any { overlapRatio(r, it) >= 0.5f || overlapRatio(it, r) >= 0.5f }
            if (photo) return@filter true
            val textArea = lines.filter { overlapRatio(it.box, r) >= 0.6f }.sumOf { (it.box.width() * it.box.height()).toDouble() }
            textArea < r.width() * r.height() * 0.3f
        }
    }

    /**
     * Une retângulos que se tocam (com folga [pad]). Faz passadas completas sem recomeçar a cada
     * união: antes, páginas com centenas de formas levavam um tempo cúbico (paginação lenta).
     */
    private fun mergeOverlapping(regions: MutableList<RectF>, pad: Float) {
        regions.sortBy { it.top }
        var changed = true
        while (changed) {
            changed = false
            var a = 0
            while (a < regions.size) {
                val ra = regions[a]
                var b = a + 1
                while (b < regions.size) {
                    val rb = regions[b]
                    if (rb.left <= ra.right + pad && rb.right >= ra.left - pad && rb.top <= ra.bottom + pad && rb.bottom >= ra.top - pad) {
                        ra.union(rb)
                        regions.removeAt(b)
                        changed = true
                    } else b++
                }
                a++
            }
        }
    }

    /** Linhas de título gigante próximas entre si viram um bloco só (desenhado como imagem). */
    private fun groupBlocks(lines: List<Line>, lineH: Float): List<RectF> {
        val blocks = lines.map { RectF(it.box).apply { inset(-lineH * 0.15f, -lineH * 0.15f) } }.toMutableList()
        mergeOverlapping(blocks, pad = lineH * 0.8f)
        return blocks
    }

    /** Fração da área de [a] que está dentro de [b]. */
    private fun overlapRatio(a: RectF, b: RectF): Float {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (w <= 0f || h <= 0f) return 0f
        return (w * h) / (a.width() * a.height()).coerceAtLeast(1e-9f)
    }

    private fun endsWithHyphen(text: PageText, w: Word): Boolean =
        w.end - w.start > 2 && text.text[w.end - 1] in HYPHENS && text.text[w.end - 2].isLetter()

    private companion object {
        /** Hífen comum, hífen tipográfico, hífen inseparável e hífen opcional. */
        const val HYPHENS = "-\u2010\u2011\u00AD"
    }

    private fun trimLastChar(text: PageText, w: Word): Word {
        val box = RectF(text.left(w.start), text.top(w.start), text.right(w.start), text.bottom(w.start))
        for (i in w.start until w.end - 1) box.union(text.left(i), text.top(i), text.right(i), text.bottom(i))
        return Word(w.start, w.end - 1, box)
    }

    /** Agrupa os caracteres em palavras e linhas usando as quebras extraídas do PDF. */
    private fun readLines(t: PageText): List<Line> {
        val lines = ArrayList<Line>()
        var words = ArrayList<Word>()
        var wStart = -1
        var box: RectF? = null

        fun endWord(end: Int) {
            val b = box
            if (wStart >= 0 && b != null && b.width() > 0 && b.height() > 0) words += Word(wStart, end, b)
            wStart = -1
            box = null
        }

        fun endLine() {
            if (words.isNotEmpty()) {
                val lb = RectF(words.first().box)
                words.forEach { lb.union(it.box) }
                lines += Line(words, lb)
            }
            words = ArrayList()
        }

        for (i in 0 until t.length) {
            val c = t.text[i]
            when {
                c == '\n' -> { endWord(i); endLine() }
                c.isWhitespace() -> endWord(i)
                else -> {
                    if (t.right(i) <= t.left(i)) continue
                    // salto grande para trás/baixo dentro da "linha" = na verdade outra linha
                    val b = box
                    // hífen de fim de linha às vezes vem em outra fonte, com caixa mais baixa:
                    // colado à direita da palavra, ele continua na mesma linha (não vira "-" sozinho)
                    val trailingHyphen = c in HYPHENS && b != null && t.left(i) >= b.right - 0.004f && t.left(i) - b.right < 0.02f
                    if (b != null && !trailingHyphen && (t.left(i) < b.left - 0.02f || t.top(i) > b.bottom)) {
                        endWord(i)
                        endLine()
                    }
                    if (wStart < 0) wStart = i
                    val cb = RectF(t.left(i), t.top(i), t.right(i), t.bottom(i))
                    // o hífen deslocado só alarga a palavra; não muda a altura da linha
                    box = if (trailingHyphen) b!!.apply { right = maxOf(right, cb.right) } else box?.apply { union(cb) } ?: cb
                }
            }
        }
        endWord(t.length)
        endLine()
        // mantém a ordem de extração (respeita colunas); só junta pedaços da mesma linha
        return mergeSameRow(lines)
    }

    /** Pedaços que estão na mesma altura (o PDF às vezes quebra a linha no meio) voltam a ser uma linha. */
    private fun mergeSameRow(lines: List<Line>): List<Line> {
        val out = ArrayList<Line>()
        for (l in lines) {
            val last = out.lastOrNull()
            val sameRow = last != null && abs(last.box.centerY() - l.box.centerY()) < last.box.height() * 0.3f && l.box.left >= last.box.right - 0.005f
            if (sameRow) {
                val box = RectF(last!!.box).apply { union(l.box) }
                out[out.lastIndex] = Line(last.words + l.words, box)
            } else {
                out += l
            }
        }
        return out
    }
}
