package dev.reisarthur.papyrus.util

import java.text.Normalizer
import java.util.Locale

object TextUtil {
    val PT_BR: Locale = Locale.forLanguageTag("pt-BR")

    /**
     * Remove acentos e passa para minúsculas SEM mudar o tamanho da string,
     * assim um índice encontrado no texto "dobrado" vale também no texto original.
     */
    fun foldSameLength(s: CharSequence): String {
        val out = CharArray(s.length)
        for (i in s.indices) out[i] = foldChar(s[i])
        return String(out)
    }

    private val foldCache = HashMap<Char, Char>()

    fun foldChar(c: Char): Char {
        if (c.code < 128) return c.lowercaseChar()
        return foldCache.getOrPut(c) {
            val decomposed = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
            decomposed.firstOrNull()?.lowercaseChar() ?: c
        }
    }

    /** Versão para chaves de dicionário (pode mudar o tamanho). */
    fun foldKey(s: String): String =
        Normalizer.normalize(s.lowercase(PT_BR), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .trim()

    /** Encontra a palavra que contém [index] (ou a mais próxima). Retorna o intervalo [start, end). */
    fun wordAt(text: CharSequence, index: Int): IntRange? {
        if (text.isEmpty()) return null
        var i = index.coerceIn(0, text.length - 1)
        if (!isWordChar(text[i])) {
            // procura um caractere de palavra logo ao lado
            if (i > 0 && isWordChar(text[i - 1])) i-- else return null
        }
        var start = i
        while (start > 0 && isWordChar(text[start - 1])) start--
        var end = i + 1
        while (end < text.length && isWordChar(text[end])) end++
        // não deixa hífen/apóstrofo nas pontas (acentos soltos no fim continuam: são da última letra)
        while (end > start && !text[end - 1].isLetterOrDigit() && !isMark(text[end - 1])) end--
        while (start < end && !text[start].isLetterOrDigit()) start++
        return if (start < end) start until end else null
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || isMark(c) || c == '-' || c == '\'' || c == '’'

    /** Acento "solto" (ê gravado como e + ^): faz parte da letra anterior. */
    fun isMark(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }
}
