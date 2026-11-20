package dev.reisarthur.papyrus.reader

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.BulletSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import dev.reisarthur.papyrus.data.Locator
import dev.reisarthur.papyrus.epub.Block
import dev.reisarthur.papyrus.epub.Chapter
import dev.reisarthur.papyrus.epub.Style
import dev.reisarthur.papyrus.ui.Fonts
import dev.reisarthur.papyrus.ui.ReaderTheme
import dev.reisarthur.papyrus.util.TextUtil
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * EPUB paginado com StaticLayout: Literata, texto justificado com hifenização em pt-BR,
 * recuo de parágrafo, títulos centralizados e imagens ajustadas à página.
 * Cada capítulo começa numa página nova, como no Play Livros.
 *
 * Desempenho:
 * - a paginação roda em paralelo, um capítulo por núcleo;
 * - o resultado fica em cache por configuração (voltar a um tamanho de fonte é instantâneo);
 * - uma página é desenhada montando só os parágrafos que ela contém, não o capítulo inteiro,
 *   então pular para qualquer ponto do livro é imediato;
 * - [prepare] calcula em segundo plano configurações prováveis (ex.: fonte ±1) antes do clique.
 */
class EpubContent(private val dir: File) : BookContent {

    /** Página = intervalo de caracteres de um capítulo. Não depende de nenhum layout guardado. */
    private class Page(val chapter: Int, val startOffset: Int, val endOffset: Int)

    /** Layout só dos parágrafos de uma página, com as linhas que ela ocupa. */
    private class PageRender(val layout: StaticLayout, val sliceStart: Int, val firstLine: Int, val endLine: Int)

