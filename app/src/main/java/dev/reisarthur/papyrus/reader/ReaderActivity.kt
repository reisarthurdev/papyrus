package dev.reisarthur.papyrus.reader

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import dev.reisarthur.papyrus.R
import dev.reisarthur.papyrus.data.Annotation
import dev.reisarthur.papyrus.data.Book
import dev.reisarthur.papyrus.data.BookFiles
import dev.reisarthur.papyrus.data.BookFormat
import dev.reisarthur.papyrus.data.Bookmark
import dev.reisarthur.papyrus.data.Database
import dev.reisarthur.papyrus.data.Locator
import dev.reisarthur.papyrus.data.Prefs
import dev.reisarthur.papyrus.data.TocEntry
import dev.reisarthur.papyrus.library.TocIO
import dev.reisarthur.papyrus.ui.AppColors
import dev.reisarthur.papyrus.ui.HighlightColor
import dev.reisarthur.papyrus.ui.MenuEntry
import dev.reisarthur.papyrus.ui.showM3Menu
import dev.reisarthur.papyrus.ui.tintM3
import dev.reisarthur.papyrus.ui.alert
import dev.reisarthur.papyrus.ui.ReaderTheme
import dev.reisarthur.papyrus.ui.dp
import dev.reisarthur.papyrus.ui.dpf
import dev.reisarthur.papyrus.ui.horizontal
import dev.reisarthur.papyrus.ui.oval
import dev.reisarthur.papyrus.ui.ripple
import dev.reisarthur.papyrus.ui.spacer
import dev.reisarthur.papyrus.ui.crossfade
import dev.reisarthur.papyrus.ui.roundRect
import dev.reisarthur.papyrus.ui.text
import dev.reisarthur.papyrus.ui.vertical
import dev.reisarthur.papyrus.ui.weight
import dev.reisarthur.papyrus.util.Bg
import dev.reisarthur.papyrus.util.TextUtil

/**
 * Tela de leitura. Coordena: paginação (BookContent), virada 3D (PageCurlView),
 * seleção/destaques (SelectionOverlay), barras de controle, busca, sumário,
 * marcadores, dicionário e leitura em voz alta.
 */
