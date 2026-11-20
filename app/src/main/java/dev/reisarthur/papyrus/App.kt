package dev.reisarthur.papyrus

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dev.reisarthur.papyrus.data.Database
import dev.reisarthur.papyrus.data.Prefs
import dev.reisarthur.papyrus.ui.Fonts

/** Ponto de entrada do processo: inicializa os singletons usados pelo app inteiro. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        PDFBoxResourceLoader.init(this)
        Prefs.init(this)
        Database.init(this)
        Fonts.init(assets)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
