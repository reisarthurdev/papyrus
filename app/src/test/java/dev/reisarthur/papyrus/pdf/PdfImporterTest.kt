package dev.reisarthur.papyrus.pdf

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Extrai o texto de um PDF de exemplo com o mesmo código da importação (pdfbox real, numa JVM
 * com o Android simulado). Garante que a seleção/notas/busca dos PDFs têm o texto de que precisam.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfImporterTest {

    private fun sample(): File {
        val f = File.createTempFile("amostra", ".pdf")
        javaClass.getResourceAsStream("/amostra.pdf")!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    private fun extract(parallelism: Int) {
        PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication())
        val out = File.createTempFile("papyrus", "").apply { delete(); mkdirs() }
        PdfImporter(sample(), out, "amostra.pdf").extractText(3, parallelism) { _, _ -> }
        val store = PdfTextStore(out)
        assertTrue("texto não gravado", store.exists)
        assertEquals(3, store.pageCount)
        val page = store.page(0)
        println("página 1: " + page.text.take(120).replace('\n', '|'))
        assertTrue(page.text.contains("Introdução à Física 1"))
        // caixa da primeira letra dentro da página e com altura
        assertTrue(page.bottom(0) > page.top(0) && page.left(0) in 0f..1f)
    }

    @Test fun extraiTextoSequencial() = extract(1)

    @Test fun extraiTextoEmParalelo() = extract(2)
}
