package dev.reisarthur.papyrus.pdf

import android.graphics.RectF
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Guarda em disco o resultado do [PdfReflow] de cada configuração (tela × zoom), compactado.
 * Assim um PDF é paginado uma vez por configuração; nas próximas aberturas só é lido.
 * Mantém poucas configurações por livro para não crescer sem limite.
 */
object ReflowStore {

    private const val VERSION = 1
    private const val MAX_FILES = 4

    class Result(val pages: List<PdfReflow.SubPage>, val firstSub: IntArray)

    private fun file(dir: File, key: String) = File(dir, "reflow_${Integer.toHexString(key.hashCode())}.bin")

    fun load(dir: File, key: String): Result? {
        val f = file(dir, key)
        if (!f.exists()) return null
        return runCatching {
            DataInputStream(InflaterInputStream(f.inputStream()).buffered()).use { input ->
                // a chave inteira fica gravada: colisão de hash não carrega o reflow errado
                if (input.readInt() != VERSION || input.readUTF() != key) return null
                val firstSub = IntArray(input.readInt()) { input.readInt() }
                val pages = List(input.readInt()) {
                    val pdfPage = input.readInt()
                    val start = input.readInt()
                    val end = input.readInt()
                    val pieces = List(input.readInt()) {
                        PdfReflow.Piece(input.readInt(), input.readInt(), input.readRect(), input.readRect())
                    }
                    val figures = List(input.readInt()) { PdfReflow.Figure(input.readRect(), input.readRect()) }
                    PdfReflow.SubPage(pdfPage, start, end, pieces, figures)
                }
                f.setLastModified(System.currentTimeMillis()) // usado agora: é o último a ser descartado
                Result(pages, firstSub)
            }
        }.getOrNull()
    }

    fun save(dir: File, key: String, result: Result) {
        runCatching {
            val tmp = File(dir, "reflow.tmp")
            DataOutputStream(DeflaterOutputStream(tmp.outputStream()).buffered()).use { out ->
                out.writeInt(VERSION)
                out.writeUTF(key)
                out.writeInt(result.firstSub.size)
                result.firstSub.forEach { out.writeInt(it) }
                out.writeInt(result.pages.size)
                for (sub in result.pages) {
                    out.writeInt(sub.pdfPage); out.writeInt(sub.startOffset); out.writeInt(sub.endOffset)
                    out.writeInt(sub.pieces.size)
                    sub.pieces.forEach { out.writeInt(it.start); out.writeInt(it.end); out.writeRect(it.src); out.writeRect(it.dst) }
                    out.writeInt(sub.figures.size)
                    sub.figures.forEach { out.writeRect(it.src); out.writeRect(it.dst) }
                }
            }
            tmp.renameTo(file(dir, key)) // arquivo só aparece completo
            dir.listFiles { f -> f.name.startsWith("reflow_") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_FILES)
                ?.forEach { it.delete() }
        }
    }

    private fun DataInputStream.readRect() = RectF(readFloat(), readFloat(), readFloat(), readFloat())

    private fun DataOutputStream.writeRect(r: RectF) {
        writeFloat(r.left); writeFloat(r.top); writeFloat(r.right); writeFloat(r.bottom)
    }
}
