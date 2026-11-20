package dev.reisarthur.papyrus.data

import java.io.File

enum class BookFormat { EPUB, PDF }

/** Estado de importação de um livro na biblioteca. */
enum class BookStatus { READY, IMPORTING, FAILED, MISSING }

/**
 * Posição estável dentro de um livro, independente do tamanho da fonte:
 * - EPUB: [section] = capítulo, [offset] = caractere dentro do texto do capítulo.
 * - PDF:  [section] = página,   [offset] = caractere dentro do texto extraído da página.
 */
data class Locator(val section: Int, val offset: Int) : Comparable<Locator> {
    override fun compareTo(other: Locator): Int =
        if (section != other.section) section.compareTo(other.section) else offset.compareTo(other.offset)
}

data class Book(
    val id: Long,
    /** Identifica o arquivo entre varreduras: nome + tamanho. */
    val key: String,
    val uri: String,
    val fileName: String,
    val title: String,
    val author: String,
    val format: BookFormat,
    val status: BookStatus,
    /** Número de capítulos (EPUB) ou páginas (PDF). */
    val sections: Int,
    val lastSection: Int,
    val lastOffset: Int,
    val progress: Float,
    val lastOpened: Long,
    val addedAt: Long,
) {
    val lastLocator get() = Locator(lastSection, lastOffset)
}

/** Destaque colorido com nota opcional, ancorado num trecho do texto. */
data class Annotation(
    val id: Long,
    val bookId: Long,
    val start: Locator,
    val end: Locator,
    val color: Int,
    val note: String,
    val quote: String,
    val createdAt: Long,
)

data class Bookmark(
    val id: Long,
    val bookId: Long,
    val at: Locator,
    val label: String,
    val createdAt: Long,
)

/** Entrada do sumário. */
data class TocEntry(val title: String, val level: Int, val at: Locator)

/** Onde ficam os arquivos processados de um livro (capítulos formatados, imagens, capa, índice). */
object BookFiles {
    fun dir(filesDir: File, bookId: Long) = File(filesDir, "books/$bookId").apply { mkdirs() }
    fun cover(filesDir: File, bookId: Long) = File(dir(filesDir, bookId), "cover.jpg")
    fun toc(filesDir: File, bookId: Long) = File(dir(filesDir, bookId), "toc.json")
}
