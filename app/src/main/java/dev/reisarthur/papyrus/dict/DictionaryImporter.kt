package dev.reisarthur.papyrus.dict

import android.content.Context
import android.net.Uri
import android.text.TextUtils
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream

/** Um arquivo candidato a dicionário encontrado na pasta (ou escolhido manualmente). */
data class DictFile(val uri: Uri, val name: String, val size: Long)

/**
 * Importa dicionários offline para o banco local. Formatos aceitos:
 * - StarDict (.ifo + .idx/.idx.gz + .dict/.dict.dz): o formato mais comum de dicionários baixáveis;
 * - Texto tabulado (.tsv/.txt): "palavra<TAB>definição" por linha (\n literal vira quebra de linha);
 * - CSV com ponto e vírgula (.csv): "palavra;definição";
 * - XML do Dicionário Aberto (<entry><orth>…</orth><def>…</def></entry>).
 */
class DictionaryImporter(private val context: Context) {
    private val db = DictionaryDb.get(context)

    /** Devolve mensagens legíveis sobre o que foi importado. */
    fun import(files: List<DictFile>, force: Boolean, onStatus: (String) -> Unit): List<String> {
        val report = ArrayList<String>()
        val byBase = files.groupBy { baseName(it.name) }
        for ((base, group) in byBase) {
            val key = group.sortedBy { it.name }.joinToString("|") { "${it.name}:${it.size}" }
            if (!force && db.hasSource(key)) continue
            onStatus("Importando dicionário $base…")
            val count = runCatching {
                val ifo = group.firstOrNull { it.name.endsWith(".ifo", true) }
                when {
                    ifo != null -> importStarDict(key, base, ifo, group)
                    group.size == 1 && group[0].name.endsWith(".xml", true) -> importXml(key, base, group[0])
                    group.size == 1 && isTabular(group[0].name) -> importTabular(key, base, group[0])
                    else -> -1
                }
            }.getOrElse { -2 }
            report += when (count) {
                -1 -> "$base: arquivos incompletos ou formato não suportado"
                -2 -> "$base: erro ao ler o arquivo"
                else -> "$base: $count verbetes"
            }
        }
        return report
    }

    // ---------------- StarDict ----------------

    private fun importStarDict(key: String, base: String, ifo: DictFile, group: List<DictFile>): Int {
        val info = read(ifo.uri).bufferedReader().readLines()
            .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
            .toMap()
        val idxFile = group.firstOrNull { it.name.endsWith(".idx", true) || it.name.endsWith(".idx.gz", true) } ?: return -1
        val dictFile = group.firstOrNull { it.name.endsWith(".dict", true) || it.name.endsWith(".dict.dz", true) } ?: return -1
        val offset64 = info["idxoffsetbits"] == "64"
        val sameType = info["sametypesequence"].orEmpty()
        val name = info["bookname"]?.takeIf { it.isNotBlank() } ?: base

        val idxBytes = (if (idxFile.name.endsWith(".gz", true)) GZIPInputStream(read(idxFile.uri)) else read(idxFile.uri))
            .use { it.readBytes() }
        // .dict.dz é gzip compatível: descompacta para um arquivo temporário com acesso aleatório
        val tmp = File(context.cacheDir, "dict_tmp.bin")
        (if (dictFile.name.endsWith(".dz", true)) GZIPInputStream(read(dictFile.uri)) else read(dictFile.uri))
            .use { input -> tmp.outputStream().use { input.copyTo(it) } }

        try {
            RandomAccessFile(tmp, "r").use { raf ->
                return db.importSource(key, name) { add ->
                    var p = 0
                    while (p < idxBytes.size) {
                        var end = p
                        while (end < idxBytes.size && idxBytes[end] != 0.toByte()) end++
                        val word = String(idxBytes, p, end - p, Charsets.UTF_8)
                        p = end + 1
                        val off: Long
                        if (offset64) { off = readLong(idxBytes, p); p += 8 } else { off = readUInt(idxBytes, p); p += 4 }
                        val size = readUInt(idxBytes, p).toInt(); p += 4
                        if (size <= 0 || off + size > raf.length()) continue
                        val data = ByteArray(size)
                        raf.seek(off)
                        raf.readFully(data)
                        add(word, starDictToHtml(data, sameType))
                    }
                }
            }
        } finally {
            tmp.delete()
        }
    }

