package dev.reisarthur.papyrus.pdf

import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Regiões de imagem e de desenho vetorial (gráficos, tabelas, diagramas) de cada página,
 * em coordenadas 0..1 com origem no canto superior esquerdo. O zoom do PDF usa isso para
 * manter figuras inteiras em vez de tentar reorganizá-las como texto.
 */
object PdfFigures {

    /** Figuras de uma página: [all] para o zoom manter inteiras; [images] são fotos (não invertem no escuro). */
    class Page(val all: List<RectF>, val images: List<RectF>) {
        companion object { val EMPTY = Page(emptyList(), emptyList()) }
    }

    /** Percorre o conteúdo da página registrando onde algo é desenhado (sem rasterizar nada). */
    fun collect(page: PDPage): Page {
        if (page.rotation % 360 != 0) return Page.EMPTY // raro; o zoom usa só o texto nessas páginas
        val box = page.cropBox
        val collector = Collector(page, box.lowerLeftX, box.upperRightY, box.width, box.height)
        collector.processPage(page)
        return Page(collector.rects, collector.images)
    }

    private class Collector(
        page: PDPage,
        private val left: Float,
        private val top: Float,
        private val width: Float,
        private val height: Float,
    ) : PDFGraphicsStreamEngine(page) {
        val rects = ArrayList<RectF>()
        val images = ArrayList<RectF>()
        private var path: RectF? = null
        private val current = PointF()

        /** Ponto do PDF (y para cima) -> 0..1 (y para baixo). */
        private fun extend(x: Float, y: Float) {
            val nx = (x - left) / width
            val ny = (top - y) / height
            path = path?.apply { union(nx, ny) } ?: RectF(nx, ny, nx, ny)
            current.set(x, y)
        }

        private fun commit() {
            path?.let { add(it) }
            path = null
        }

        private fun add(r: RectF, isImage: Boolean = false) {
            val c = RectF(maxOf(0f, r.left), maxOf(0f, r.top), minOf(1f, r.right), minOf(1f, r.bottom))
            val area = c.width() * c.height()
            // ignora fios (sublinhados, réguas), pontos e fundos de página inteira
            if (c.width() < 0.02f || c.height() < 0.01f) return
            if (area <= 0.85f) rects += c
            // imagem de página inteira costuma ser digitalização de texto: essa continua sendo invertida
            if (isImage && area <= 0.6f) images += c
        }

        override fun drawImage(pdImage: PDImage) {
            // a imagem ocupa o quadrado unitário transformado pela matriz atual
            val m = graphicsState.currentTransformationMatrix
            val pts = listOf(m.transformPoint(0f, 0f), m.transformPoint(1f, 0f), m.transformPoint(0f, 1f), m.transformPoint(1f, 1f))
            val r = RectF(
                (pts.minOf { it.x } - left) / width, (top - pts.maxOf { it.y }) / height,
                (pts.maxOf { it.x } - left) / width, (top - pts.minOf { it.y }) / height,
            )
            add(r, isImage = true)
        }

        override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {
            extend(p0.x, p0.y); extend(p1.x, p1.y); extend(p2.x, p2.y); extend(p3.x, p3.y)
        }

        override fun moveTo(x: Float, y: Float) = extend(x, y)
        override fun lineTo(x: Float, y: Float) = extend(x, y)
        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            extend(x1, y1); extend(x2, y2); extend(x3, y3)
        }
        override fun getCurrentPoint(): PointF = current
        override fun closePath() = Unit
        override fun clip(windingRule: Path.FillType) = Unit
        // caminho sem pintura (só recorte): não é figura
        override fun endPath() { path = null }
        override fun strokePath() = commit()
        override fun fillPath(windingRule: Path.FillType) = commit()
        override fun fillAndStrokePath(windingRule: Path.FillType) = commit()
        override fun shadingFill(shadingName: COSName) = Unit

        // Texto não é figura: pular os operadores de texto evita decodificar fontes e glifos,
        // que é a parte mais cara de percorrer a página (a passada fica várias vezes mais rápida).
        override fun showTextString(string: ByteArray) = Unit
        override fun showTextStrings(array: com.tom_roush.pdfbox.cos.COSArray) = Unit
    }

    // ---------------- Armazenamento (figures2.dat) ----------------

    private const val FILE = "figures2.dat"

    fun save(dir: File, pages: List<Page>) {
        DataOutputStream(File(dir, FILE).outputStream().buffered()).use { out ->
            out.writeInt(pages.size)
            pages.forEach { p ->
                out.writeInt(p.all.size)
                p.all.forEach { r ->
                    out.writeFloat(r.left); out.writeFloat(r.top); out.writeFloat(r.right); out.writeFloat(r.bottom)
                    out.writeBoolean(p.images.any { it == r })
                }
            }
        }
    }

    fun load(dir: File): List<Page> {
        val f = File(dir, FILE)
        if (!f.exists()) return emptyList()
        return runCatching {
            DataInputStream(f.inputStream().buffered()).use { input ->
                List(input.readInt()) {
                    val all = ArrayList<RectF>()
                    val images = ArrayList<RectF>()
                    repeat(input.readInt()) {
                        val r = RectF(input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat())
                        all += r
                        if (input.readBoolean()) images += r
                    }
                    Page(all, images)
                }
            }
        }.getOrDefault(emptyList())
    }
}