    private val chapters: List<Chapter>
    private val texts: Array<String>
    private var pages: List<Page> = emptyList()
    private var chapterFirstPage = IntArray(0)
    private var settings: LayoutSettings? = null
    private var currentKey = ""
    private val spannedCache = lru<Int, Spanned>(3)
    private val renders = lru<Int, PageRender>(8)
    private val memCache = lru<String, List<Page>>(4)
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textLocale = TextUtil.PT_BR
        typeface = Fonts.regular
    }
    private val chromePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    init {
        var count = 0
        while (File(dir, "ch_$count.json").exists()) count++
        chapters = (0 until count).map { Chapter.load(File(dir, "ch_$it.json")) }
        texts = Array(count) { chapters[it].assemble().first }
    }

    override val sectionCount get() = chapters.size
    override val pageCount @Synchronized get() = pages.size
    override val hasText = true

    // ---------------- Geometria da página ----------------

    private val LayoutSettings.contentLeft get() = marginPx
    private val LayoutSettings.contentWidth get() = width - marginPx * 2
    private val LayoutSettings.contentTop get() = insetTop + (density * 30).toInt()
    private val LayoutSettings.contentHeight get() = height - contentTop - insetBottom - (density * 40).toInt()

    // ---------------- Paginação ----------------

    override fun layout(settings: LayoutSettings, onProgress: (Int, Int) -> Unit) {
        val key = epubKey(settings)
        // o cálculo pesado roda SEM a trava: a página atual continua sendo desenhada enquanto isso
        val newPages = if (key == synchronized(this) { currentKey }) null else obtainPages(settings, key, parallel = true, onProgress)
        synchronized(this) {
            settings.also { this.settings = it }
            paint.textSize = settings.fontPx
            paint.color = settings.theme.text
            spannedCache.clear()
            renders.clear()
            if (newPages != null) {
                pages = newPages
                currentKey = key
                chapterFirstPage = IntArray(chapters.size) { -1 }
                pages.forEachIndexed { i, p -> if (chapterFirstPage[p.chapter] < 0) chapterFirstPage[p.chapter] = i }
            }
        }
    }

    /**
     * Pré-calcula a paginação de uma configuração provável (painel de texto aberto).
     * Usa todos os núcleos; se o usuário escolher essa opção no meio do cálculo, [layout]
     * aproveita o mesmo cálculo em vez de começar outro.
     */
    fun prepare(settings: LayoutSettings) {
        val key = epubKey(settings)
        if (synchronized(this) { key == currentKey || memCache[key] != null } || cacheFile(key).exists()) return
        obtainPages(settings, key, parallel = true) { _, _ -> }
    }

    /** Cálculos em andamento por configuração: pedidos repetidos esperam o mesmo resultado. */
    private val inflight = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.FutureTask<List<Page>>>()

    private fun obtainPages(s: LayoutSettings, key: String, parallel: Boolean, onProgress: (Int, Int) -> Unit): List<Page> {
        synchronized(this) { memCache[key] }?.let { return it }
        loadCachedPages(key)?.let { cached -> synchronized(this) { memCache[key] = cached }; return cached }
        val task = java.util.concurrent.FutureTask { computePages(s, parallel, onProgress) }
        val running = inflight.putIfAbsent(key, task)
        if (running != null) return running.get()
        try {
            task.run()
            val result = task.get()
            saveCachedPages(key, result)
            synchronized(this) { memCache[key] = result }
            return result
        } finally {
            inflight.remove(key)
        }
    }

    /** Pagina todos os capítulos; em paralelo, cada tarefa usa o próprio TextPaint (sem estado compartilhado). */
    private fun computePages(s: LayoutSettings, parallel: Boolean, onProgress: (Int, Int) -> Unit): List<Page> {
        val done = AtomicInteger()
        val task = { c: Int ->
            val p = TextPaint(paint).apply { textSize = s.fontPx; color = s.theme.text }
            val text = buildSpanned(chapters[c], s)
            paginate(c, buildLayout(text, p, s), s).also { onProgress(done.incrementAndGet(), chapters.size) }
        }
        val perChapter: List<List<Page>> = if (parallel && chapters.size > 1) {
            // capítulos maiores primeiro: divide melhor o trabalho entre os núcleos
            val order = chapters.indices.sortedByDescending { texts[it].length }
            val futures = order.associateWith { c -> POOL.submit(Callable { task(c) }) }
            chapters.indices.map { futures.getValue(it).get() }
        } else {
            chapters.indices.map(task)
        }
        return perChapter.flatten()
    }

    private fun paginate(chapter: Int, layout: StaticLayout, s: LayoutSettings): List<Page> {
        val out = ArrayList<Page>()
        val maxH = s.contentHeight
        // o texto termina com \n, o que gera uma última linha vazia: ignorá-la evita página em branco
        var lineCount = layout.lineCount
        while (lineCount > 1 && layout.getLineStart(lineCount - 1) >= layout.text.length) lineCount--
        var line = 0
        while (line < lineCount) {
            val top = layout.getLineTop(line)
            var end = line
            while (end < lineCount && layout.getLineBottom(end) - top <= maxH) end++
            if (end == line) end = line + 1 // linha mais alta que a página (imagem enorme)
            out += Page(chapter, layout.getLineStart(line), layout.getLineEnd(end - 1))
            line = end
        }
        if (out.isEmpty()) out += Page(chapter, 0, 0)
        return out
    }

    /** Só o que muda a quebra de páginas do EPUB (tema e zoom do PDF não entram). */
    private fun epubKey(s: LayoutSettings) =
        s.copy(theme = ReaderTheme.LIGHT, pdfInvert = false).paginationKey() + "|f4"

    private fun cacheFile(key: String) = File(dir, "pages_${Integer.toHexString(key.hashCode())}.cache")

    private fun loadCachedPages(key: String): List<Page>? = runCatching {
        val f = cacheFile(key)
        if (!f.exists()) return null
        DataInputStream(f.inputStream().buffered()).use { input ->
            if (input.readUTF() != key) return null
            val n = input.readInt()
            List(n) { Page(input.readInt(), input.readInt(), input.readInt()) }
        }.also { f.setLastModified(System.currentTimeMillis()) }
    }.getOrNull()

    private fun saveCachedPages(key: String, list: List<Page>) {
        runCatching {
            DataOutputStream(cacheFile(key).outputStream().buffered()).use { out ->
                out.writeUTF(key)
                out.writeInt(list.size)
                list.forEach { p -> out.writeInt(p.chapter); out.writeInt(p.startOffset); out.writeInt(p.endOffset) }
            }
            // guarda só as configurações usadas mais recentemente
            dir.listFiles { f -> f.name.startsWith("pages_") }?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_CACHE_FILES)?.forEach { it.delete() }
        }
    }

    // ---------------- Montagem do texto com estilos ----------------

    private fun buildLayout(text: CharSequence, p: TextPaint, s: LayoutSettings): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, s.contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(s.fontPx * (s.lineSpacing - 1f), 1f)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            .setJustificationMode(if (s.justify) Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE)
            .build()

    private fun spannedFor(chapter: Int, s: LayoutSettings): Spanned =
        spannedCache[chapter] ?: buildSpanned(chapters[chapter], s).also { spannedCache[chapter] = it }

    /**
     * Monta o layout só dos parágrafos que a página toca. Como o Android quebra cada parágrafo
     * de forma independente, as linhas saem idênticas às da paginação do capítulo inteiro.
     */
    private fun renderFor(page: Int, s: LayoutSettings): PageRender? {
        renders[page]?.let { return it }
        val p = pages.getOrNull(page) ?: return null
        val str = texts[p.chapter]
        val a = if (p.startOffset <= 0) 0 else str.lastIndexOf('\n', p.startOffset - 1) + 1
        val lastChar = maxOf(p.endOffset - 1, a)
        val nl = str.indexOf('\n', lastChar)
        val b = if (nl < 0) str.length else nl + 1
        val slice = spannedFor(p.chapter, s).subSequence(a, b)
        var layout = buildLayout(slice, paint, s)
        var base = a
        var first = layout.getLineForOffset(p.startOffset - base)
        if (layout.getLineStart(first) != p.startOffset - base) {
            // não deveria acontecer; se acontecer, a página sai certa mesmo que um pouco mais devagar
            layout = buildLayout(spannedFor(p.chapter, s), paint, s)
            base = 0
            first = layout.getLineForOffset(p.startOffset)
        }
        val end = if (p.endOffset > p.startOffset) layout.getLineForOffset(p.endOffset - 1 - base) + 1 else first
        return PageRender(layout, base, first, end).also { renders[page] = it }
    }

    /**
     * Converte blocos em texto com spans. O texto inteiro é montado ANTES de qualquer span:
     * num SpannableStringBuilder, um span de parágrafo que termina no fim do texto "cresce"
     * quando se anexa mais texto, e o recuo/alinhamento de um parágrafo vazaria para os seguintes.
     * A ordem dos caracteres vem de [Chapter.assemble], a mesma usada nos offsets salvos.
     */
    private fun buildSpanned(chapter: Chapter, s: LayoutSettings): Spanned {
        val (plain, starts) = chapter.assemble()
        val sb = SpannableString(plain)
        val em = s.fontPx
        val indent = (em * 1.1f).toInt()
        val paraGap = (em * 0.35f).toInt()
        chapter.blocks.forEachIndexed { i, block ->
            val start = starts[i]
            val end = start + Chapter.blockText(block).length + 1 // inclui o \n do parágrafo
            when (block) {
                is Block.Para -> {
                    var pos = start
                    for (run in block.runs) {
                        applyRunStyle(sb, pos, pos + run.text.length, run.style)
                        pos += run.text.length
                    }
                    applyParagraphStyle(sb, start, end, block, s, indent, paraGap)
                }
                is Block.Image -> {
                    val maxW = s.contentWidth.toFloat()
                    val maxH = s.contentHeight * 0.9f
                    val scale = minOf(maxW / block.width, maxH / block.height, s.density * 1.25f)
                    val w = (block.width * scale).toInt().coerceAtLeast(1)
                    val h = (block.height * scale).toInt().coerceAtLeast(1)
                    sb.setSpan(ImageBlockSpan(File(dir, block.file), w, h), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, end, Spanned.SPAN_PARAGRAPH)
                    sb.setSpan(ParagraphSpacingSpan(paraGap, paraGap * 2), start, end, Spanned.SPAN_PARAGRAPH)
                }
                Block.Separator -> {
                    sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, end, Spanned.SPAN_PARAGRAPH)
                    sb.setSpan(ParagraphSpacingSpan((em * 0.8f).toInt(), (em * 0.8f).toInt()), start, end, Spanned.SPAN_PARAGRAPH)
                }
            }
        }
        return sb
    }

    private fun applyRunStyle(sb: Spannable, start: Int, end: Int, style: Int) {
        if (start >= end || style == 0) return
        val bold = style and Style.BOLD != 0
        val italic = style and Style.ITALIC != 0
        if (bold || italic) sb.setSpan(FontSpan(Fonts.of(bold, italic)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (style and Style.SUP != 0) {
            sb.setSpan(SuperscriptSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.7f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (style and Style.SUB != 0) {
            sb.setSpan(SubscriptSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.7f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (style and Style.MONO != 0) sb.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (style and Style.SMALL_CAPS != 0) sb.setSpan(RelativeSizeSpan(0.85f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun applyParagraphStyle(
        sb: Spannable, start: Int, end: Int, p: Block.Para,
        s: LayoutSettings, indent: Int, paraGap: Int,
    ) {
        val em = s.fontPx
        val tight = p.flags and Block.TIGHT != 0
        fun para(span: Any) = sb.setSpan(span, start, end, Spanned.SPAN_PARAGRAPH)
        when (p.kind) {
            Block.Kind.H1, Block.Kind.H2, Block.Kind.H3 -> {
                val size = when (p.kind) { Block.Kind.H1 -> 1.55f; Block.Kind.H2 -> 1.35f; else -> 1.15f }
                sb.setSpan(RelativeSizeSpan(size), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(FontSpan(Fonts.bold), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                para(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER))
                para(ParagraphSpacingSpan((em * 1.2f).toInt(), (em * 1.1f).toInt()))
            }
            Block.Kind.QUOTE -> {
                para(LeadingMarginSpan.Standard(indent * 2 / 3, indent * 2 / 3))
                para(ParagraphSpacingSpan(0, if (tight) 0 else paraGap))
            }
            Block.Kind.CENTER -> {
                para(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER))
                para(ParagraphSpacingSpan(0, if (tight) 0 else paraGap))
            }
            Block.Kind.RIGHT -> {
                para(AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE))
                para(ParagraphSpacingSpan(0, if (tight) 0 else paraGap))
            }
            Block.Kind.LIST -> {
                para(BulletSpan(indent / 2, s.theme.secondaryText))
                para(ParagraphSpacingSpan(0, if (tight) 0 else paraGap / 2))
            }
            Block.Kind.PRE -> {
                sb.setSpan(RelativeSizeSpan(0.85f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            Block.Kind.BODY -> {
                if (p.flags and Block.NO_INDENT == 0) para(LeadingMarginSpan.Standard(indent, 0))
                para(ParagraphSpacingSpan(0, if (tight) 0 else paraGap))
            }
        }
    }

    // ---------------- Navegação ----------------

    @Synchronized
    override fun pageOf(loc: Locator): Int {
        if (pages.isEmpty()) return 0
        val ch = loc.section.coerceIn(0, chapters.size - 1)
        var p = chapterFirstPage.getOrElse(ch) { 0 }.coerceAtLeast(0)
        while (p + 1 < pages.size && pages[p + 1].chapter == ch && pages[p + 1].startOffset <= loc.offset) p++
        return p
    }

    @Synchronized
    override fun pageStart(page: Int): Locator = pages.getOrNull(page)?.let { Locator(it.chapter, it.startOffset) } ?: Locator(0, 0)

    @Synchronized
    override fun pageEnd(page: Int): Locator = pages.getOrNull(page)?.let { Locator(it.chapter, it.endOffset) } ?: Locator(0, 0)

    override fun sectionText(section: Int): String = texts.getOrElse(section) { "" }
    override fun sectionTitle(section: Int): String = chapters.getOrNull(section)?.title.orEmpty()

    // ---------------- Desenho ----------------

    @Synchronized
    override fun drawPage(page: Int, canvas: Canvas, marks: List<Mark>) {
        val s = settings ?: return
        canvas.drawColor(s.theme.background)
        val p = pages.getOrNull(page) ?: return

        // só o número da página no rodapé, discreto
        chromePaint.textSize = 11.5f * s.density
        chromePaint.color = s.theme.secondaryText
        chromePaint.typeface = Fonts.regular
        chromePaint.textAlign = Paint.Align.CENTER
        canvas.drawText("${page + 1}", s.width / 2f, s.height - s.insetBottom - s.density * 16, chromePaint)

        val r = renderFor(page, s) ?: return
        if (r.endLine <= r.firstLine) return
        val layout = r.layout
        val dy = s.contentTop - layout.getLineTop(r.firstLine).toFloat()

        // destaques por baixo do texto
        val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = s.density * 3
        for (m in marks) {
            hlPaint.color = m.color
            rectsInternal(p, r, m.start, m.end, s).forEach { canvas.drawRoundRect(it, radius, radius, hlPaint) }
        }

        canvas.save()
        val visibleH = layout.getLineBottom(r.endLine - 1) - layout.getLineTop(r.firstLine)
        canvas.clipRect(0f, s.contentTop.toFloat(), s.width.toFloat(), s.contentTop + visibleH + 1f)
        canvas.translate(s.contentLeft.toFloat(), dy)
        layout.draw(canvas)
        canvas.restore()
    }

    @Synchronized
    override fun charAt(page: Int, x: Float, y: Float): Locator? {
        val s = settings ?: return null
        val p = pages.getOrNull(page) ?: return null
        val r = renderFor(page, s) ?: return null
        if (r.endLine <= r.firstLine) return null
        val layout = r.layout
        val ly = (y - s.contentTop + layout.getLineTop(r.firstLine)).toInt()
        val line = layout.getLineForVertical(ly).coerceIn(r.firstLine, r.endLine - 1)
        val local = charIndexAt(layout, line, x - s.contentLeft)
        return Locator(p.chapter, (local + r.sliceStart).coerceIn(0, maxOf(0, texts[p.chapter].length - 1)))
    }

    @Synchronized
    override fun rects(page: Int, start: Locator, end: Locator): List<RectF> {
        val s = settings ?: return emptyList()
        val p = pages.getOrNull(page) ?: return emptyList()
        return rectsInternal(p, renderFor(page, s) ?: return emptyList(), start, end, s)
    }

    private fun rectsInternal(p: Page, r: PageRender, start: Locator, end: Locator, s: LayoutSettings): List<RectF> {
        val from = if (start.section < p.chapter) 0 else if (start.section > p.chapter) return emptyList() else start.offset
        val to = if (end.section > p.chapter) Int.MAX_VALUE else if (end.section < p.chapter) return emptyList() else end.offset
        // offsets do capítulo -> offsets do trecho montado
        val a = maxOf(from, p.startOffset) - r.sliceStart
        val b = minOf(to, p.endOffset) - r.sliceStart
        if (a >= b) return emptyList()
        val layout = r.layout
        val text = texts[p.chapter]
        val out = ArrayList<RectF>()
        val dy = s.contentTop - layout.getLineTop(r.firstLine).toFloat()
        for (line in layout.getLineForOffset(a)..layout.getLineForOffset(maxOf(a, b - 1))) {
            val ls = maxOf(a, layout.getLineStart(line))
            var le = minOf(b, layout.getLineEnd(line))
            // não destaca o \n nem o espaço final da linha
            while (le > ls && text.getOrNull(le - 1 + r.sliceStart)?.isWhitespace() == true) le--
            if (le <= ls) continue
            val left = xOf(layout, line, ls)
            val right = if (le >= layout.getLineEnd(line)) layout.getLineRight(line) else xOf(layout, line, le)
            // rente às letras: do topo das maiúsculas/acentos (~0,8 em) até o fim das descendentes
            // (~0,24 em). As métricas da fonte são bem mais altas que isso e deixavam folga.
            val baseline = layout.getLineBaseline(line).toFloat()
            val em = paint.textSize
            out += RectF(
                s.contentLeft + minOf(left, right), baseline - em * 0.80f + dy,
                s.contentLeft + maxOf(left, right), baseline + em * 0.24f + dy,
            )
        }
        return out
    }

    // ---------------- Posição das letras em linhas justificadas ----------------

    /**
     * Posição x (no layout) do início do caractere [offset] da linha [line], como ele é DESENHADO.
     *
     * O Android desenha linhas justificadas com espaço extra entre as palavras, mas
     * getPrimaryHorizontal/getOffsetForHorizontal devolvem a posição sem esse espaço: a seleção
     * ia ficando para a esquerda das letras a cada palavra. Aqui o extra é recalculado do mesmo
     * jeito que o Android faz (TextLine.justify): a sobra da linha dividida igualmente pelos
     * espaços comuns (U+0020), sem contar os do fim da linha.
     */
    private fun xOf(layout: Layout, line: Int, offset: Int): Float {
        val natural = layout.getPrimaryHorizontal(offset)
        val extra = justifyExtra(layout, line)
        if (extra == 0f) return natural
        val text = layout.text
        var spaces = 0
        for (i in layout.getLineStart(line) until offset) if (text[i] == ' ') spaces++
        return natural + extra * spaces
    }

    /** Espaço extra por espaço comum numa linha justificada (0 se a linha não é esticada). */
    private fun justifyExtra(layout: Layout, line: Int): Float {
        if (layout.justificationMode == Layout.JUSTIFICATION_MODE_NONE) return 0f
        val start = layout.getLineStart(line)
        val lineEnd = layout.getLineEnd(line)
        val text = layout.text
        // a última linha de cada parágrafo (e do texto) não é justificada
        if (line == layout.lineCount - 1 || (lineEnd > start && text[lineEnd - 1] == '\n')) return 0f
        var end = lineEnd
        while (end > start && text[end - 1].isWhitespace()) end--
        var spaces = 0
        for (i in start until end) if (text[i] == ' ') spaces++
        if (spaces == 0) return 0f
        val startX = layout.getPrimaryHorizontal(start)
        val available = layout.getParagraphRight(line) - startX
        val natural = layout.getPrimaryHorizontal(end) - startX + hyphenWidth(layout, line)
        // se o próprio Android já devolveu a posição justificada (outras versões), não soma de novo
        if (natural >= available - 1f) return 0f
        return (available - natural) / spaces
    }

    /** Hífen automático no fim da linha: ocupa largura, mas não existe no texto. */
    private fun hyphenWidth(layout: Layout, line: Int): Float =
        if (android.os.Build.VERSION.SDK_INT >= 29 && layout.getEndHyphenEdit(line) != 0) layout.paint.measureText("-") else 0f

    /** Índice do caractere sob o ponto [x] da linha, considerando a justificação. */
    private fun charIndexAt(layout: Layout, line: Int, x: Float): Int {
        val start = layout.getLineStart(line)
        var end = layout.getLineEnd(line)
        while (end > start + 1 && layout.text[end - 1] == '\n') end--
        // último caractere cujo início está à esquerda do dedo
        var lo = start
        var hi = end - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (xOf(layout, line, mid) <= x) lo = mid else hi = mid - 1
        }
        return lo
    }

    // ---------------- Sumário ----------------

    /** Confere o sumário pelos títulos dos capítulos (ver [TocResolver]). */
    fun resolveToc(toc: List<dev.reisarthur.papyrus.data.TocEntry>) = dev.reisarthur.papyrus.epub.TocResolver(chapters).resolve(toc)

    override fun close() = Unit

    private companion object {
        const val MAX_CACHE_FILES = 12

        /** Um núcleo livre para a interface; o resto pagina. */
        val POOL = Executors.newFixedThreadPool(maxOf(2, Runtime.getRuntime().availableProcessors() - 1)) { r ->
            Thread(r, "paginacao").apply { isDaemon = true }
        }

        fun <K, V> lru(max: Int) = object : LinkedHashMap<K, V>(max * 2, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
        }
    }
}