    private fun readUInt(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or
            ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)

    private fun readLong(b: ByteArray, p: Int): Long = (readUInt(b, p) shl 32) or readUInt(b, p + 4)

    /** Converte os campos do StarDict em HTML simples para exibição. */
    private fun starDictToHtml(data: ByteArray, sameType: String): String {
        if (sameType.length == 1) return field(sameType[0], String(data, Charsets.UTF_8))
        val parts = ArrayList<String>()
        var p = 0
        var typeIndex = 0
        while (p < data.size) {
            val type: Char = if (sameType.isNotEmpty()) sameType.getOrNull(typeIndex++) ?: break else data[p++].toInt().toChar()
            val isLast = sameType.isNotEmpty() && typeIndex == sameType.length
            if (type.isUpperCase()) {
                val len = if (isLast) data.size - p else readUInt(data, p).toInt().also { p += 4 }
                p += len // dados binários (imagem/áudio): ignorados
            } else {
                var end = p
                if (isLast) end = data.size else while (end < data.size && data[end] != 0.toByte()) end++
                parts += field(type, String(data, p, end - p, Charsets.UTF_8))
                p = end + 1
            }
        }
        return parts.joinToString("<br>")
    }

    private fun field(type: Char, text: String): String = when (type) {
        'h', 'g', 'x' -> text
        't' -> "<i>/" + TextUtils.htmlEncode(text) + "/</i>"
        else -> TextUtils.htmlEncode(text).replace("\n", "<br>")
    }

    // ---------------- Texto tabulado / CSV ----------------

    private fun isTabular(name: String) = name.endsWith(".tsv", true) || name.endsWith(".txt", true) || name.endsWith(".csv", true)

    private fun importTabular(key: String, base: String, file: DictFile): Int {
        val sep = if (file.name.endsWith(".csv", true)) ';' else '\t'
        return read(file.uri).bufferedReader().use { reader ->
            db.importSource(key, base) { add ->
                reader.forEachLine { line ->
                    val i = line.indexOf(sep)
                    if (i > 0) {
                        val def = line.substring(i + 1).trim().removeSurrounding("\"").replace("\\n", "\n")
                        add(line.substring(0, i).removeSurrounding("\""), TextUtils.htmlEncode(def).replace("\n", "<br>"))
                    }
                }
            }
        }
    }

    // ---------------- XML (Dicionário Aberto) ----------------

    private fun importXml(key: String, base: String, file: DictFile): Int = read(file.uri).use { input ->
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        db.importSource(key, base) { add ->
            var word: String? = null
            val defs = StringBuilder()
            var grammar = ""
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name.substringAfter(':')) {
                        "entry" -> { word = null; defs.setLength(0); grammar = "" }
                        "orth" -> if (word == null) word = parser.innerText()
                        "gramGrp" -> grammar = parser.innerText()
                        "def" -> {
                            val d = parser.innerText()
                            if (d.isNotEmpty()) {
                                if (defs.isNotEmpty()) defs.append("<br><br>")
                                if (grammar.isNotEmpty()) defs.append("<i>").append(TextUtils.htmlEncode(grammar)).append("</i> ")
                                defs.append(TextUtils.htmlEncode(d).replace("\n", "<br>"))
                            }
                        }
                    }
                } else if (event == XmlPullParser.END_TAG && parser.name.substringAfter(':') == "entry") {
                    word?.let { add(it, defs.toString()) }
                }
                event = parser.next()
            }
        }
    }

    /** Texto de um elemento incluindo filhos (nextText() falha se houver tags dentro). */
    private fun XmlPullParser.innerText(): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.TEXT -> sb.append(text)
                XmlPullParser.END_DOCUMENT -> return sb.toString().trim()
            }
        }
        return sb.toString().trim()
    }

    private fun read(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: error("Não foi possível abrir $uri")

    companion object {
        private val EXTENSIONS = listOf(".ifo", ".idx", ".idx.gz", ".dict", ".dict.dz", ".syn", ".tsv", ".txt", ".csv", ".xml")

        fun isDictionaryFile(name: String) = EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

        fun baseName(name: String): String {
            val lower = name.lowercase()
            val ext = EXTENSIONS.filter { lower.endsWith(it) }.maxByOrNull { it.length } ?: return name
            return name.dropLast(ext.length)
        }
    }
}
