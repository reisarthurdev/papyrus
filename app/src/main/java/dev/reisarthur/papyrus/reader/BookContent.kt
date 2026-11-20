package dev.reisarthur.papyrus.reader

import android.graphics.Canvas
import android.graphics.RectF
import dev.reisarthur.papyrus.data.Locator
import dev.reisarthur.papyrus.ui.ReaderTheme
import dev.reisarthur.papyrus.ui.dp

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
    /** Área ocupada por notch/barra de gestos, para o texto não ficar embaixo. */
    val insetTop: Int,
    val insetBottom: Int,
) {
    /** Só o que afeta a quebra de páginas (tema não entra: trocar cor não repagina). */
    // o "1.0" no fim era o zoom do PDF (removido); fica para os caches já gravados continuarem valendo
    fun paginationKey() = "v3|$width|$height|$fontPx|$lineSpacing|$marginPx|$justify|$insetTop|$insetBottom|1.0"
}

/**
 * Configuração de leitura atual (preferências + tamanho da tela). Usada pelo leitor e pela
 * pré-paginação feita logo após importar, para as duas gerarem exatamente a mesma chave de cache.
 */
fun readerSettings(
    context: android.content.Context, format: dev.reisarthur.papyrus.data.BookFormat,
    width: Int, height: Int, insetTop: Int, insetBottom: Int,
): LayoutSettings {
    val dm = context.resources.displayMetrics
    val prefs = dev.reisarthur.papyrus.data.Prefs
    return LayoutSettings(
        width = width,
        height = height,
        density = dm.density,
        fontPx = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, prefs.fontSize, dm),
        lineSpacing = prefs.lineSpacing,
        marginPx = context.dp(prefs.margin), // igual ao leitor: a chave do cache precisa bater
        justify = true, // todo livro é justificado
        theme = prefs.theme,
        pdfInvert = prefs.pdfDarkInvert,
        insetTop = insetTop,
        insetBottom = insetBottom,
    )
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
