package dev.reisarthur.papyrus.dict

import android.content.Context
import dev.reisarthur.papyrus.util.TextUtil

/** Resultado de uma consulta: [lemma] é preenchido quando a palavra foi achada por uma forma básica. */
data class LookupResult(val query: String, val lemma: String?, val entries: List<DictEntry>)

/**
 * Consulta ao dicionário com um "lematizador" simples de português:
 * se "correram" não existir, tenta "correr"; "casas" -> "casa"; "pães" -> "pão" etc.
 */
class Dictionary(context: Context) {
    private val bundled = BundledDictionary.get(context)
    private val db = DictionaryDb.get(context)

    val isEmpty get() = bundled == null && db.isEmpty()

    fun lookup(rawWord: String): LookupResult {
        val word = rawWord.trim().trim { !it.isLetterOrDigit() }
        val lower = word.lowercase(TextUtil.PT_BR)
        val fold = TextUtil.foldKey(word)
        val direct = find(lower, fold)
        if (direct.isNotEmpty()) return LookupResult(word, null, direct)
        // primeira forma básica (na ordem do lematizador) que existe em algum dicionário
        for (candidate in Lemmatizer.candidates(fold)) {
            val entries = byFold(candidate)
            if (entries.isNotEmpty()) return LookupResult(word, entries.first().headword, entries)
        }
        return LookupResult(word, null, emptyList())
    }

    /** Grafia exata (minúsculas) primeiro; se não houver, qualquer grafia com a mesma chave sem acento. */
    private fun find(lower: String, fold: String): List<DictEntry> {
        val all = byFold(fold)
        return all.filter { it.headword.lowercase(TextUtil.PT_BR) == lower }.ifEmpty { all }
    }

    private fun byFold(fold: String): List<DictEntry> =
        bundled?.find(fold).orEmpty() + db.findByFold(fold)

    /** Nomes dos dicionários disponíveis (para o rodapé da tela inicial). */
    fun sourceNames(): List<String> =
        listOfNotNull(bundled?.let { BundledDictionary.NAME }) + db.sources().map { it.first }
}

internal object Lemmatizer {
    // Regras aplicadas sobre a palavra SEM acento (o banco também indexa sem acento).
    private val NOMINAL = listOf(
        "oes" to "ao", "aes" to "ao", "aos" to "ao", "ais" to "al", "eis" to "el", "eis" to "il",
        "ois" to "ol", "uis" to "ul", "is" to "il", "ns" to "m", "res" to "r", "zes" to "z", "ses" to "s",
        "issimos" to "o", "issimas" to "o", "issimo" to "o", "issima" to "o",
        "zinhos" to "", "zinhas" to "", "zinho" to "", "zinha" to "",
        "inhos" to "o", "inhas" to "a", "inho" to "o", "inha" to "a",
        "amente" to "o", "mente" to "",
        // plural simples antes de gênero: "casas" -> "casa" (e não "caso")
        "s" to "", "as" to "o", "ona" to "ao", "esa" to "es", "a" to "o",
    )

    private val AR = listOf(
        "assemos", "aramos", "aremos", "ariamos", "avamos", "asseis", "areis", "arieis", "aveis", "assem",
        "arao", "ariam", "aria", "arias", "arei", "aras", "ara", "aram", "asse", "asses", "aste", "astes",
        "ava", "avas", "avam", "ando", "ados", "adas", "ado", "ada", "amos", "ais", "am", "armos", "arem",
        "ares", "ar", "ou", "ei", "emos", "em", "es", "e", "as", "a", "o",
    )
    private val ER = listOf(
        "essemos", "eramos", "eremos", "eriamos", "esseis", "ereis", "erieis", "essem", "erao", "eriam",
        "eria", "erias", "erei", "eras", "era", "eram", "esse", "esses", "este", "estes", "iamos", "ieis",
        "endo", "idos", "idas", "ido", "ida", "emos", "eis", "em", "ermos", "erem", "eres", "er", "eu",
        "ia", "ias", "iam", "es", "e", "i", "amos", "am", "as", "a", "o",
    )
    private val IR = listOf(
        "issemos", "iramos", "iremos", "iriamos", "isseis", "ireis", "irieis", "issem", "irao", "iriam",
        "iria", "irias", "irei", "iras", "ira", "iram", "isse", "isses", "iste", "istes", "iamos", "ieis",
        "indo", "idos", "idas", "ido", "ida", "imos", "irmos", "irem", "ires", "ir", "iu", "ia", "ias",
        "iam", "is", "em", "es", "e", "i", "amos", "am", "as", "a", "o",
    )

    fun candidates(fold: String): List<String> {
        val out = LinkedHashSet<String>()
        for ((suffix, repl) in NOMINAL) {
            if (fold.length > suffix.length && fold.endsWith(suffix)) out += fold.dropLast(suffix.length) + repl
        }
        // segunda passada para casos como "rapidamente" -> "rapida" -> "rapido"
        out.toList().forEach { c -> if (c.endsWith("a")) out += c.dropLast(1) + "o" }
        addVerb(fold, AR, "ar", out)
        addVerb(fold, ER, "er", out)
        addVerb(fold, IR, "ir", out)
        out.remove(fold)
        return out.filter { it.length >= 2 }.take(60)
    }

    private fun addVerb(fold: String, endings: List<String>, infinitive: String, out: MutableSet<String>) {
        for (e in endings) {
            if (fold.length > e.length + 1 && fold.endsWith(e)) out += fold.dropLast(e.length) + infinitive
        }
    }
}
