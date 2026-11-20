package dev.reisarthur.papyrus.reader

import android.graphics.Canvas
import android.graphics.RectF
import dev.reisarthur.papyrus.data.Locator
import dev.reisarthur.papyrus.ui.ReaderTheme

/** Tudo que muda a paginação ou a aparência das páginas. */
data class LayoutSettings(
    val width: Int,
    val height: Int,
    val density: Float,
    val fontPx: Float,
    val lineSpacing: Float,
    val marginPx: Int,
    val justify: Boolean,
    val theme: ReaderTheme,
    val pdfInvert: Boolean,
    /** Zoom consolidado do PDF (1 = página inteira; acima disso o texto é refluído). */
    val pdfZoom: Float,
    /** Área ocupada por notch/barra de gestos, para o texto não ficar embaixo. */
    val insetTop: Int,
    val insetBottom: Int,
) {
    /** Só o que afeta a quebra de páginas (tema não entra: trocar cor não repagina). */
    fun paginationKey() = "v3|$width|$height|$fontPx|$lineSpacing|$marginPx|$justify|$insetTop|$insetBottom|$pdfZoom"
}

/** Um trecho destacado a desenhar na página. */
data class Mark(val start: Locator, val end: Locator, val color: Int)

/**
 * Interface comum entre EPUB (texto refluível) e PDF (páginas fixas).
 * O leitor, a virada de página, a seleção, a busca e o TTS só conversam com isto.
 * As implementações são thread-safe (métodos sincronizados), pois a pré-renderização roda em segundo plano.
 */
interface BookContent {
    val sectionCount: Int
    val pageCount: Int
    /** false em PDFs escaneados (sem texto extraível). */
    val hasText: Boolean

    /** (Re)pagina para o tamanho/ajustes atuais. Pode demorar: chamar fora da UI. */
    fun layout(settings: LayoutSettings, onProgress: (Int, Int) -> Unit = { _, _ -> })

    fun pageOf(loc: Locator): Int
    fun pageStart(page: Int): Locator
    /** Fim exclusivo da página. */
    fun pageEnd(page: Int): Locator

    fun sectionText(section: Int): String
    fun sectionTitle(section: Int): String

    /** Desenha a página inteira (fundo, texto, destaques) no canvas do tamanho da tela. */
    fun drawPage(page: Int, canvas: Canvas, marks: List<Mark>)

    /**
     * Desenha só o trecho visível da página ampliada pela pinça: equivale a desenhar a página
     * com o canvas transladado por (panX, panY) e escalado por [zoom]. O PDF sobrescreve para
     * renderizar nítido na escala ampliada.
     */
    fun drawViewport(page: Int, canvas: Canvas, marks: List<Mark>, zoom: Float, panX: Float, panY: Float) {
        canvas.save()
        canvas.translate(panX, panY)
        canvas.scale(zoom, zoom)
        drawPage(page, canvas, marks)
        canvas.restore()
    }

    /** Caractere mais próximo do ponto (coordenadas da tela), ou null se não houver texto ali. */
    fun charAt(page: Int, x: Float, y: Float): Locator?

    /** Retângulos (um por linha) que cobrem o trecho [start, end) dentro da página. */
    fun rects(page: Int, start: Locator, end: Locator): List<RectF>

    fun close()
}
