package dev.reisarthur.papyrus.pdf

import android.graphics.RectF
import com.tom_roush.pdfbox.util.Matrix
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Onde ficam as fotos de cada página, em coordenadas 0..1 com origem no canto superior esquerdo.
 * No tema escuro com inversão, essas áreas são desenhadas com as cores reais.
 * As posições são registradas durante a extração de texto (mesma passada pelo PDF, sem custo extra).
 */
object PdfFigures {

    /** [images] = fotos da página. ([all] existe por compatibilidade com o arquivo já gravado.) */
    class Page(val all: List<RectF>, val images: List<RectF>) {
        companion object { val EMPTY = Page(emptyList(), emptyList()) }
    }

    /**
     * Retângulo (0..1) ocupado por uma imagem desenhada com a matriz [ctm], numa página cujo
     * recorte visível começa em ([left], [top]) do PDF e mede [width] x [height].
     * Devolve null para o que não deve ficar sem inverter: pedacinhos (fios, ícones) e imagens de
     * página quase inteira (normalmente é a digitalização do próprio texto).
     */
    fun imageRect(ctm: Matrix, left: Float, top: Float, width: Float, height: Float): RectF? {
        // a imagem ocupa o quadrado unitário transformado pela matriz atual
        val pts = listOf(ctm.transformPoint(0f, 0f), ctm.transformPoint(1f, 0f), ctm.transformPoint(0f, 1f), ctm.transformPoint(1f, 1f))
        val r = RectF(
            ((pts.minOf { it.x } - left) / width).coerceAtLeast(0f), ((top - pts.maxOf { it.y }) / height).coerceAtLeast(0f),
            ((pts.maxOf { it.x } - left) / width).coerceAtMost(1f), ((top - pts.minOf { it.y }) / height).coerceAtMost(1f),
        )
        if (r.width() < 0.02f || r.height() < 0.01f || r.width() * r.height() > 0.6f) return null
        return r
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
