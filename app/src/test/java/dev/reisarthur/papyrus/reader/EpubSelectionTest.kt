package dev.reisarthur.papyrus.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import dev.reisarthur.papyrus.data.Locator
import dev.reisarthur.papyrus.epub.EpubImporter
import dev.reisarthur.papyrus.ui.Fonts
import dev.reisarthur.papyrus.ui.ReaderTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * A seleção precisa cobrir exatamente as letras desenhadas. O teste importa um EPUB, desenha
 * páginas de verdade (gráficos nativos) e compara o retângulo de cada palavra com a tinta
 * das letras naquela linha.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class EpubSelectionTest {

    @Test
    fun retangulosCobremAsLetras() {
        val app = RuntimeEnvironment.getApplication()
        Fonts.init(app.assets)
        val epub = File.createTempFile("amostra", ".epub")
        javaClass.getResourceAsStream("/amostra.epub")!!.use { i -> epub.outputStream().use { i.copyTo(it) } }
        val dir = File.createTempFile("livro", "").apply { delete(); mkdirs() }
        EpubImporter(epub, dir, "amostra.epub").import { _, _ -> }

        val s = LayoutSettings(
            width = 1080, height = 2298, density = 2.75f, fontPx = 18 * 2.75f, lineSpacing = 1.7f,
            marginPx = 66, justify = true, theme = ReaderTheme.DARK, pdfInvert = true, insetTop = 0, insetBottom = 0,
        )
        val content = EpubContent(dir)
        content.layout(s)
        val bmp = Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ARGB_8888)
        var worst = 0f
        var worstWord = ""
        for (page in 0 until minOf(3, content.pageCount)) {
            Canvas(bmp).also { content.drawPage(page, it, emptyList()) }
            val debug = bmp.copy(Bitmap.Config.ARGB_8888, true)
            val dc = Canvas(debug)
            val stroke = android.graphics.Paint().apply { style = android.graphics.Paint.Style.STROKE; color = Color.RED; strokeWidth = 2f }
            val start = content.pageStart(page)
            val end = content.pageEnd(page)
            val text = content.sectionText(start.section)
            Regex("[\\p{L}]{4,}").findAll(text.substring(start.offset, end.offset)).forEach { m ->
                val a = start.offset + m.range.first
                val rects = content.rects(page, Locator(start.section, a), Locator(start.section, a + m.value.length))
                if (rects.size != 1) return@forEach // palavra hifenizada entre linhas: fora deste teste
                val r = rects[0]
                dc.drawRect(r, stroke)
                // tinta dentro da faixa da linha, perto do retângulo
                val y0 = r.top.toInt().coerceAtLeast(0)
                val y1 = r.bottom.toInt().coerceAtMost(s.height - 1)
                fun ink(x: Int) = (y0..y1).any { y -> Color.red(bmp.getPixel(x, y)) > 110 }
                val gap = (s.fontPx * 0.18f).toInt() // menor que um espaço entre palavras
                // a partir do meio do retângulo, anda até achar um vão do tamanho de um espaço
                val mid = r.centerX().toInt()
                var l = mid; var empty = 0
                while (l > 0 && empty < gap) { l--; empty = if (ink(l)) 0 else empty + 1 }
                l += empty
                var rr = mid; empty = 0
                while (rr < s.width - 1 && empty < gap) { rr++; empty = if (ink(rr)) 0 else empty + 1 }
                rr -= empty
                val err = maxOf(abs(l - r.left), abs(rr - r.right))
                if (err > worst) { worst = err; worstWord = "${m.value} (página ${page + 1}): tinta $l..$rr, retângulo ${r.left}..${r.right}" }
            }
            System.getenv("PAPYRUS_DEBUG_DIR")?.let { d ->
                File(d).mkdirs()
                File(d, "pagina${page + 1}.png").outputStream().use { debug.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        println("pior diferença: $worst px — $worstWord")
        assertTrue("seleção desalinhada: $worstWord", worst <= 12f)
    }
}
