package dev.reisarthur.papyrus.data

import android.content.Context
import android.content.SharedPreferences
import dev.reisarthur.papyrus.ui.ReaderTheme

/** Preferências do usuário, com valores padrão razoáveis. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("papyrus", Context.MODE_PRIVATE)
    }

    /** Pasta escolhida pelo usuário (URI da árvore do SAF). */
    var libraryTreeUri: String?
        get() = sp.getString("tree", null)
        set(v) = sp.edit().putString("tree", v).apply()

    var theme: ReaderTheme
        get() = ReaderTheme.entries.getOrElse(sp.getInt("theme", ReaderTheme.DARK.ordinal)) { ReaderTheme.DARK }
        set(v) = sp.edit().putInt("theme", v.ordinal).apply()

    /** Tamanho da fonte do EPUB em sp. */
    var fontSize: Float
        get() = sp.getFloat("fontSize", 19f)
        set(v) = sp.edit().putFloat("fontSize", v.coerceIn(MIN_FONT, MAX_FONT)).apply()

    var lineSpacing: Float
        get() = sp.getFloat("lineSpacing", 1.45f)
        set(v) = sp.edit().putFloat("lineSpacing", v).apply()

    /** Margem lateral em dp. */
    var margin: Int
        get() = sp.getInt("margin", 24)
        set(v) = sp.edit().putInt("margin", v).apply()

    /** Inverte as cores do PDF quando o tema escuro está ativo. */
    var pdfDarkInvert: Boolean
        get() = sp.getBoolean("pdfInvert", true)
        set(v) = sp.edit().putBoolean("pdfInvert", v).apply()

    /** Zoom consolidado do PDF: 1.0, 1.25, 1.5, 1.75 ou 2.0. */
    var pdfZoom: Float
        get() = sp.getFloat("pdfZoom", 1f).coerceIn(1f, 3f) // 300% é o máximo (versões antigas iam a 400%)
        set(v) = sp.edit().putFloat("pdfZoom", v).apply()

    var lastHighlightColor: Int
        get() = sp.getInt("hlColor", 0)
        set(v) = sp.edit().putInt("hlColor", v).apply()

    const val MIN_FONT = 12f
    const val MAX_FONT = 40f
}