class ReaderActivity : Activity(),
    PageCurlView.Provider, PageCurlView.Listener, SelectionOverlay.Callbacks {

    private val db get() = Database.instance
    private lateinit var book: Book
    private var content: BookContent? = null
    private var settings: LayoutSettings? = null
    private var toc: List<TocEntry> = emptyList()
    @Volatile private var annotations: List<Annotation> = emptyList()
    @Volatile private var bookmarks: List<Bookmark> = emptyList()

    // ---------------- Views ----------------
    private lateinit var root: FrameLayout
    private lateinit var curl: PageCurlView
    private lateinit var overlay: SelectionOverlay
    private lateinit var ribbon: RibbonAnimView
    private lateinit var bottomBar: LinearLayout
    private lateinit var pageLabel: TextView
    private lateinit var seek: SeekBar
    private lateinit var moreButton: ImageView
    private lateinit var loading: LinearLayout
    private lateinit var loadingText: TextView
    private val chromeIcons = ArrayList<ImageView>()
    private val chromeTexts = ArrayList<TextView>()
    private val colors get() = AppColors(Prefs.theme.isDark)

    private var barsVisible = false
    private var insetTop = 0
    private var insetBottom = 0

    // ---------------- Estado de leitura ----------------
    private var selStart: Locator? = null
    private var selEnd: Locator? = null
    private var popup: PopupWindow? = null
    private var searchHit: SearchHit? = null
    private var turning = false
    /** Início da página atual: âncora para manter o trecho ao repaginar. */
    private var anchor = Locator(0, 0)
    /** Repaginando em segundo plano: a UI não consulta o conteúdo nesse meio-tempo. */
    private var busy = false
    private val relayoutTask = Runnable { relayout() }

    // ---------------- Cache de páginas renderizadas ----------------
    private val renderLock = Any()
    private var cacheGeneration = 0
    private val pageCache = LruCache<Int, Bitmap>(4)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra(EXTRA_BOOK_ID, -1)
        book = db.book(id) ?: run { finish(); return }
        if (Build.VERSION.SDK_INT >= 28) {
            // usa a área do notch também (o texto respeita a área segura via insets)
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        // barra de navegação transparente por cima da página, sem o "véu" que o sistema coloca
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.slide_in_right, R.anim.hold)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.hold, R.anim.slide_out_right)
        }
        buildViews()
        applyChromeTheme()
        enterImmersive()
        root.post { load() }
    }

    // =====================================================================
    // Carregamento e paginação
    // =====================================================================

    private fun currentSettings(): LayoutSettings =
        readerSettings(this, book.format, root.width, root.height, insetTop, insetBottom)

    private fun load() {
        if (root.width == 0) { root.post { load() }; return }
        showLoading("Abrindo livro…")
        val s = currentSettings()
        // guarda o tamanho da tela: os próximos livros importados já chegam paginados para ele
        Prefs.readerGeometry = intArrayOf(s.width, s.height, s.insetTop, s.insetBottom)
        val dir = BookFiles.dir(filesDir, book.id)
        Bg.load({
            val c: BookContent = if (book.format == BookFormat.EPUB) {
                EpubContent(dir)
            } else {
                PdfContent(this, android.net.Uri.parse(book.uri), dir, TocIO.load(BookFiles.toc(filesDir, book.id)))
            }
            c.layout(s) { done, total -> Bg.main { loadingText.text = "Paginando… ${done * 100 / maxOf(1, total)}%" } }
            toc = TocIO.load(BookFiles.toc(filesDir, book.id)).let { saved ->
                // EPUB: confere cada capítulo pelo título (posições salvas podem estar erradas)
                (c as? EpubContent)?.resolveToc(saved) ?: saved
            }
            annotations = db.annotations(book.id)
            bookmarks = db.bookmarks(book.id)
            c
        }) { result ->
            hideLoading()
            val c = result.getOrElse {
                Toast.makeText(this, "Não foi possível abrir o livro: ${it.message}", Toast.LENGTH_LONG).show()
                finish()
                return@load
            }
            content = c
            settings = s
            curl.provider = this
            curl.listener = this
            seek.max = maxOf(0, c.pageCount - 1)
            curl.showPage(c.pageOf(book.lastLocator))
            onPageShown()
            if (book.lastOpened == 0L) showBars(true)
        }
    }

    /**
     * Troca de tema no mesmo instante em que o painel muda de cor: só as cores mudam (a paginação é
     * a mesma), então tudo roda aqui mesmo, sem fila nem outra thread. A página atual já é redesenhada
     * antes do próximo quadro, que só precisa copiar o resultado — painel e livro mudam juntos.
     */
    private fun applyThemeNow() {
        val c = content ?: return
        val s = currentSettings()
        if (busy || settings?.paginationKey() != s.paginationKey()) { scheduleRelayout(0); return }
        c.layout(s)
        settings = s
        invalidatePages()
        bitmap(curl.currentPage)
        curl.refresh()
        refreshOverlay()
    }

    /** Agrupa cliques seguidos (ex.: A+ A+ A+) numa repaginação só. */
    private fun scheduleRelayout(delayMs: Long = 120) {
        Bg.cancel(relayoutTask)
        Bg.mainDelayed(delayMs, relayoutTask)
    }

    /** Repagina mantendo o leitor no mesmo trecho (tamanho da fonte, margens, rotação…). */
    private fun relayout() {
        val c = content ?: return
        if (busy) { scheduleRelayout(); return }
        val target = anchor
        val s = currentSettings()
        if (s.width == 0 || s.height == 0) return
        busy = true
        // o aviso "Paginando…" só aparece se demorar: trocas rápidas não piscam a tela
        val veil = Runnable { if (busy) showLoading("Paginando…") }
        Bg.mainDelayed(250, veil)
        Bg.load({ c.layout(s) }) {
            busy = false
            Bg.cancel(veil)
            hideLoading()
            settings = s
            invalidatePages()
            seek.max = maxOf(0, c.pageCount - 1)
            curl.showPage(c.pageOf(target))
            onPageShown()
            if (settingsOpen) speculate()
        }
    }

    // ---------------- Pré-cálculo especulativo ----------------

    /** Painel de texto aberto: grande chance de o próximo toque ser A+ ou A−. */
    private var settingsOpen = false

    /**
     * Pagina em segundo plano (um núcleo, prioridade baixa) os tamanhos de fonte vizinhos.
     * Quando o usuário tocar em A+/A−, o resultado já está em cache e a troca é instantânea.
     */
    private fun speculate() {
        val c = content as? EpubContent ?: return
        val base = currentSettings()
        if (base.width == 0) return
        val dm = resources.displayMetrics
        fun sp(v: Float) = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, v, dm)
        // na ordem do que é mais provável tocar em seguida
        val candidates = buildList {
            listOf(Prefs.fontSize + 1, Prefs.fontSize - 1).filter { it in Prefs.MIN_FONT..Prefs.MAX_FONT }
                .forEach { add(base.copy(fontPx = sp(it))) }
            MARGINS.filter { it != Prefs.margin }.forEach { add(base.copy(marginPx = dp(it))) }
            SPACINGS.filter { it != Prefs.lineSpacing }.forEach { add(base.copy(lineSpacing = it)) }
        }
        candidates.forEach { s -> SPECULATOR.execute { runCatching { c.prepare(s) } } }
    }

    // =====================================================================
    // Renderização (PageCurlView.Provider)
    // =====================================================================

    override val pageCount get() = content?.pageCount ?: 0
    override val paperColor get() = settings?.theme?.background ?: Color.WHITE

    override fun bitmap(page: Int): Bitmap? {
        val c = content ?: return null
        val s = settings ?: return null
        if (page !in 0 until c.pageCount) return null
        synchronized(renderLock) {
            pageCache.get(page)?.let { return it }
            val gen = cacheGeneration
            val bmp = Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            c.drawPage(page, canvas, marksFor(c, page, s.theme))
            if (isBookmarked(page)) drawRibbon(canvas, s)
            if (gen == cacheGeneration) pageCache.put(page, bmp)
            return bmp
        }
    }

    override fun renderViewport(page: Int, zoom: Float, panX: Float, panY: Float, done: (Bitmap) -> Unit) {
        val c = content ?: return
        val s = settings ?: return
        Bg.load({
            val bmp = Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ARGB_8888)
            c.drawViewport(page, Canvas(bmp), marksFor(c, page, s.theme), zoom, panX, panY)
            bmp
        }) { r -> r.getOrNull()?.let(done) }
    }

    private fun marksFor(c: BookContent, page: Int, theme: ReaderTheme): List<Mark> {
        val start = c.pageStart(page)
        val end = c.pageEnd(page)
        return annotations.filter { it.start < end && it.end > start }
            .map { Mark(it.start, it.end, HighlightColor.fromOrdinal(it.color).overlay(theme.isDark)) }
    }

    /** Fitinha de marcador no canto superior direito (vira junto com a página). */
    private fun drawRibbon(canvas: Canvas, s: LayoutSettings) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AppColors(s.theme.isDark).primary }
        canvas.drawPath(BookmarkRibbon.path(s.width, s.insetTop, s.density), paint)
    }

    private fun invalidatePages() {
        synchronized(renderLock) {
            cacheGeneration++
            pageCache.evictAll()
        }
        curl.refresh()
    }

    private fun prefetchNeighbors() {
        val page = curl.currentPage
        Bg.io {
            bitmap(page + 1)
            bitmap(page - 1)
        }
    }

    // =====================================================================
    // Eventos da página (PageCurlView.Listener)
    // =====================================================================

    override fun onPageChanged(page: Int) {
        onPageShown()
    }

    private fun onPageShown() {
        val c = content ?: return
        val page = curl.currentPage
        seek.progress = page
        updatePageLabels(page)
        refreshOverlay()
        prefetchNeighbors()
        val at = c.pageStart(page)
        anchor = at
        val progress = (page + 1f) / maxOf(1, c.pageCount)
        Bg.io { db.saveProgress(book.id, at, progress) }
    }

    override fun onTurnStart() {
        turning = true
        clearSelection()
        overlay.visibility = View.INVISIBLE
        if (barsVisible) showBars(false)
    }

    override fun onTurnEnd() {
        turning = false
        overlay.visibility = View.VISIBLE
        refreshOverlay()
    }

    override fun onTap(x: Float, y: Float): Boolean {
        if (barsVisible) { showBars(false); return true }
        val c = content ?: return true
        // tocar numa marcação seleciona o trecho marcado e abre o menu (as coordenadas só batem sem zoom)
        if (c.hasText && !curl.isZoomed) {
            c.charAt(curl.currentPage, x, y)?.let { loc ->
                annotations.firstOrNull { loc >= it.start && loc < it.end }?.let { a ->
                    val r = c.rects(curl.currentPage, a.start, a.end)
                    if (r.any { it.contains(x, y) }) {
                        selStart = a.start
                        selEnd = a.end
                        refreshOverlay()
                        showSelectionMenu()
                        return true
                    }
                }
            }
        }
        if (x > root.width * 0.32f && x < root.width * 0.68f) {
            showBars(true)
            return true
        }
        return false
    }

    override fun onLongPress(x: Float, y: Float) {
        val c = content ?: return
        if (!c.hasText) {
            Toast.makeText(this, "Este PDF não tem texto selecionável.", Toast.LENGTH_SHORT).show()
            return
        }
        val loc = c.charAt(curl.currentPage, x, y) ?: return
        val word = TextUtil.wordAt(c.sectionText(loc.section), loc.offset) ?: return
        curl.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        selStart = Locator(loc.section, word.first)
        selEnd = Locator(loc.section, word.last + 1)
        if (barsVisible) showBars(false)
        refreshOverlay()
        showSelectionMenu()
    }

    override fun onZoomChanged(zoomed: Boolean) {
        if (zoomed) {
            clearSelection()
            if (barsVisible) showBars(false)
        }
        // seleção e destaque de leitura são desenhados na escala normal: escondidos enquanto ampliado
        overlay.visibility = if (zoomed) View.INVISIBLE else View.VISIBLE
    }

    // =====================================================================
    // Seleção, destaques e notas
    // =====================================================================

    private fun refreshOverlay() {
        val c = content ?: return
        val page = curl.currentPage
        val s = selStart
        val e = selEnd
        overlay.setSelection(if (s != null && e != null) c.rects(page, s, e) else emptyList())
        overlay.setSearchHit(searchHit?.let { c.rects(page, it.start, it.end) }.orEmpty())
    }

    override fun onHandleMoved(isStart: Boolean, x: Float, y: Float) {
        val c = content ?: return
        val loc = c.charAt(curl.currentPage, x, y) ?: return
        val text = c.sectionText(loc.section)
        val word = TextUtil.wordAt(text, loc.offset)
        popup?.dismiss()
        if (isStart) {
            val start = Locator(loc.section, word?.first ?: loc.offset)
            val end = selEnd ?: return
            if (start < end) selStart = start
        } else {
            val end = Locator(loc.section, (word?.last ?: loc.offset) + 1)
            val start = selStart ?: return
            if (end > start) selEnd = end
        }
        refreshOverlay()
    }

    override fun onHandleReleased() = showSelectionMenu()

    override fun onDismissSelection() = clearSelection()

    private fun clearSelection() {
        selStart = null
        selEnd = null
        popup?.dismiss()
        popup = null
        overlay.setSelection(emptyList())
    }

    private fun selectedText(): String {
        val c = content ?: return ""
        val s = selStart ?: return ""
        val e = selEnd ?: return ""
        return c.sectionText(s.section).substring(s.offset, minOf(e.offset, c.sectionText(s.section).length))
    }

    /** Menu flutuante sobre a seleção: 4 cores + Nota, Definição, Copiar e, se já marcado, Excluir marcação. */
    private fun showSelectionMenu() {
        val bounds = overlay.selectionBounds() ?: return
        popup?.dismiss()
        val c = colors
        val marked = overlappingSelection()
        val markedColor = marked.firstOrNull()?.color
        val box = vertical(dp(6)).apply {
            background = roundRect(c.surfaceContainer, dpf(16))
            elevation = dpf(3)
        }
        // cores e opções ficam "espaçadas por igual": a largura que sobrar na caixa é dividida
        // igualmente entre os itens, então o espaçamento não depende do tamanho do menu
        val dots = HighlightColor.entries.map { hc ->
            // a cor já aplicada ganha um anel: tocar nela de novo desmarca
            val ring = if (hc.ordinal == markedColor) c.onSurface else Color.TRANSPARENT
            ReaderDialogs.colorDot(this, hc, ring) { applyColor(hc.ordinal) }
        }
        box.addView(evenRow(dots) { LinearLayout.LayoutParams(dp(30), dp(30)).apply { setMargins(dp(8), dp(6), dp(8), dp(4)) } }, LinearLayout.LayoutParams(-1, -2))
        val buttons = buildList {
            add(ReaderDialogs.menuButton(this@ReaderActivity, "Nota", c.primary) { noteForSelection() })
            add(ReaderDialogs.menuButton(this@ReaderActivity, "Definição", c.primary) { defineSelection() })
            add(ReaderDialogs.menuButton(this@ReaderActivity, "Copiar", c.primary) { copySelection() })
            if (marked.isNotEmpty()) add(ReaderDialogs.menuButton(this@ReaderActivity, "Excluir marcação", c.primary) { removeMarking() })
        }
        box.addView(evenRow(buttons) { LinearLayout.LayoutParams(-2, -2) }, LinearLayout.LayoutParams(-1, -2))
        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)

        // largura exata = a linha mais larga; as outras linhas distribuem a sobra igualmente
        val p = PopupWindow(box, box.measuredWidth, ViewGroup.LayoutParams.WRAP_CONTENT, false)
        p.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        p.elevation = dpf(3)
        p.isOutsideTouchable = false
        p.animationStyle = R.style.PopupGrow
        val w = box.measuredWidth
        val h = box.measuredHeight
        val x = (bounds.centerX() - w / 2).toInt().coerceIn(dp(8), maxOf(dp(8), root.width - w - dp(8)))
        val above = bounds.top - h - dp(12)
        val y = if (above > insetTop + dp(8)) above.toInt() else (bounds.bottom + dp(36)).toInt().coerceAtMost(root.height - h - dp(8))
        p.showAtLocation(root, Gravity.NO_GRAVITY, x, y)
        popup = p
    }

    /** Linha com espaços iguais antes, entre e depois dos itens. */
    private fun evenRow(items: List<View>, params: () -> LinearLayout.LayoutParams): LinearLayout =
        horizontal().apply {
            addView(spacer())
            items.forEach { addView(it, params()); addView(spacer()) }
        }

    /** Marcações que encostam no trecho selecionado. */
    private fun overlappingSelection(): List<Annotation> {
        val s = selStart ?: return emptyList()
        val e = selEnd ?: return emptyList()
        return annotations.filter { it.start < e && it.end > s }
    }

    /** Texto entre dois pontos (o trecho fica dentro de uma seção na prática; se não, junta as partes). */
    private fun quoteOf(start: Locator, end: Locator): String {
        val c = content ?: return ""
        return (start.section..end.section).joinToString(" ") { sec ->
            val t = c.sectionText(sec)
            val a = if (sec == start.section) start.offset else 0
            val b = if (sec == end.section) end.offset else t.length
            t.substring(a.coerceIn(0, t.length), b.coerceIn(0, t.length))
        }.trim()
    }

    /**
     * Toque numa cor:
     * - trecho sem marcação: marca;
     * - já marcado com a mesma cor: desmarca;
     * - marcado com outra cor: troca a cor, virando uma única marcação (nunca duas no mesmo trecho,
     *   então as cores não se "somam").
     */
    private fun applyColor(color: Int) = saveMarking(color, newNote = null)

    private fun saveMarking(color: Int, newNote: String?) {
        val s = selStart ?: return
        val e = selEnd ?: return
        val overlapping = overlappingSelection()
        Prefs.lastHighlightColor = color
        clearSelection()
        Bg.load({
            when {
                overlapping.isEmpty() ->
                    db.insertAnnotation(Annotation(0, book.id, s, e, color, newNote.orEmpty(), quoteOf(s, e), System.currentTimeMillis()))
                newNote == null && overlapping.any { it.color == color } ->
                    overlapping.forEach { db.deleteAnnotation(it.id) }
                else -> {
                    val start = minOf(s, overlapping.minOf { it.start })
                    val end = maxOf(e, overlapping.maxOf { it.end })
                    val note = newNote ?: overlapping.map { it.note }.filter { it.isNotBlank() }.joinToString("\n")
                    overlapping.forEach { db.deleteAnnotation(it.id) }
                    db.insertAnnotation(Annotation(0, book.id, start, end, color, note, quoteOf(start, end), overlapping.minOf { it.createdAt }))
                }
            }
            db.annotations(book.id)
        }) { r ->
            annotations = r.getOrDefault(annotations)
            invalidatePages()
        }
    }

    private fun removeMarking() {
        val overlapping = overlappingSelection()
        clearSelection()
        Bg.load({ overlapping.forEach { db.deleteAnnotation(it.id) }; db.annotations(book.id) }) { r ->
            annotations = r.getOrDefault(annotations)
            invalidatePages()
        }
    }

    private fun noteForSelection() {
        val existing = overlappingSelection().firstOrNull()
        val quote = existing?.quote ?: selectedText()
        popup?.dismiss()
        ReaderDialogs.showNoteEditor(this, Prefs.theme.isDark, quote, existing?.note.orEmpty(), existing?.color ?: Prefs.lastHighlightColor,
            onSave = { note, color -> saveMarking(color, note) },
            onDelete = existing?.let { { removeMarking() } })
    }

    private fun editAnnotation(a: Annotation) {
        ReaderDialogs.showNoteEditor(this, Prefs.theme.isDark, a.quote, a.note, a.color,
            onSave = { note, color ->
                Bg.load({ db.updateAnnotation(a.copy(note = note, color = color)); db.annotations(book.id) }) { r ->
                    annotations = r.getOrDefault(annotations); invalidatePages()
                }
            },
            onDelete = {
                Bg.load({ db.deleteAnnotation(a.id); db.annotations(book.id) }) { r ->
                    annotations = r.getOrDefault(annotations); invalidatePages()
                }
            })
    }

    private fun defineSelection() {
        val text = selectedText().trim()
        clearSelection()
        // dicionário consulta uma palavra; com várias selecionadas usa a primeira
        val word = text.split(Regex("\\s+")).firstOrNull { w -> w.any { it.isLetter() } }?.trim { !it.isLetterOrDigit() } ?: return
        ReaderDialogs.showDefinition(this, Prefs.theme.isDark, word)
    }

    private fun copySelection() {
        val text = selectedText()
        clearSelection()
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("trecho", text))
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
    }

    // =====================================================================
    // Marcadores
    // =====================================================================

    private fun isBookmarked(page: Int): Boolean {
        val c = content ?: return false
        val start = c.pageStart(page)
        val end = c.pageEnd(page)
        return bookmarks.any { it.at >= start && (it.at < end || it.at == start) }
    }

    private fun toggleBookmark() {
        val c = content ?: return
        val page = curl.currentPage
        val start = c.pageStart(page)
        val end = c.pageEnd(page)
        val existing = bookmarks.filter { it.at >= start && (it.at < end || it.at == start) }
        val adding = existing.isEmpty()
        val color = AppColors(Prefs.theme.isDark).primary
        ribbon.insetTop = insetTop
        curl.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        Bg.load({
            if (adding) db.insertBookmark(book.id, start, c.sectionTitle(start.section))
            else existing.forEach { db.deleteBookmark(it.id) }
            db.bookmarks(book.id)
        }) { r ->
            bookmarks = r.getOrDefault(bookmarks)
            if (adding) {
                // a fita cai do topo; só no fim ela passa a fazer parte da página
                ribbon.animateIn(color) { invalidatePages(); onPageShown() }
            } else {
                invalidatePages()
                onPageShown()
                ribbon.animateOut(color)
            }
        }
    }

    // =====================================================================
    // Navegação
    // =====================================================================

    /** Todas as opções do leitor ficam aqui, para a tela de leitura ficar limpa. */
    private fun showReaderMenu() {
        val c = content ?: return
        val marked = isBookmarked(curl.currentPage)
        showM3Menu(moreButton, Prefs.theme.isDark, listOf(
            MenuEntry(R.drawable.ic_search, "Pesquisar no livro") { openSearch() },
            MenuEntry(R.drawable.ic_toc, "Sumário, marcadores e notas") { openContents() },
            MenuEntry(if (marked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border,
                if (marked) "Remover marcador" else "Adicionar marcador") { toggleBookmark() },
            MenuEntry(R.drawable.ic_text, "Texto e exibição") { openDisplaySettings() },
        ))
    }

    private fun openDisplaySettings() {
        showBars(false)
        settingsOpen = true
        speculate()
        ReaderDialogs.showSettings(this, book.format == BookFormat.PDF, content?.hasText == true, object : ReaderDialogs.SettingsListener {
            // fade do tema antigo para o novo (o painel faz o mesmo fade, no mesmo instante)
            override fun onThemeChanged() = crossfade(root, root) { applyChromeTheme(); applyThemeNow() }
            override fun onLayoutChanged() = scheduleRelayout()
            override fun onClosed() { settingsOpen = false }
        })
    }

    private fun goTo(at: Locator) {
        val c = content ?: return
        curl.showPage(c.pageOf(at))
        onPageShown()
    }

    private fun openSearch(query: String = "") {
        val c = content ?: return
        showBars(false)
        SearchPanel(this, c, Prefs.theme.isDark) { hit ->
            searchHit = hit
            goTo(hit.start)
            // o contorno do resultado some sozinho depois de alguns segundos
            Bg.mainDelayed(4000, Runnable { if (searchHit === hit) { searchHit = null; refreshOverlay() } })
        }.show(query)
    }

    private fun openContents() {
        val c = content ?: return
        showBars(false)
        ContentsPanel(this, Prefs.theme.isDark, c, toc, bookmarks, annotations, curl.currentPage, object : ContentsPanel.Actions {
            override fun goTo(at: Locator) = this@ReaderActivity.goTo(at)
            override fun deleteBookmark(b: Bookmark) = this@ReaderActivity.confirmDeleteBookmark(b)
            override fun editAnnotation(a: Annotation) = this@ReaderActivity.editAnnotation(a)
        }).show()
    }

    private fun confirmDeleteBookmark(b: Bookmark) {
        alert(Prefs.theme.isDark)
            .setMessage("Remover este marcador?")
            .setPositiveButton("Remover") { _, _ ->
                Bg.load({ db.deleteBookmark(b.id); db.bookmarks(book.id) }) { r ->
                    bookmarks = r.getOrDefault(bookmarks); invalidatePages(); onPageShown()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // =====================================================================
    // Barras e interface
    // =====================================================================

    private fun buildViews() {
        root = FrameLayout(this)
        curl = PageCurlView(this)
        overlay = SelectionOverlay(this).apply { callbacks = this@ReaderActivity }
        root.addView(curl, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        ribbon = RibbonAnimView(this)
        root.addView(ribbon, FrameLayout.LayoutParams(-1, -1))

        // ---- barra inferior única: linha do tempo + [voltar | Página X de N | ⋮] ----
        bottomBar = vertical().apply { setPadding(dp(12), dp(10), dp(12), dp(6)) }
        seek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) updatePageLabels(p) }
                override fun onStartTrackingTouch(s: SeekBar) = Unit
                override fun onStopTrackingTouch(s: SeekBar) {
                    curl.showPage(s.progress)
                    onPageShown()
                }
            })
        }
        bottomBar.addView(seek, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(8), 0, dp(8), dp(2)) })
        val actions = horizontal()
        actions.addView(iconButton(R.drawable.ic_back) { finish() })
        pageLabel = text("", 13f).apply { gravity = Gravity.CENTER }
        chromeTexts += pageLabel
        actions.addView(pageLabel, weight())
        moreButton = iconButton(R.drawable.ic_more) { showReaderMenu() }
        actions.addView(moreButton)
        bottomBar.addView(actions, LinearLayout.LayoutParams(-1, -2))
        root.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // ---- carregando ----
        // véu de carregamento: cobre a tela e segura os toques enquanto pagina
        loading = vertical(dp(20)).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            isClickable = true
            setBackgroundColor(0x33000000)
        }
        loading.addView(ProgressBar(this).apply { tag = "spinner" })
        loadingText = text("", 14f).apply { gravity = Gravity.CENTER }
        chromeTexts += loadingText
        loading.addView(loadingText)
        root.addView(loading, FrameLayout.LayoutParams(-1, -1))

        bottomBar.visibility = View.INVISIBLE // medida desde o início; ver showBars
        setContentView(root)

        root.setOnApplyWindowInsetsListener { _, insets ->
            val cutout = insets.displayCutout
            val newTop = cutout?.safeInsetTop ?: 0
            @Suppress("DEPRECATION")
            val navBottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom
            else insets.stableInsetBottom
            val newBottom = maxOf(cutout?.safeInsetBottom ?: 0, navBottom)
            val bars = if (Build.VERSION.SDK_INT >= 30) insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()) else null
            @Suppress("DEPRECATION")
            val sysTop = bars?.top ?: insets.stableInsetTop
            @Suppress("DEPRECATION")
            val sysBottom = bars?.bottom ?: insets.stableInsetBottom
            bottomBar.setPadding(dp(12), dp(10), dp(12), sysBottom + dp(6))
            if (newTop != insetTop || newBottom != insetBottom) {
                insetTop = newTop
                insetBottom = newBottom
                if (content != null) scheduleRelayout()
            }
            insets
        }
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (content != null && (r - l != or - ol || b - t != ob - ot)) scheduleRelayout()
        }
    }

    private fun iconButton(icon: Int, tint: Int? = null, onClick: () -> Unit): ImageView = ImageView(this).apply {
        setImageResource(icon)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        ripple(colors.stateLayer(colors.onSurface), mask = oval(Color.WHITE))
        if (tint != null) setColorFilter(tint) else chromeIcons += this
        setOnClickListener { onClick() }
    }

    private fun applyChromeTheme() {
        val t = Prefs.theme
        val c = colors
        window.setBackgroundDrawable(ColorDrawable(t.background))
        bottomBar.background = roundRect(c.surfaceContainer, 0f).apply {
            cornerRadii = floatArrayOf(dpf(28), dpf(28), dpf(28), dpf(28), 0f, 0f, 0f, 0f)
        }
        chromeIcons.forEach { it.setColorFilter(c.onSurfaceVariant) }
        chromeTexts.forEach { it.setTextColor(c.onSurface) }
        pageLabel.setTextColor(c.onSurfaceVariant)
        seek.tintM3(c)
        loading.findViewWithTag<ProgressBar>("spinner")?.indeterminateTintList = android.content.res.ColorStateList.valueOf(c.primary)
        overlay.accent = c.primary
        if (Build.VERSION.SDK_INT >= 30) {
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (t.isDark) 0 else light, light)
        }
    }

    private fun updatePageLabels(page: Int) {
        val c = content ?: return
        pageLabel.text = "Página ${page + 1} de ${c.pageCount}"
    }

    private fun showBars(show: Boolean) {
        if (barsVisible == show) return
        barsVisible = show
        if (show) clearSelection()
        val bar = bottomBar
        bar.animate().cancel()
        // a barra nunca sai do layout (INVISIBLE, não GONE): a altura é conhecida antes de aparecer,
        // então ela já começa escondida abaixo da tela e não "pisca" na posição final
        val offscreen = (bar.height.takeIf { it > 0 } ?: dp(160)).toFloat()
        if (show) {
            bar.translationY = offscreen
            bar.visibility = View.VISIBLE
            bar.animate().translationY(0f).setDuration(260)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        } else {
            bar.animate().translationY(offscreen).setDuration(200)
                .setInterpolator(android.view.animation.AccelerateInterpolator(1.5f))
                .withEndAction { if (!barsVisible) bar.visibility = View.INVISIBLE }.start()
        }
        if (show) exitImmersive() else enterImmersive()
    }

    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars())
                it.show(WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    private fun exitImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.show(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    private fun showLoading(msg: String) {
        loadingText.text = msg
        loading.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        loading.visibility = View.GONE
    }

    // =====================================================================
    // Ciclo de vida e teclas
    // =====================================================================



    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < 34) {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.hold, R.anim.slide_out_right)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // só a seleção de texto "segura" o voltar; de resto, sai do livro na hora
        if (selStart != null) clearSelection() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !barsVisible) enterImmersive()
    }

    override fun onDestroy() {
        super.onDestroy()
        popup?.dismiss()
        content?.close()
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"

        /** Mesmos valores do painel "Texto e exibição". */
        private val MARGINS = listOf(14, 24, 40)
        private val SPACINGS = listOf(1.25f, 1.45f, 1.7f)

        /** Fila de pré-cálculo: uma tarefa por vez, em prioridade baixa para não disputar com a interface. */
        private val SPECULATOR = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "especulacao").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
    }
}
