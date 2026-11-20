package dev.reisarthur.papyrus.epub

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Representação "limpa" de um capítulo de EPUB, gerada uma vez na importação.
 * O leitor nunca mais olha o HTML original: só estes blocos já formatados.
 */
sealed class Block {
    data class Para(val runs: List<Run>, val kind: Kind, val flags: Int = 0) : Block() {
        val text: String get() = runs.joinToString("") { it.text }
    }
    data class Image(val file: String, val width: Int, val height: Int) : Block()
    data object Separator : Block()

    enum class Kind { BODY, H1, H2, H3, QUOTE, CENTER, RIGHT, PRE, LIST }

    companion object {
        const val NO_INDENT = 1
        /** Sem espaço extra depois (linhas de poema, quebras com <br>). */
        const val TIGHT = 2
    }
}

/** Trecho de texto com estilo (bits de [Style]). */
data class Run(val text: String, val style: Int)

object Style {
    const val ITALIC = 1
    const val BOLD = 2
    const val SUP = 4
    const val SUB = 8
    const val MONO = 16
    const val SMALL_CAPS = 32
}

class Chapter(val title: String, val blocks: List<Block>) {

    /**
     * Texto puro do capítulo + início de cada bloco. É a "verdade" para offsets:
     * o leitor monta o texto com estilos exatamente na mesma ordem.
     */
    fun assemble(): Pair<String, IntArray> {
        val sb = StringBuilder()
        val starts = IntArray(blocks.size)
        blocks.forEachIndexed { i, b ->
            starts[i] = sb.length
            sb.append(blockText(b)).append('\n')
        }
        return sb.toString() to starts
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        put("blocks", JSONArray().apply { blocks.forEach { put(blockToJson(it)) } })
    }

    /** Gravado em gzip: o JSON dos capítulos ocupa ~4x menos no aparelho. */
    fun save(file: File) {
        val tmp = File(file.path + ".tmp")
        java.util.zip.GZIPOutputStream(tmp.outputStream(), 1 shl 16).bufferedWriter(Charsets.UTF_8).use {
            it.write(toJson().toString())
        }
        tmp.renameTo(file) // troca atômica: nunca fica um capítulo pela metade
    }

    companion object {
        const val IMAGE_CHAR = "￼"
        const val SEPARATOR_TEXT = "⁂"

        fun blockText(b: Block): String = when (b) {
            is Block.Para -> b.text
            is Block.Image -> IMAGE_CHAR
            Block.Separator -> SEPARATOR_TEXT
        }

        fun load(file: File): Chapter {
            val bytes = file.readBytes()
            val gz = bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
            val json = if (gz) java.util.zip.GZIPInputStream(bytes.inputStream()).reader(Charsets.UTF_8).use { it.readText() }
            else String(bytes, Charsets.UTF_8)
            val chapter = parse(JSONObject(json))
            // livro importado antes da compactação: compacta agora, uma vez só
            if (!gz) runCatching { chapter.save(file) }
            return chapter
        }

        private fun parse(o: JSONObject): Chapter {
            val arr = o.getJSONArray("blocks")
            val blocks = ArrayList<Block>(arr.length())
            for (i in 0 until arr.length()) blocks.add(blockFromJson(arr.getJSONObject(i)))
            return Chapter(o.optString("title"), blocks)
        }

        private fun blockToJson(b: Block): JSONObject = when (b) {
            is Block.Para -> JSONObject().apply {
                put("t", "p")
                put("k", b.kind.ordinal)
                if (b.flags != 0) put("f", b.flags)
                put("r", JSONArray().apply {
                    b.runs.forEach { r -> put(JSONArray().put(r.text).put(r.style)) }
                })
            }
            is Block.Image -> JSONObject().put("t", "img").put("src", b.file).put("w", b.width).put("h", b.height)
            Block.Separator -> JSONObject().put("t", "hr")
        }

        private fun blockFromJson(o: JSONObject): Block = when (o.getString("t")) {
            "img" -> Block.Image(o.getString("src"), o.optInt("w"), o.optInt("h"))
            "hr" -> Block.Separator
            else -> {
                val r = o.getJSONArray("r")
                val runs = (0 until r.length()).map {
                    val pair = r.getJSONArray(it)
                    // livros importados antes da normalização também ficam com acentos compostos
                    Run(java.text.Normalizer.normalize(pair.getString(0), java.text.Normalizer.Form.NFC), pair.getInt(1))
                }
                Block.Para(runs, Block.Kind.entries[o.getInt("k")], o.optInt("f"))
            }
        }
    }
}
