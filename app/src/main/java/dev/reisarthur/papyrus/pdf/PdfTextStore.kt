package dev.reisarthur.papyrus.pdf

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.Deflater
import java.util.zip.InflaterInputStream

/**
 * Texto de uma página de PDF com a caixa de cada caractere (coordenadas 0..1 da página).
 * É o que permite selecionar palavras, destacar, buscar e reorganizar o PDF no zoom.
 */
class PageText(val text: String, private val boxes: FloatArray) {
    val length get() = text.length
    fun left(i: Int) = boxes[i * 4]
    fun top(i: Int) = boxes[i * 4 + 1]
    fun right(i: Int) = boxes[i * 4 + 2]
    fun bottom(i: Int) = boxes[i * 4 + 3]

    companion object {
        val EMPTY = PageText("", FloatArray(0))
    }
}

/**
 * Armazenamento compacto, pensado para ocupar pouco espaço no aparelho:
 * - coordenadas em 16 bits (precisão de 1/65535 da página, bem abaixo de um pixel);
 * - cada página comprimida separadamente (dá para ler só a página da tela);
 * - text2.idx guarda onde começa cada página em text2.dat.
 * Resultado: cerca de 4x menor que guardar floats crus.
 */
class PdfTextStore(dir: File) {
    private val dat = File(dir, "text2.dat")
    private val idx = File(dir, "text2.idx")

    val exists get() = dat.exists() && idx.exists()

    class Writer(store: PdfTextStore) : AutoCloseable {
        private val datFile = store.dat
        private val idxFile = store.idx
        private val datOut = BufferedOutputStream(datFile.outputStream(), 1 shl 16)
        private val offsets = ArrayList<Long>()
        private var written = 0L

        /** [boxes] tem 4 floats (esquerda, topo, direita, base) por caractere de [text]. */
        fun write(text: String, boxes: FloatArray) = writeEncoded(encode(text, boxes))

        /** Página já codificada por [encode] (as threads da importação codificam em paralelo). */
        fun writeEncoded(page: ByteArray) {
            offsets += written
            datOut.write(page)
            written += page.size
        }

        override fun close() {
            datOut.close()
            offsets += written // fim da última página
            DataOutputStream(BufferedOutputStream(idxFile.outputStream())).use { out ->
                out.writeInt(offsets.size)
                offsets.forEach(out::writeLong)
            }
        }
    }

    private var offsets: LongArray? = null

    companion object {
        /**
         * Codifica uma página: int tamanho, caracteres (UTF-16) e caixas em 16 bits, tudo compactado.
         * Monta os bytes num buffer e compacta de uma vez: escrever campo a campo direto no
         * compactador chamava o zlib a cada 2 bytes (era a parte mais lenta da importação).
         */
        fun encode(text: String, boxes: FloatArray): ByteArray {
            require(boxes.size == text.length * 4)
            val buf = java.nio.ByteBuffer.allocate(4 + text.length * 2 + boxes.size * 2)
            buf.putInt(text.length)
            for (c in text) buf.putChar(c)
            for (f in boxes) buf.putShort((f.coerceIn(0f, 1f) * 65535f + 0.5f).toInt().toShort())
            val deflater = Deflater(6) // nível padrão: quase o mesmo tamanho do máximo, bem mais rápido
            try {
                deflater.setInput(buf.array())
                deflater.finish()
                val out = ByteArrayOutputStream(buf.capacity() / 3 + 64)
                val chunk = ByteArray(16 * 1024)
                while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
                return out.toByteArray()
            } finally {
                deflater.end() // libera a memória nativa na hora (não espera o coletor)
            }
        }
    }

    @Synchronized
    private fun offsets(): LongArray = offsets ?: DataInputStream(idx.inputStream().buffered()).use { input ->
        LongArray(input.readInt()) { input.readLong() }
    }.also { offsets = it }

    val pageCount: Int get() = if (exists) offsets().size - 1 else 0

    /** Pode ser chamado de várias threads ao mesmo tempo (a paginação lê páginas em paralelo). */
    fun page(index: Int): PageText {
        if (!exists) return PageText.EMPTY
        val offs = offsets()
        if (index < 0 || index + 1 >= offs.size) return PageText.EMPTY
        val compressed = ByteArray((offs[index + 1] - offs[index]).toInt())
        RandomAccessFile(dat, "r").use { raf ->
            raf.seek(offs[index])
            raf.readFully(compressed)
        }
        // descompacta tudo de uma vez e lê do buffer (ler campo a campo do descompactador era lento)
        val bytes = InflaterInputStream(compressed.inputStream()).use { it.readBytes() }
        val buf = java.nio.ByteBuffer.wrap(bytes)
        val len = buf.int
        val chars = CharArray(len) { buf.char }
        val boxes = FloatArray(len * 4) { (buf.short.toInt() and 0xFFFF) / 65535f }
        return PageText(String(chars), boxes)
    }
}
