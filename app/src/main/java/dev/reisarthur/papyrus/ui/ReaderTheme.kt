package dev.reisarthur.papyrus.ui

import android.graphics.Color

/**
 * Temas de leitura. O escuro usa fundo preto e texto levemente acinzentado (#D9D9D9),
 * evitando o contraste "estourado" de branco puro sobre preto.
 */
enum class ReaderTheme(
    val label: String,
    val background: Int,
    val text: Int,
    val secondaryText: Int,
    val isDark: Boolean,
) {
    LIGHT("Claro", Color.WHITE, 0xFF1F1F1F.toInt(), 0xFF6B6B6B.toInt(), false),
    SEPIA("Sépia", 0xFFF6EEDC.toInt(), 0xFF3B2F22.toInt(), 0xFF7A6A55.toInt(), false),
    DARK("Escuro", Color.BLACK, 0xFFD9D9D9.toInt(), 0xFF8A8A8A.toInt(), true);
}

/** Cores de destaque iguais às do Play Livros (com transparência para o texto aparecer por baixo). */
enum class HighlightColor(val label: String, val solid: Int) {
    YELLOW("Amarelo", 0xFFFDD663.toInt()),
    BLUE("Azul", 0xFF8AB4F8.toInt()),
    ORANGE("Laranja", 0xFFFCAD70.toInt()),
    GREEN("Verde", 0xFF81C995.toInt());

    /** Cor usada sobre a página: mais transparente no tema escuro para não ofuscar. */
    fun overlay(dark: Boolean): Int {
        val alpha = if (dark) 0x66 else 0x80
        return (alpha shl 24) or (solid and 0x00FFFFFF)
    }

    companion object {
        fun fromOrdinal(i: Int) = entries.getOrElse(i) { YELLOW }
    }
}
