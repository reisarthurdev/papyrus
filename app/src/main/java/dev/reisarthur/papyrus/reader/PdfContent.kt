package dev.reisarthur.papyrus.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import dev.reisarthur.papyrus.data.Locator
import dev.reisarthur.papyrus.data.TocEntry
import dev.reisarthur.papyrus.pdf.PageText
import dev.reisarthur.papyrus.pdf.PdfFigures
import dev.reisarthur.papyrus.pdf.PdfReflow
import dev.reisarthur.papyrus.pdf.PdfTextStore
import dev.reisarthur.papyrus.pdf.ReflowStore
import java.io.File
import kotlin.math.sqrt

/**
 * PDF em dois modos:
 * - 100%: página inteira encaixada na tela (renderização nativa do PdfRenderer);
 * - zoom consolidado (125%, 150%…): palavras reorganizadas em linhas que cabem na tela
 *   ([PdfReflow]), então nada fica cortado na borda. PDFs escaneados ficam sempre em 100%.
 * Em qualquer modo, o zoom de pinça desenha só a parte visível na resolução certa.
 */
class PdfContent(context: Context, uri: Uri, private val dataDir: File, private val toc: List<TocEntry>) : BookContent {

    private val fd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
        ?: error("Não foi possível abrir o PDF")
    private val renderer = PdfRenderer(fd)
    private val textStore = PdfTextStore(dataDir)
    /** Regiões de imagem/desenho de cada página (registradas na importação). */
    private val figures = PdfFigures.load(dataDir)
    private val textCache = LruCache<Int, PageText>(16)
    private val pageSizes = HashMap<Int, Pair<Int, Int>>()
    private var settings: LayoutSettings? = null
    private var scratch: Bitmap? = null

    // modo refluído
    private var reflow: List<PdfReflow.SubPage>? = null
    private var firstSub = IntArray(0)
    private var source: Bitmap? = null
    private var sourceKey = ""

    private val pdfPages = renderer.pageCount
    override val sectionCount = pdfPages
    override val hasText = textStore.exists
    override val pageCount: Int @Synchronized get() = reflow?.size ?: pdfPages

