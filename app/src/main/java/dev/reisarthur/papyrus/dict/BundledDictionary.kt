package dev.reisarthur.papyrus.dict

import android.content.Context
import android.util.LruCache
import dev.reisarthur.papyrus.util.TextUtil
import java.io.DataInputStream
import java.util.zip.Inflater

/**
 * Dicionário Aberto lido direto de dentro do APK, sem copiar nada para o aparelho.
 *
 * O arquivo (assets/dict/dicionario-aberto.dic, gerado por scripts/build_dic.py) tem os verbetes
 * ordenados pela chave sem acento e agrupados em blocos de 256, cada um compactado à parte.
 * Um índice pequeno (1ª chave de cada bloco) fica na memória; uma consulta faz busca binária
 * nele e descompacta só um ou dois blocos (~70 KB). O asset fica sem compressão do APK
 * (noCompress "dic"), então é lido por posição, como um arquivo comum.
 *
 * Antes o dicionário era copiado para um banco SQLite com índices: ~24 MB a mais no aparelho.
 */
class BundledDictionary private constructor(context: Context) {

    /** Leitor de trechos do arquivo: posição -> bytes. */
    private val reader: (Long, Int) -> ByteArray
    private val zdict: ByteArray
    private val firstKeys: Array<String>
    private val offsets: IntArray
    private val sizes: IntArray
    /** Blocos já descompactados: as formas testadas pelo lematizador costumam cair nos mesmos. */
    private val blockCache = LruCache<Int, List<Pair<String, String>>>(8)

    init {
        reader = openReader(context)
        DataInputStream(context.assets.open(ASSET).buffered()).use { h ->
            check(h.readInt() == MAGIC) { "dicionário embutido inválido" }
            zdict = ByteArray(h.readInt()).also { h.readFully(it) }
            val n = h.readInt()
            val keys = arrayOfNulls<String>(n)
            offsets = IntArray(n)
            sizes = IntArray(n)
            for (i in 0 until n) {
                keys[i] = ByteArray(h.readUnsignedShort()).also { h.readFully(it) }.toString(Charsets.UTF_8)
                offsets[i] = h.readInt()
                sizes[i] = h.readInt()
            }
            @Suppress("UNCHECKED_CAST")
            firstKeys = keys as Array<String>
        }
    }

    /** Verbetes cuja chave sem acento é [fold] (no máximo [limit]). */
    @Synchronized
    fun find(fold: String, limit: Int = 20): List<DictEntry> {
        if (fold.isEmpty() || firstKeys.isEmpty()) return emptyList()
        // último bloco cuja 1ª chave é MENOR que a procurada: verbetes iguais podem começar no fim dele
        var lo = 0
        var hi = firstKeys.size - 1
        var start = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (firstKeys[mid] < fold) { start = mid; lo = mid + 1 } else hi = mid - 1
        }
        val out = ArrayList<DictEntry>()
        var b = start
        while (b < firstKeys.size && out.size < limit) {
            if (b > start && firstKeys[b] > fold) break
            for ((word, def) in block(b)) {
                if (TextUtil.foldKey(word) == fold) {
                    out += DictEntry(word, def, NAME)
                    if (out.size >= limit) break
                }
            }
            b++
        }
        return out
    }

    private fun block(i: Int): List<Pair<String, String>> = blockCache.get(i) ?: readBlock(i).also { blockCache.put(i, it) }

    private fun readBlock(i: Int): List<Pair<String, String>> {
        val packed = reader(offsets[i].toLong(), sizes[i])
        val inflater = Inflater()
        try {
            inflater.setInput(packed)
            val out = java.io.ByteArrayOutputStream(sizes[i] * 4)
            val buf = ByteArray(32 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    when {
                        inflater.needsDictionary() -> inflater.setDictionary(zdict)
                        inflater.needsInput() -> break
                    }
                }
                out.write(buf, 0, n)
            }
            return out.toString("UTF-8").lineSequence().mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) line.substring(0, tab) to line.substring(tab + 1) else null
            }.toList()
        } finally {
            inflater.end()
        }
    }

    /**
     * Lê trechos do asset por posição. O jeito rápido é o descritor do próprio APK com pread
     * (posição absoluta = início do asset no APK + posição no arquivo).
     *
     * Antes era usado o FileChannel do stream do asset: no Android 14+ esse canal já soma o início
     * do asset sozinho, então a posição saía somada duas vezes e toda consulta falhava.
     * Se o asset não puder ser aberto como descritor (APK comprimido), carrega tudo na memória.
     */
    private fun openReader(context: Context): (Long, Int) -> ByteArray {
        val afd = runCatching { context.assets.openFd(ASSET) }.getOrNull()
            ?: return context.assets.open(ASSET).use { it.readBytes() }.let { all -> { pos, len -> all.copyOfRange(pos.toInt(), pos.toInt() + len) } }
        val fd = afd.fileDescriptor
        val start = afd.startOffset
        return { pos, len ->
            // o lambda guarda o afd vivo: se ele fosse coletado, o descritor seria fechado
            check(afd.length < 0 || pos + len <= afd.length) { "trecho fora do dicionário" }
            val out = ByteArray(len)
            var done = 0
            while (done < len) {
                val n = android.system.Os.pread(fd, out, done, len - done, start + pos + done)
                if (n <= 0) error("fim inesperado do dicionário")
                done += n
            }
            out
        }
    }

    companion object {
        const val NAME = "Dicionário Aberto"
        private const val ASSET = "dict/dicionario-aberto.dic"
        private const val MAGIC = 0x50444131 // "PDA1"

        @Volatile private var instance: BundledDictionary? = null

        /** null se o arquivo não puder ser aberto (o app segue funcionando sem definições). */
        fun get(context: Context): BundledDictionary? = instance ?: synchronized(this) {
            instance ?: runCatching { BundledDictionary(context.applicationContext) }
                .onFailure { android.util.Log.e("papyrus", "Dicionário embutido indisponível", it) }
                .getOrNull()?.also { instance = it }
        }
    }
}
