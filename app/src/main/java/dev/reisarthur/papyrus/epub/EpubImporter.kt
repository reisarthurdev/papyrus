package dev.reisarthur.papyrus.epub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.reisarthur.papyrus.data.Locator
import dev.reisarthur.papyrus.data.TocEntry
import dev.reisarthur.papyrus.library.ImportResult
import dev.reisarthur.papyrus.library.TocIO
import dev.reisarthur.papyrus.library.saveCover
import java.io.File
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.zip.ZipFile

/**
 * Lê o pacote EPUB (zip + OPF), formata cada capítulo da espinha e grava tudo
 * em [outDir] já pronto para o leitor: ch_N.json, imagens, capa e sumário.
 */
/** [displayName]: nome do arquivo original, usado quando o EPUB não informa o título. */
class EpubImporter(private val epubFile: File, private val outDir: File, private val displayName: String) {

    private class Item(val id: String, val path: String, val mediaType: String, val properties: String)

    private lateinit var zip: ZipFile
    private val images = HashMap<String, Block.Image?>()
    private var imageCounter = 0

    fun import(onProgress: (Int, Int) -> Unit): ImportResult {
        ZipFile(epubFile).use { z ->
            zip = z
            val opfPath = findOpfPath()
            val opfDir = opfPath.substringBeforeLast('/', "")
            val opf = HtmlTokenizer.tokenize(readText(opfPath) ?: error("OPF não encontrado"))

            val meta = readMetadata(opf)
            val manifest = readManifest(opf, opfDir)
            val spine = readSpine(opf).mapNotNull { manifest[it] }
                .filter { it.mediaType.contains("html") || it.path.endsWith("htm") || it.path.endsWith("html") }

            val css = CssHints()
            manifest.values.filter { it.mediaType == "text/css" }.forEach { item ->
                readText(item.path)?.let(css::addStylesheet)
            }

            // Formata cada capítulo; capítulos vazios são descartados.
            val chapterIndexByPath = HashMap<String, Int>()
            val anchorBlocks = HashMap<String, Int>() // "path#id" -> bloco
            val chapters = ArrayList<Chapter>()
            spine.forEachIndexed { i, item ->
                onProgress(i, spine.size)
                val html = readText(item.path) ?: return@forEachIndexed
                val baseDir = item.path.substringBeforeLast('/', "")
                val formatter = EpubFormatter(css) { src -> image(resolve(baseDir, src)) }
                val chapter = formatter.format(html, "Capítulo ${chapters.size + 1}")
                if (chapter.blocks.isEmpty()) return@forEachIndexed
                val index = chapters.size
                chapterIndexByPath[item.path] = index
                formatter.anchors.forEach { (id, block) -> anchorBlocks["${item.path}#$id"] = block }
                chapter.save(File(outDir, "ch_$index.json"))
                chapters.add(chapter)
            }
            if (chapters.isEmpty()) error("Nenhum capítulo com texto")

            val toc = readToc(opf, manifest, chapters, chapterIndexByPath, anchorBlocks)
            TocIO.save(File(outDir, "toc.json"), toc)
            extractCover(meta.coverId, manifest, spine)
            return ImportResult(meta.title, meta.author, chapters.size)
        }
    }

    // ---------------- OPF ----------------

    private fun findOpfPath(): String {
        val container = readText("META-INF/container.xml")
        val fromContainer = container?.let {
            HtmlTokenizer.tokenize(it).filterIsInstance<Token.Start>()
                .firstOrNull { t -> t.name == "rootfile" }?.attr("full-path")
        }
        return fromContainer ?: zip.entries().asSequence().map { it.name }.first { it.endsWith(".opf") }
    }

    private class Meta(val title: String, val author: String, val coverId: String?)

