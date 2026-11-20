package dev.reisarthur.papyrus.ui

import android.content.res.AssetManager
import android.graphics.Typeface

/** Família Literata (licença OFL, em assets/fonts) usada em todos os EPUBs. */
object Fonts {
    lateinit var regular: Typeface; private set
    lateinit var bold: Typeface; private set
    lateinit var italic: Typeface; private set
    lateinit var boldItalic: Typeface; private set

    fun init(assets: AssetManager) {
        regular = Typeface.createFromAsset(assets, "fonts/Literata-Regular.ttf")
        bold = Typeface.createFromAsset(assets, "fonts/Literata-Bold.ttf")
        italic = Typeface.createFromAsset(assets, "fonts/Literata-Italic.ttf")
        boldItalic = Typeface.createFromAsset(assets, "fonts/Literata-BoldItalic.ttf")
    }

    fun of(bold: Boolean, italic: Boolean): Typeface = when {
        bold && italic -> boldItalic
        bold -> this.bold
        italic -> this.italic
        else -> regular
    }
}
