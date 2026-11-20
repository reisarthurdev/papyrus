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
import dev.reisarthur.papyrus.pdf.PdfTextStore
import java.io.File

/**
 * PDF com a página inteira encaixada na tela (renderização nativa do PdfRenderer): uma página
 * do leitor = uma página do PDF, sem paginação para calcular. O zoom de pinça desenha só a parte
 * visível, na resolução certa.
 */
class PdfContent(context: Context, uri: Uri, dataDir: File, private val toc: List<TocEntry>) : BookContent {

    private val fd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
        ?: error("Não foi possível abrir o PDF")
    private val renderer = PdfRenderer(fd)
    private val textStore = PdfTextStore(dataDir)
    /** Onde ficam as fotos de cada página (registradas na importação): não invertem no escuro. */
    private val figures = PdfFigures.load(dataDir)
    private val textCache = LruCache<Int, PageText>(16)
    private val pageSizes = HashMap<Int, Pair<Int, Int>>()
    private var settings: LayoutSettings? = null
    private var scratch: Bitmap? = null

    init {
        // restos do zoom consolidado (removido): libera o espaço
        dataDir.listFiles { f -> f.name.startsWith("reflow") }?.forEach { it.delete() }
    }

    override val pageCount = renderer.pageCount
    override val sectionCount = pageCount
    override val hasText = textStore.exists

    /** Nada a calcular: só guarda o tamanho da tela e as cores. */
    @Synchronized
    override fun layout(settings: LayoutSettings, onProgress: (Int, Int) -> Unit) {
        this.settings = settings
    }

    private fun imagesOf(page: Int) = figures.getOrNull(page)?.images.orEmpty()

    // ---------------- Navegação ----------------

    override fun pageOf(loc: Locator): Int = loc.section.coerceIn(0, pageCount - 1)

    override fun pageStart(page: Int): Locator = Locator(page, 0)

    override fun pageEnd(page: Int): Locator = Locator(page, pageText(page).length)

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
        canvas.drawColor(if (s.theme.isDark && s.pdfInvert) Color.BLACK else s.theme.background)
        if (page !in 0 until pageCount) return
        drawFixed(page, canvas, marks, zoom, panX, panY, s, pagePaint(s))
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
            for (img in imagesOf(page)) {
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

    // ---------------- Texto sob o dedo e retângulos ----------------

    @Synchronized
    override fun charAt(page: Int, x: Float, y: Float): Locator? {
        val s = settings ?: return null
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

    @Synchronized
    override fun rects(page: Int, start: Locator, end: Locator): List<RectF> {
        val s = settings ?: return emptyList()
        return rectsFixed(page, start, end, s)
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

    @Synchronized
    override fun close() {
        runCatching { renderer.close() }
        runCatching { fd.close() }
        scratch?.recycle(); scratch = null
    }

    private companion object {
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
