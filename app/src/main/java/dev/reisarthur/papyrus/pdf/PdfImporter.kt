package dev.reisarthur.papyrus.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import dev.reisarthur.papyrus.data.Locator
import dev.reisarthur.papyrus.data.TocEntry
import dev.reisarthur.papyrus.library.ImportResult
import dev.reisarthur.papyrus.library.TocIO
import dev.reisarthur.papyrus.library.saveCover
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Importa um PDF: gera a capa (PdfRenderer), extrai o texto com a posição de cada letra
 * (pdfbox) e lê o sumário (outline). A renderização no leitor continua sendo nativa.
 */
/** [displayName]: nome do arquivo original (o [pdfFile] é uma cópia temporária com outro nome). */
class PdfImporter(private val pdfFile: File, private val outDir: File, private val displayName: String) {

    companion object {
        /** Muda quando o formato dos dados do PDF muda; livros sem ela são reimportados uma vez. */
        const val FORMAT_MARKER = "pdf.v2"
        private const val TEXT_WEIGHT = 4

        /** Grava a 1ª página como capa em [out]; devolve o número de páginas. */
        fun renderCover(fd: ParcelFileDescriptor, out: File): Int = PdfRenderer(fd).use { renderer ->
            renderer.openPage(0).use { page ->
                val w = 420
                val h = (w * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                saveCover(bmp, out)
                bmp.recycle()
            }
            renderer.pageCount
        }
    }

    /**
     * [onProgress] recebe (feito, total) em unidades de trabalho: cada página vale [TEXT_WEIGHT]
     * na extração de texto (a parte pesada) e 1 na busca de figuras.
     * [parallelism]: quantas threads dividem as páginas deste PDF (cada uma abre o documento
     * por conta própria, porque o pdfbox não pode ser usado por duas threads ao mesmo tempo).
     */
    fun import(onProgress: (Int, Int) -> Unit, parallelism: Int = 1): ImportResult {
        val pageCount = renderCover()
        // título = nome do arquivo: o título interno de PDFs costuma ser lixo ("Microsoft Word - doc1")
        val title = displayName.substringBeforeLast('.')
        var author = ""
        runCatching {
            load().use { doc ->
                doc.documentInformation?.author?.takeIf { it.isNotBlank() }?.let { author = it.trim() }
                TocIO.save(File(outDir, "toc.json"), readOutline(doc))
            }
            val total = pageCount * (TEXT_WEIGHT + 1)
            val done = AtomicInteger()
            val report = { units: Int -> onProgress(done.addAndGet(units), total) }
            // blocos contíguos de páginas, um por thread; cada um devolve suas páginas já codificadas
            val ranges = splitRanges(pageCount, parallelism.coerceIn(1, maxOf(1, pageCount / 8)))
            val chunks = if (ranges.size == 1) {
                listOf(processRange(ranges[0], report))
            } else {
                val pool = Executors.newFixedThreadPool(ranges.size - 1)
                try {
                    // a thread atual faz o primeiro bloco; as outras, o resto
                    val others = ranges.drop(1).map { r -> pool.submit(Callable { processRange(r, report) }) }
                    listOf(processRange(ranges[0], report)) + others.map { it.get() }
                } finally {
                    pool.shutdown()
                }
            }
            PdfTextStore.Writer(PdfTextStore(outDir)).use { writer ->
                chunks.forEach { c -> c.pages.forEach(writer::writeEncoded) }
            }
            // onde ficam imagens e desenhos de cada página (para o zoom manter figuras inteiras)
            PdfFigures.save(outDir, chunks.flatMap { it.figures })
        }.onFailure {
            // PDF escaneado/protegido: o livro abre, só sem recursos de texto.
            File(outDir, "text2.dat").delete()
            File(outDir, "text2.idx").delete()
        }
        // marca de versão gravada SEMPRE (mesmo se o pdfbox falhou): impede reimportar em ciclo
        File(outDir, FORMAT_MARKER).writeText("ok")
        return ImportResult(title, author, pageCount)
    }

    /** Memória até 16 MB por documento aberto; o resto vai para arquivo temporário. */
    private fun load() = PDDocument.load(pdfFile, MemoryUsageSetting.setupMixed(16L shl 20))

    private class Chunk(val pages: List<ByteArray>, val figures: List<PdfFigures.Page>)

    /** Extrai texto e figuras das páginas [range] (índices a partir de 0) com um documento próprio. */
    private fun processRange(range: IntRange, report: (Int) -> Unit): Chunk = load().use { doc ->
        val pages = ArrayList<ByteArray>(range.count())
        val extractor = TextExtractor(range.first, { text, boxes -> pages += PdfTextStore.encode(text, boxes) }) { report(TEXT_WEIGHT) }
        extractor.setStartPage(range.first + 1) // o pdfbox conta páginas a partir de 1
        extractor.setEndPage(range.last + 1)
        extractor.getText(doc)
        extractor.fillUntil(range.last + 1)
        val figures = range.map { i ->
            val page = runCatching { PdfFigures.collect(doc.getPage(i)) }.getOrDefault(PdfFigures.Page.EMPTY)
            report(1)
            page
        }
        Chunk(pages, figures)
    }

    private fun splitRanges(count: Int, parts: Int): List<IntRange> {
        if (count <= 0) return listOf(0 until 0)
        val size = (count + parts - 1) / parts
        return (0 until count step size).map { it until minOf(count, it + size) }
    }

    private fun renderCover(): Int =
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { renderCover(it, File(outDir, "cover.jpg")) }

    private fun readOutline(doc: PDDocument): List<TocEntry> {
        val out = ArrayList<TocEntry>()
        fun walk(node: PDOutlineNode, level: Int) {
            for (item in node.children()) {
                val page = runCatching { item.findDestinationPage(doc) }.getOrNull()
                val index = page?.let { doc.pages.indexOf(it) } ?: -1
                if (index >= 0) out.add(TocEntry(item.title ?: "", level, Locator(index, 0)))
                if (level < 4) walk(item, level + 1)
            }
        }
        doc.documentCatalog.documentOutline?.let { walk(it, 0) }
        return out
    }

    /** Captura cada caractere com sua caixa, página por página. */
    private class TextExtractor(
        firstPage: Int,
        private val sink: (String, FloatArray) -> Unit,
        private val onPage: () -> Unit,
    ) : PDFTextStripper() {
        private val sb = StringBuilder()
        private var boxes = FloatArray(4096)
        private var pageW = 1f
        private var pageH = 1f
        /** Próxima página a gravar (índice absoluto; páginas sem conteúdo são puladas pelo pdfbox). */
        private var written = firstPage

        override fun startPage(page: PDPage) {
            sb.setLength(0)
            val box = page.cropBox
            val rotated = page.rotation % 180 != 0
            pageW = if (rotated) box.height else box.width
            pageH = if (rotated) box.width else box.height
            super.startPage(page)
        }

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            for (p in textPositions) {
                val unicode = p.unicode ?: continue
                if (unicode.isEmpty()) continue
                val h = p.heightDir.coerceAtLeast(1f)
                val left = p.xDirAdj
                val bottom = p.yDirAdj + h * 0.25f
                val top = p.yDirAdj - h * 1.1f
                val step = p.widthDirAdj / unicode.length
                // ligaduras ("fi") viram vários caracteres dividindo a largura
                for ((k, c) in unicode.withIndex()) {
                    add(c, left + step * k, top, left + step * (k + 1), bottom)
                }
            }
        }

        override fun writeWordSeparator() = addAfterLast(' ')
        override fun writeLineSeparator() = addAfterLast('\n')

        override fun endPage(page: PDPage) {
            super.endPage(page)
            fillUntil(currentPageNo - 1)
            sink(sb.toString(), boxes.copyOf(sb.length * 4))
            written++
            onPage()
        }

        /** Grava páginas vazias até a página [count] (exclusiva), mantendo o índice alinhado com o PDF. */
        fun fillUntil(count: Int) {
            while (written < count) {
                sink("", FloatArray(0))
                written++
                onPage()
            }
        }

        private fun add(c: Char, l: Float, t: Float, r: Float, b: Float) {
            val i = sb.length
            if ((i + 1) * 4 > boxes.size) boxes = boxes.copyOf(boxes.size * 2)
            boxes[i * 4] = (l / pageW).coerceIn(0f, 1f)
            boxes[i * 4 + 1] = (t / pageH).coerceIn(0f, 1f)
            boxes[i * 4 + 2] = (r / pageW).coerceIn(0f, 1f)
            boxes[i * 4 + 3] = (b / pageH).coerceIn(0f, 1f)
            sb.append(c)
        }

        /** Separadores não têm posição própria: usam uma caixa de largura zero após o último caractere. */
        private fun addAfterLast(c: Char) {
            val i = sb.length
            if (i == 0) {
                add(c, 0f, 0f, 0f, 0f)
                return
            }
            val r = boxes[(i - 1) * 4 + 2] * pageW
            val t = boxes[(i - 1) * 4 + 1] * pageH
            val b = boxes[(i - 1) * 4 + 3] * pageH
            add(c, r, t, r, b)
        }
    }
}