    private fun readMetadata(tokens: List<Token>): Meta {
        var title = ""
        var author = ""
        var coverId: String? = null
        var current: String? = null
        for (t in tokens) {
            when (t) {
                is Token.Start -> {
                    current = t.name
                    if (t.name == "meta" && t.attrs["name"] == "cover") coverId = t.attrs["content"]
                }
                is Token.Text -> when (current) {
                    "title" -> if (title.isEmpty()) title = t.text.trim()
                    "creator" -> if (author.isEmpty()) author = t.text.trim()
                }
                is Token.End -> current = null
            }
            if (t is Token.Start && t.name == "manifest") break
        }
        return Meta(title.ifEmpty { displayName.substringBeforeLast('.') }, author, coverId)
    }

    private fun readManifest(tokens: List<Token>, opfDir: String): Map<String, Item> =
        tokens.filterIsInstance<Token.Start>().filter { it.name == "item" }.mapNotNull { t ->
            val id = t.attrs["id"] ?: return@mapNotNull null
            val href = t.attrs["href"] ?: return@mapNotNull null
            id to Item(id, resolve(opfDir, href), t.attrs["media-type"].orEmpty(), t.attrs["properties"].orEmpty())
        }.toMap()

    private fun readSpine(tokens: List<Token>): List<String> =
        tokens.filterIsInstance<Token.Start>().filter { it.name == "itemref" }.mapNotNull { it.attrs["idref"] }

    // ---------------- Sumário ----------------

    private fun readToc(
        opf: List<Token>,
        manifest: Map<String, Item>,
        chapters: List<Chapter>,
        chapterByPath: Map<String, Int>,
        anchorBlocks: Map<String, Int>,
    ): List<TocEntry> {
        val raw = ArrayList<Triple<String, String, Int>>() // título, href absoluto, nível
        val nav = manifest.values.firstOrNull { it.properties.split(' ').contains("nav") }
        if (nav != null) readText(nav.path)?.let { parseNav(it, nav.path.substringBeforeLast('/', ""), raw) }
        if (raw.isEmpty()) {
            val ncxId = opf.filterIsInstance<Token.Start>().firstOrNull { it.name == "spine" }?.attrs?.get("toc")
            val ncx = manifest[ncxId] ?: manifest.values.firstOrNull { it.mediaType.contains("dtbncx") }
            if (ncx != null) readText(ncx.path)?.let { parseNcx(it, ncx.path.substringBeforeLast('/', ""), raw) }
        }

        val blockStarts = HashMap<Int, IntArray>()
        val entries = raw.mapNotNull { (title, href, level) ->
            val path = href.substringBefore('#')
            val chapter = chapterByPath[path] ?: return@mapNotNull null
            val block = if ('#' in href) anchorBlocks[href] ?: 0 else 0
            val starts = blockStarts.getOrPut(chapter) { chapters[chapter].assemble().second }
            val offset = starts.getOrElse(block) { 0 }
            TocEntry(title.ifBlank { chapters[chapter].title }, level, Locator(chapter, offset))
        }
        return entries.ifEmpty {
            chapters.mapIndexed { i, c -> TocEntry(c.title, 0, Locator(i, 0)) }
        }
    }

    /** EPUB 3: <nav epub:type="toc"><ol><li><a href>…</a><ol>…</ol></li></ol></nav> */
    private fun parseNav(html: String, baseDir: String, out: MutableList<Triple<String, String, Int>>) {
        var inToc = false
        var navDepth = 0
        var olDepth = 0
        var href: String? = null
        val title = StringBuilder()
        for (t in HtmlTokenizer.tokenize(html)) {
            when (t) {
                is Token.Start -> when (t.name) {
                    "nav" -> {
                        val type = t.attrs["epub:type"] ?: t.attrs["type"] ?: t.attrs["role"].orEmpty()
                        if (!inToc && (type.contains("toc") || out.isEmpty() && navDepth == 0 && type.isEmpty())) inToc = true
                        if (inToc) navDepth++
                    }
                    "ol", "ul" -> if (inToc) olDepth++
                    "a" -> if (inToc) { href = t.attrs["href"]; title.setLength(0) }
                }
                is Token.Text -> if (href != null) title.append(t.text)
                is Token.End -> when (t.name) {
                    "a" -> href?.let {
                        out.add(Triple(title.toString().trim().replace(Regex("\\s+"), " "), resolve(baseDir, it, keepFragment = true), (olDepth - 1).coerceAtLeast(0)))
                        href = null
                    }
                    "ol", "ul" -> if (inToc) olDepth--
                    "nav" -> if (inToc && --navDepth == 0) return
                }
            }
        }
    }