    /** Reflows já calculados por tamanho de tela/zoom: trocar tema ou voltar a um zoom não recalcula nada. */
    private val reflowCache = object : LinkedHashMap<String, ReflowStore.Result>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ReflowStore.Result>?) = size > 3
    }

    override fun layout(settings: LayoutSettings, onProgress: (Int, Int) -> Unit) {
        val reflowed = settings.pdfZoom > 1.01f && hasText
        val key = "r2|${settings.width}|${settings.height}|${settings.density}|${settings.pdfZoom}|${settings.insetTop}|${settings.insetBottom}"
        // calculado FORA da trava: a página atual continua sendo desenhada enquanto pagina.
        // memória → disco → calcula (e grava): cada configuração é paginada uma vez só
        val result = if (!reflowed) null else synchronized(reflowCache) { reflowCache[key] }
            ?: ReflowStore.load(dataDir, key)
            ?: buildReflow(settings, onProgress).also { ReflowStore.save(dataDir, key, it) }
        synchronized(this) {
            if (this.settings?.pdfZoom != settings.pdfZoom) { source?.recycle(); source = null; sourceKey = "" }
            this.settings = settings
            reflow = result?.pages
            firstSub = result?.firstSub ?: IntArray(0)
        }
        if (result != null) synchronized(reflowCache) { reflowCache[key] = result }
    }

    /**
     * Pagina o livro inteiro no zoom escolhido. As páginas são independentes, então são divididas
     * em blocos e calculadas em paralelo; o resultado é montado na ordem original.
     */
    private fun buildReflow(s: LayoutSettings, onProgress: (Int, Int) -> Unit): ReflowStore.Result {
        val engine = PdfReflow(
            s.width, s.height,
            marginX = (s.density * 18).toInt(),
            marginTop = s.insetTop + (s.density * 28).toInt(),
            marginBottom = s.insetBottom + (s.density * 36).toInt(),
        )
        val perPage = arrayOfNulls<List<PdfReflow.SubPage>>(pdfPages)
        val done = java.util.concurrent.atomic.AtomicInteger()
        val chunk = 8
        (0 until pdfPages step chunk).map { from ->
            POOL.submit {
                for (p in from until minOf(pdfPages, from + chunk)) {
                    val fit = fitRect(p, s)
                    val text = pageText(p)
                    val figs = figuresOf(p)
                    perPage[p] = engine.layoutPage(p, text, figs.all, figs.images, fit.width() * s.pdfZoom, fit.height() * s.pdfZoom)
                        // página sem texto (capa, figura inteira): mostra a página toda encaixada
                        .ifEmpty { listOf(PdfReflow.SubPage(p, 0, text.length, emptyList(), listOf(PdfReflow.Figure(RectF(0f, 0f, 1f, 1f), fit)))) }
                    val n = done.incrementAndGet()
                    if (n % 8 == 0) onProgress(n, pdfPages)
                }
            }
        }.forEach { it.get() }
        val out = ArrayList<PdfReflow.SubPage>()
        val first = IntArray(pdfPages)
        for (p in 0 until pdfPages) {
            first[p] = out.size
            out += perPage[p]!!
        }
        return ReflowStore.Result(out, first)
    }

    private fun figuresOf(page: Int) = figures.getOrElse(page) { PdfFigures.Page.EMPTY }

    // ---------------- Navegação ----------------

    @Synchronized
    override fun pageOf(loc: Locator): Int {
        val r = reflow ?: return loc.section.coerceIn(0, pdfPages - 1)
        val pdfPage = loc.section.coerceIn(0, pdfPages - 1)
        var i = firstSub[pdfPage]
        while (i + 1 < r.size && r[i + 1].pdfPage == pdfPage && r[i + 1].startOffset <= loc.offset) i++
        return i
    }

    @Synchronized
    override fun pageStart(page: Int): Locator =
        reflow?.getOrNull(page)?.let { Locator(it.pdfPage, it.startOffset) } ?: Locator(page, 0)

    @Synchronized
    override fun pageEnd(page: Int): Locator =
        reflow?.getOrNull(page)?.let { Locator(it.pdfPage, it.endOffset) } ?: Locator(page, pageText(page).length)

    override fun sectionText(section: Int): String = pageText(section).text

    override fun sectionTitle(section: Int): String =
        toc.lastOrNull { it.at.section <= section }?.title ?: "Página ${section + 1}"

    @Synchronized
    private fun pageText(page: Int): PageText =
        textCache.get(page) ?: textStore.page(page).also { textCache.put(page, it) }

    @Synchronized
    private fun pageSize(page: Int): Pair<Int, Int> = pageSizes.getOrPut(page) {
        renderer.openPage(page).use { it.width to it.height }
    }

    /** Onde a página inteira aparece na tela em 100% (centralizada, mantendo a proporção). */
    private fun fitRect(page: Int, s: LayoutSettings): RectF {
        val (pw, ph) = pageSize(page)
        val availH = (s.height - s.insetTop - s.insetBottom).toFloat()
        val scale = minOf(s.width.toFloat() / pw, availH / ph)
        val w = pw * scale
        val h = ph * scale
        val left = (s.width - w) / 2
        val top = s.insetTop + (availH - h) / 2
        return RectF(left, top, left + w, top + h)
    }

    // ---------------- Desenho ----------------

    override fun drawPage(page: Int, canvas: Canvas, marks: List<Mark>) = drawViewport(page, canvas, marks, 1f, 0f, 0f)

    @Synchronized
    override fun drawViewport(page: Int, canvas: Canvas, marks: List<Mark>, zoom: Float, panX: Float, panY: Float) {
        val s = settings ?: return
        val r = reflow
        canvas.drawColor(
            when {
                s.theme.isDark && s.pdfInvert -> Color.BLACK
                // escuro sem inversão no zoom: as palavras recortadas vêm com o papel branco em volta;
                // fundo branco evita "caixinhas" brancas sobre o preto
                s.theme.isDark && r != null -> Color.WHITE
                else -> s.theme.background
            },
        )
        if (page !in 0 until pageCount) return
        val paint = pagePaint(s)
        if (r != null) drawReflowed(r[page], canvas, marks, zoom, panX, panY, s, paint)
        else drawFixed(page, canvas, marks, zoom, panX, panY, s, paint)
    }

    /**
     * Escuro com inversão: inverte as cores. Sépia: "multiplica" (o branco do papel vira a cor do tema).
     * Escuro SEM inversão: página original, como impressa, sobre o fundo preto. (Multiplicar aqui
     * deixaria tudo preto: branco × preto = preto.)
     */
    private fun pagePaint(s: LayoutSettings) = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        when {
            s.theme.isDark && s.pdfInvert -> colorFilter = INVERT
            !s.theme.isDark && s.theme.background != Color.WHITE -> xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        }
    }

    private fun drawFixed(page: Int, canvas: Canvas, marks: List<Mark>, zoom: Float, panX: Float, panY: Float, s: LayoutSettings, paint: Paint) {
        val fit = fitRect(page, s)
        val dst = RectF(fit.left * zoom + panX, fit.top * zoom + panY, fit.right * zoom + panX, fit.bottom * zoom + panY)
        // renderiza só a área da tela, já na escala do zoom (nítido mesmo ampliado)
        val bmp = scratch?.takeIf { it.width == s.width && it.height == s.height }
            ?: Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ARGB_8888).also { scratch?.recycle(); scratch = it }
        bmp.eraseColor(Color.TRANSPARENT)
        Canvas(bmp).drawRect(dst, Paint().apply { color = Color.WHITE })
        val (pw, ph) = pageSize(page)
        val m = Matrix().apply {
            setScale(dst.width() / pw, dst.height() / ph)
            postTranslate(dst.left, dst.top)
        }
        renderer.openPage(page).use { it.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
        canvas.drawBitmap(bmp, 0f, 0f, paint)
        if (paint.colorFilter != null) {
            // fotos ficam com as cores reais no escuro (só texto e desenhos são invertidos)
            val tmp = Rect()
            for (img in figuresOf(page).images) {
                val d = RectF(dst.left + img.left * dst.width(), dst.top + img.top * dst.height(),
                    dst.left + img.right * dst.width(), dst.top + img.bottom * dst.height())
                d.roundOut(tmp)
                if (!tmp.intersect(0, 0, bmp.width, bmp.height)) continue
                canvas.drawBitmap(bmp, tmp, tmp, PHOTO)
            }
        }

        val hl = Paint(Paint.ANTI_ALIAS_FLAG)
        for (mark in marks) {
            hl.color = mark.color
            rectsFixed(page, mark.start, mark.end, s).forEach {
                canvas.drawRect(it.left * zoom + panX, it.top * zoom + panY, it.right * zoom + panX, it.bottom * zoom + panY, hl)
            }
        }
    }

    private fun drawReflowed(sub: PdfReflow.SubPage, canvas: Canvas, marks: List<Mark>, zoom: Float, panX: Float, panY: Float, s: LayoutSettings, paint: Paint) {
        val fit = fitRect(sub.pdfPage, s)
        val baseW = fit.width() * s.pdfZoom
        val baseH = fit.height() * s.pdfZoom
        // resolução da página de origem acompanha o zoom de pinça, com teto de memória
        var mult = (Math.round(zoom * 2) / 2f).coerceIn(1f, 3f)
        val maxPixels = 18_000_000f
        if (baseW * mult * baseH * mult > maxPixels) mult = sqrt(maxPixels / (baseW * baseH)).coerceAtLeast(0.5f)
        val src = sourceBitmap(sub.pdfPage, (baseW * mult).toInt(), (baseH * mult).toInt())
        canvas.save()
        canvas.translate(panX, panY)
        canvas.scale(zoom, zoom)
        val images = if (paint.colorFilter != null) figuresOf(sub.pdfPage).images else emptyList()
        val part = RectF()
        for (f in sub.figures) {
            blit(canvas, src, f.src, f.dst, paint)
            // fotos dentro da figura: redesenhadas sem inverter
            for (img in images) {
                if (!part.setIntersect(img, f.src)) continue
                val kx = f.dst.width() / f.src.width().coerceAtLeast(1e-6f)
                val ky = f.dst.height() / f.src.height().coerceAtLeast(1e-6f)
                blit(canvas, src, part, RectF(
                    f.dst.left + (part.left - f.src.left) * kx, f.dst.top + (part.top - f.src.top) * ky,
                    f.dst.left + (part.right - f.src.left) * kx, f.dst.top + (part.bottom - f.src.top) * ky,
                ), PHOTO)
            }
        }
        for (p in sub.pieces) {
            // folga para acentos, descendentes e itálico que passam da caixa estimada
            val padY = p.src.height() * 0.2f
            // folga lateral pequena: maior que isso pegava um pedaço da letra vizinha ("chama.-se")
            val padX = p.src.height() * 0.05f
            val kx = p.dst.width() / p.src.width().coerceAtLeast(1e-6f)
            val ky = p.dst.height() / p.src.height().coerceAtLeast(1e-6f)
            blit(
                canvas, src,
                RectF(p.src.left - padX, p.src.top - padY, p.src.right + padX, p.src.bottom + padY * 0.5f),
                RectF(p.dst.left - padX * kx, p.dst.top - padY * ky, p.dst.right + padX * kx, p.dst.bottom + padY * 0.5f * ky),
                paint,
            )
        }
        val hl = Paint(Paint.ANTI_ALIAS_FLAG)
        for (mark in marks) {
            hl.color = mark.color
            rectsReflowed(sub, mark.start, mark.end).forEach { canvas.drawRect(it, hl) }
        }
        canvas.restore()
    }

    /**
     * Copia a região normalizada [srcN] (0..1) da página para [dst]. A origem é recortada aos limites
     * da página e o destino acompanha o recorte: a folga das palavras na borda não estica nem desloca nada.
     */
    private fun blit(canvas: Canvas, src: Bitmap, srcN: RectF, dst: RectF, paint: Paint) {
        val l = srcN.left.coerceAtLeast(0f)
        val t = srcN.top.coerceAtLeast(0f)
        val r = srcN.right.coerceAtMost(1f)
        val b = srcN.bottom.coerceAtMost(1f)
        if (r <= l || b <= t) return
        val kx = dst.width() / srcN.width().coerceAtLeast(1e-6f)
        val ky = dst.height() / srcN.height().coerceAtLeast(1e-6f)
        val sw = src.width
        val sh = src.height
        val sr = Rect((l * sw).toInt(), (t * sh).toInt(), (r * sw).toInt().coerceAtMost(sw), (b * sh).toInt().coerceAtMost(sh))
        if (sr.isEmpty) return
        // destino calculado a partir dos pixels inteiros da origem (sem meio-pixel de deslize)
        val dr = RectF(
            dst.left + (sr.left / sw.toFloat() - srcN.left) * kx, dst.top + (sr.top / sh.toFloat() - srcN.top) * ky,
            dst.left + (sr.right / sw.toFloat() - srcN.left) * kx, dst.top + (sr.bottom / sh.toFloat() - srcN.top) * ky,
        )
        canvas.drawBitmap(src, sr, dr, paint)
    }

    /** Página do PDF renderizada inteira numa escala; reaproveitada entre subpáginas da mesma página. */
    private fun sourceBitmap(page: Int, w: Int, h: Int): Bitmap {
        val key = "$page@${w}x$h"
        source?.takeIf { sourceKey == key }?.let { return it }
        source?.recycle()
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val (pw, ph) = pageSize(page)
        val m = Matrix().apply { setScale(w.toFloat() / pw, h.toFloat() / ph) }
        renderer.openPage(page).use { it.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
        source = bmp
        sourceKey = key
        return bmp
    }

    // ---------------- Texto sob o dedo e retângulos ----------------

    @Synchronized
    override fun charAt(page: Int, x: Float, y: Float): Locator? {
        val s = settings ?: return null
        val r = reflow
        return if (r != null) charAtReflowed(r.getOrNull(page) ?: return null, x, y) else charAtFixed(page, x, y, s)
    }

    private fun charAtFixed(page: Int, x: Float, y: Float, s: LayoutSettings): Locator? {
        val t = pageText(page)
        if (t.length == 0) return null
        val r = fitRect(page, s)
        val nx = (x - r.left) / r.width()
        val ny = (y - r.top) / r.height()
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (i in 0 until t.length) {
            if (t.text[i].isWhitespace()) continue
            if (nx >= t.left(i) && nx <= t.right(i) && ny >= t.top(i) && ny <= t.bottom(i)) return Locator(page, i)
            val cx = (t.left(i) + t.right(i)) / 2
            val cy = (t.top(i) + t.bottom(i)) / 2
            // distância vertical pesa mais: favorece a linha certa
            val d = (cx - nx) * (cx - nx) + 4 * (cy - ny) * (cy - ny)
            if (d < bestDist) { bestDist = d; best = i }
        }
        return if (best >= 0 && bestDist < 0.004f) Locator(page, best) else null
    }

    private fun charAtReflowed(sub: PdfReflow.SubPage, x: Float, y: Float): Locator? {
        val t = pageText(sub.pdfPage)
        val piece = sub.pieces.firstOrNull { it.dst.left - 6 <= x && x <= it.dst.right + 6 && it.dst.top - 8 <= y && y <= it.dst.bottom + 8 }
            ?: sub.pieces.filter { y >= it.dst.top - 12 && y <= it.dst.bottom + 12 }
                .minByOrNull { minOf(kotlin.math.abs(it.dst.left - x), kotlin.math.abs(it.dst.right - x)) }
            ?: return null
        val k = piece.dst.width() / piece.src.width().coerceAtLeast(1e-6f)
        for (i in piece.start until piece.end) {
            val right = piece.dst.left + (t.right(i) - piece.src.left) * k
            if (x <= right) return Locator(sub.pdfPage, i)
        }
        return Locator(sub.pdfPage, maxOf(piece.start, piece.end - 1))
    }

    @Synchronized
    override fun rects(page: Int, start: Locator, end: Locator): List<RectF> {
        val s = settings ?: return emptyList()
        val r = reflow
        return if (r != null) rectsReflowed(r.getOrNull(page) ?: return emptyList(), start, end) else rectsFixed(page, start, end, s)
    }

    private fun rectsFixed(page: Int, start: Locator, end: Locator, s: LayoutSettings): List<RectF> {
        if (start.section > page || end.section < page) return emptyList()
        val t = pageText(page)
        val a = if (start.section < page) 0 else start.offset
        val b = if (end.section > page) t.length else minOf(end.offset, t.length)
        if (a >= b) return emptyList()
        val r = fitRect(page, s)
        val out = ArrayList<RectF>()
        var cur: RectF? = null
        for (i in a until b) {
            val c = t.text[i]
            if (c == '\n') { cur = null; continue }
            if (t.right(i) <= t.left(i) && c.isWhitespace()) continue
            val box = RectF(
                r.left + t.left(i) * r.width(), r.top + t.top(i) * r.height(),
                r.left + t.right(i) * r.width(), r.top + t.bottom(i) * r.height(),
            )
            val c0 = cur
            // junta caracteres da mesma linha num retângulo só
            if (c0 != null && kotlin.math.abs(c0.centerY() - box.centerY()) < c0.height() * 0.5f && box.left >= c0.left - 2) {
                c0.union(box)
            } else {
                cur = box
                out += box
            }
        }
        return out
    }

    private fun rectsReflowed(sub: PdfReflow.SubPage, start: Locator, end: Locator): List<RectF> {
        if (start.section > sub.pdfPage || end.section < sub.pdfPage) return emptyList()
        val t = pageText(sub.pdfPage)
        val a = if (start.section < sub.pdfPage) 0 else start.offset
        val b = if (end.section > sub.pdfPage) Int.MAX_VALUE else end.offset
        val out = ArrayList<RectF>()
        for (p in sub.pieces) {
            val from = maxOf(a, p.start)
            val to = minOf(b, p.end)
            if (from >= to) continue
            val k = p.dst.width() / p.src.width().coerceAtLeast(1e-6f)
            val rect = RectF(
                p.dst.left + (t.left(from) - p.src.left) * k, p.dst.top,
                p.dst.left + (t.right(to - 1) - p.src.left) * k, p.dst.bottom,
            )
            val last = out.lastOrNull()
            // palavras vizinhas na mesma linha viram um retângulo contínuo
            if (last != null && kotlin.math.abs(last.centerY() - rect.centerY()) < rect.height() * 0.5f && rect.left - last.right < rect.height()) {
                last.union(rect)
            } else {
                out += rect
            }
        }
        return out
    }

    @Synchronized
    override fun close() {
        runCatching { renderer.close() }
        runCatching { fd.close() }
        scratch?.recycle(); scratch = null
        source?.recycle(); source = null
    }

    private companion object {
        /** Paginação do zoom: um núcleo livre para a interface. */
        val POOL: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newFixedThreadPool(
            (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 6),
        ) { r -> Thread(r, "pdf-reflow").apply { isDaemon = true } }

        /** Fotos no tema escuro: cores reais, só um pouco escurecidas para não ofuscar. */
        val PHOTO = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.88f, 0.88f, 0.88f, 1f) })
        }

        /** Branco vira preto puro e preto vira #D9D9D9 (sem contraste estourado). */
        val INVERT = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            -0.85f, 0f, 0f, 0f, 217f,
            0f, -0.85f, 0f, 0f, 217f,
            0f, 0f, -0.85f, 0f, 217f,
            0f, 0f, 0f, 1f, 0f,
        )))
    }
}
