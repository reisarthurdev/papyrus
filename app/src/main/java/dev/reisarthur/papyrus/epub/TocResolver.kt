package dev.reisarthur.papyrus.epub

import dev.reisarthur.papyrus.data.Locator
import dev.reisarthur.papyrus.data.TocEntry
import dev.reisarthur.papyrus.util.TextUtil

/** Reencontra o destino de cada entrada do sumário pelo título escrito no próprio livro. */
class TocResolver(private val chapters: List<Chapter>) {
    private val lengths = chapters.map { it.assemble().first.length }

    /**
     * Confere cada entrada do sumário pelo TÍTULO, não só pela posição salva na importação.
     * A posição pode estar errada (âncora que o livro não tinha, livro importado antes de uma
     * correção do texto); o título do capítulo, escrito no próprio texto, não muda.
     *
     * Regras:
     * - se a posição salva já cai num título que bate, fica como está;
     * - senão, usa o próximo título que bate depois da entrada anterior (o sumário segue a ordem
     *   de leitura), preferindo títulos de verdade (h1–h3) a parágrafos curtos;
     * - páginas de "sumário impresso" dentro do livro (muitos títulos listados juntos) são ignoradas;
     * - sem nenhum título que bata, mantém a posição salva (limitada ao tamanho do capítulo).
     */
    fun resolve(toc: List<TocEntry>): List<TocEntry> {
        if (toc.isEmpty()) return toc
        class Candidate(val at: Locator, val text: String, val isHeading: Boolean)

        val candidates = ArrayList<Candidate>()
        chapters.forEachIndexed { c, chapter ->
            val starts = chapter.assemble().second
            chapter.blocks.forEachIndexed { i, b ->
                if (b is Block.Para && b.text.length <= 140) {
                    val heading = b.kind == Block.Kind.H1 || b.kind == Block.Kind.H2 || b.kind == Block.Kind.H3
                    val text = normTitle(b.text)
                    if (text.length >= 2) candidates += Candidate(Locator(c, starts[i]), text, heading)
                }
            }
        }
        val keys = toc.map { normTitle(it.title) }

        fun matches(key: String, text: String) =
            key.length >= 2 && (text == key || text.startsWith("$key ") || (text.length >= 4 && key.startsWith("$text ")))

        // capítulo que lista vários títulos do sumário = sumário impresso no livro: não serve de destino
        val hitsPerChapter = HashMap<Int, Int>()
        keys.forEach { key ->
            candidates.filter { !it.isHeading && matches(key, it.text) }.map { it.at.section }.distinct()
                .forEach { hitsPerChapter[it] = (hitsPerChapter[it] ?: 0) + 1 }
        }
        val contentsPages = hitsPerChapter.filterValues { it >= maxOf(3, toc.size / 3) }.keys

        var last = Locator(0, 0)
        return toc.mapIndexed { i, entry ->
            val key = keys[i]
            val stored = Locator(entry.at.section.coerceIn(0, chapters.size - 1),
                entry.at.offset.coerceIn(0, maxOf(0, lengths.getOrElse(entry.at.section) { 0 } - 1)))
            val usable = candidates.filter { matches(key, it.text) && (it.isHeading || it.at.section !in contentsPages) }
            val resolved = when {
                usable.any { it.at == stored } -> stored
                usable.isEmpty() -> stored
                else -> {
                    val ahead = usable.filter { it.at >= last }
                    (ahead.firstOrNull { it.isHeading } ?: ahead.firstOrNull()
                        ?: usable.minByOrNull { kotlin.math.abs(it.at.section - stored.section) })!!.at
                }
            }
            if (resolved >= last) last = resolved
            entry.copy(at = resolved)
        }
    }

    /** "Capítulo 20 — O Fim!" -> "capitulo 20 o fim": compara títulos sem acento, caixa ou pontuação. */
    private fun normTitle(s: String): String =
        TextUtil.foldKey(s).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

}