    /** EPUB 2: <navPoint><navLabel><text>…</text></navLabel><content src/>…</navPoint> */
    private fun parseNcx(xml: String, baseDir: String, out: MutableList<Triple<String, String, Int>>) {
        var depth = 0
        var inText = false
        var label = ""
        for (t in HtmlTokenizer.tokenize(xml)) {
            when (t) {
                is Token.Start -> when (t.name) {
                    "navpoint" -> depth++
                    "text" -> { inText = true; label = "" }
                    "content" -> t.attrs["src"]?.let {
                        out.add(Triple(label.trim(), resolve(baseDir, it, keepFragment = true), (depth - 1).coerceAtLeast(0)))
                    }
                }
                is Token.Text -> if (inText) label += t.text
                is Token.End -> when (t.name) {
                    "navpoint" -> depth--
                    "text" -> inText = false
                }
            }
        }
    }

    // ---------------- Imagens e capa ----------------

    private fun image(path: String): Block.Image? = images.getOrPut(path) {
        val entry = zip.getEntry(path) ?: return@getOrPut null
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext == "svg") return@getOrPut null
        File(outDir, "img").mkdirs()
        val name = "img/${imageCounter++}.$ext"
        val file = File(outDir, name)
        zip.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            file.delete()
            null
        } else {
            Block.Image(name, opts.outWidth, opts.outHeight)
        }
    }

    private fun extractCover(coverId: String?, manifest: Map<String, Item>, spine: List<Item>) {
        val item = manifest[coverId]
            ?: manifest.values.firstOrNull { it.properties.contains("cover-image") }
            ?: manifest.values.firstOrNull { it.mediaType.startsWith("image/") && it.id.contains("cover", true) }
        val path = item?.path ?: spine.firstOrNull()?.let { first ->
            // primeira imagem do primeiro capítulo
            val html = readText(first.path) ?: return@let null
            val src = HtmlTokenizer.tokenize(html).filterIsInstance<Token.Start>()
                .firstOrNull { it.name == "img" || it.name == "image" }
                ?.attr("src", "xlink:href", "href") ?: return@let null
            resolve(first.path.substringBeforeLast('/', ""), src)
        } ?: return
        val entry = zip.getEntry(path) ?: return
        val bmp: Bitmap = zip.getInputStream(entry).use { BitmapFactory.decodeStream(it) } ?: return
        saveCover(bmp, File(outDir, "cover.jpg"))
        bmp.recycle()
    }

    // ---------------- Utilidades ----------------

    private fun readText(path: String): String? {
        val entry = zip.getEntry(path) ?: zip.getEntry(URLDecoder.decode(path, "UTF-8")) ?: return null
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
        val charset = Regex("encoding=[\"']([\\w-]+)[\"']").find(head)?.groupValues?.get(1)
            ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        return String(bytes, charset).removePrefix("﻿")
    }

    /** Resolve um href relativo a [baseDir] dentro do zip (trata ../, ./ e %20). */
    private fun resolve(baseDir: String, href: String, keepFragment: Boolean = false): String {
        val fragment = href.substringAfter('#', "")
        val clean = runCatching { URLDecoder.decode(href.substringBefore('#').replace("+", "%2B"), "UTF-8") }
            .getOrDefault(href.substringBefore('#'))
        val parts = ArrayList<String>()
        if (!clean.startsWith("/") && baseDir.isNotEmpty()) parts.addAll(baseDir.split('/'))
        for (seg in clean.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(seg)
            }
        }
        val path = parts.joinToString("/")
        return if (keepFragment && fragment.isNotEmpty()) "$path#$fragment" else path
    }
}
