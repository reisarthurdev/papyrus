package dev.reisarthur.papyrus.reader

import android.app.Activity
import android.graphics.Color
import android.text.Html
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.reisarthur.papyrus.data.Prefs
import dev.reisarthur.papyrus.dict.Dictionary
import dev.reisarthur.papyrus.dict.LookupResult
import dev.reisarthur.papyrus.ui.AppColors
import dev.reisarthur.papyrus.ui.HighlightColor
import dev.reisarthur.papyrus.ui.ReaderTheme
import dev.reisarthur.papyrus.ui.alert
import dev.reisarthur.papyrus.ui.BottomSheet
import dev.reisarthur.papyrus.ui.bottomSheet
import dev.reisarthur.papyrus.ui.dp
import dev.reisarthur.papyrus.ui.flatButton
import dev.reisarthur.papyrus.ui.horizontal
import dev.reisarthur.papyrus.ui.segmented
import dev.reisarthur.papyrus.ui.tintM3
import dev.reisarthur.papyrus.ui.matchWrap
import dev.reisarthur.papyrus.ui.oval
import dev.reisarthur.papyrus.ui.ripple
import dev.reisarthur.papyrus.ui.roundRect
import dev.reisarthur.papyrus.ui.dpf
import dev.reisarthur.papyrus.ui.text
import dev.reisarthur.papyrus.ui.vertical
import dev.reisarthur.papyrus.ui.weight
import dev.reisarthur.papyrus.util.Bg

/** Diálogos simples do leitor, agrupados para não espalhar código de UI pela Activity. */
object ReaderDialogs {

    // ---------------- Dicionário ----------------

    fun showDefinition(activity: Activity, dark: Boolean, word: String) {
        val colors = AppColors(dark)
        val body = activity.text("Procurando…", 16f, colors.secondary)
        val scroll = ScrollView(activity).apply {
            setPadding(activity.dp(24), activity.dp(8), activity.dp(24), 0)
            addView(body)
        }
        val dialog = activity.alert(dark)
            .setTitle(word)
            .setView(scroll)
            .setPositiveButton("Fechar", null)
            .show()
        Bg.load({ Dictionary(activity).let { if (it.isEmpty) null else it.lookup(word) } }) { result ->
            if (!dialog.isShowing) return@load
            val r = result.getOrNull()
            body.setTextColor(colors.text)
            body.text = when {
                result.isFailure -> "Erro ao consultar o dicionário."
                r == null -> NO_DICTIONARY
                r.entries.isEmpty() -> "Nenhum significado encontrado para \"$word\"."
                else -> formatEntries(r)
            }
        }
    }

    private fun formatEntries(r: LookupResult): CharSequence {
        val html = StringBuilder()
        if (r.lemma != null) html.append("<i>Forma de <b>").append(Html.escapeHtml(r.lemma)).append("</b></i><br><br>")
        r.entries.forEachIndexed { i, e ->
            if (i > 0) html.append("<br><br>")
            if (r.entries.size > 1 || r.lemma == null) html.append("<b>").append(Html.escapeHtml(e.headword)).append("</b><br>")
            html.append(e.definitionHtml)
        }
        val sources = r.entries.map { it.source }.distinct().joinToString(", ")
        html.append("<br><br><small>Fonte: ").append(Html.escapeHtml(sources)).append("</small>")
        return Html.fromHtml(html.toString(), Html.FROM_HTML_MODE_COMPACT)
    }

    private const val NO_DICTIONARY =
        "Nenhum dicionário instalado.\n\nColoque os arquivos de um dicionário (StarDict .ifo/.idx/.dict.dz, " +
            ".tsv ou XML do Dicionário Aberto) numa subpasta chamada \"Dicionário\" dentro da sua pasta de livros " +
            "e toque em atualizar na biblioteca."

    // ---------------- Nota com cor ----------------

    /**
     * Editor de nota: mostra o trecho, as 4 cores e o campo de texto.
     * [onDelete] só aparece ao editar uma nota existente.
     */
    fun showNoteEditor(
        activity: Activity,
        dark: Boolean,
        quote: String,
        initialNote: String,
        initialColor: Int,
        onSave: (note: String, color: Int) -> Unit,
        onDelete: (() -> Unit)? = null,
    ) {
        val colors = AppColors(dark)
        var color = initialColor
        val root = activity.vertical().apply { setPadding(activity.dp(24), 0, activity.dp(24), 0) }
        root.addView(activity.text("“${quote.take(300)}”", 15f, colors.secondary).apply {
            setPadding(0, 0, 0, activity.dp(12))
        })
        val swatches = activity.horizontal()
        val swatchViews = ArrayList<View>()
        fun refresh() = swatchViews.forEachIndexed { i, v ->
            v.background = oval(HighlightColor.fromOrdinal(i).solid, if (i == color) colors.text else Color.TRANSPARENT, activity.dp(3))
        }
        HighlightColor.entries.forEach { hc ->
            val v = View(activity)
            v.setOnClickListener { color = hc.ordinal; refresh() }
            swatches.addView(v, LinearLayout.LayoutParams(activity.dp(36), activity.dp(36)).apply { marginEnd = activity.dp(14) })
            swatchViews += v
        }
        refresh()
        root.addView(swatches)
        val input = EditText(activity).apply {
            setText(initialNote)
            hint = "Escreva sua nota"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            gravity = Gravity.TOP
            setTextColor(colors.onSurface)
            setHintTextColor(colors.onSurfaceVariant)
            background = roundRect(colors.surfaceContainerHighest, activity.dpf(12))
            setPadding(activity.dp(16), activity.dp(14), activity.dp(16), activity.dp(14))
        }
        root.addView(input, matchWrap().apply { topMargin = activity.dp(18) })

        val builder = activity.alert(dark)
            .setTitle(if (onDelete != null) "Editar nota" else "Nova nota")
            .setView(root)
            .setPositiveButton("Salvar") { _, _ -> onSave(input.text.toString().trim(), color) }
            .setNegativeButton("Cancelar", null)
        if (onDelete != null) builder.setNeutralButton("Excluir") { _, _ -> onDelete() }
        builder.showKeyboardFor(input).show()
    }

    // ---------------- Texto e exibição ----------------

    interface SettingsListener {
        fun onThemeChanged()
        fun onLayoutChanged()
        fun onClosed() {}
    }

    private val ZOOMS = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

    /** Painel inferior com tema, tamanho do texto/zoom e ajustes de layout. */
    fun showSettings(activity: Activity, isPdf: Boolean, pdfHasText: Boolean, listener: SettingsListener) {
        val sheet = activity.bottomSheet(Prefs.theme.isDark, View(activity))
        sheet.setContent(buildSettings(activity, isPdf, pdfHasText, listener, sheet))
        sheet.setOnDismissListener { listener.onClosed() }
        sheet.show()
    }

    /** Conteúdo do painel; reconstruído no mesmo painel quando o tema muda (sem fechar e reabrir). */
    private fun buildSettings(activity: Activity, isPdf: Boolean, pdfHasText: Boolean, listener: SettingsListener, sheet: BottomSheet): View {
        val dark = Prefs.theme.isDark
        val colors = AppColors(dark)
        val root = activity.vertical().apply { setPadding(activity.dp(24), 0, activity.dp(24), activity.dp(28)) }

        fun section(title: String) = root.addView(activity.text(title, 14f, colors.onSurfaceVariant).apply {
            setPadding(0, activity.dp(18), 0, activity.dp(10))
        })

        root.addView(activity.text("Texto e exibição", 22f, colors.onSurface))

        section("Tema")
        val themes = activity.horizontal()
        ReaderTheme.entries.forEach { t ->
            val selected = t == Prefs.theme
            val chip = activity.text(t.label, 15f, t.text).apply {
                gravity = Gravity.CENTER
                setPadding(0, activity.dp(16), 0, activity.dp(16))
                background = roundRect(t.background, activity.dpf(16), if (selected) colors.primary else colors.outlineVariant, activity.dp(if (selected) 3 else 1))
                setOnClickListener {
                    if (t == Prefs.theme) return@setOnClickListener
                    Prefs.theme = t
                    listener.onThemeChanged()
                    // mesmo painel, novas cores, com o mesmo fade do livro: nada pula
                    sheet.crossfade {
                        sheet.recolor(t.isDark)
                        sheet.setContent(buildSettings(activity, isPdf, pdfHasText, listener, sheet))
                    }
                }
            }
            themes.addView(chip, weight().apply { marginEnd = activity.dp(8) })
        }
        root.addView(themes)

        if (isPdf) {
            section("Zoom")
            if (pdfHasText) {
                // − 150% +  (mesmo formato do tamanho do texto do EPUB)
                var index = ZOOMS.indexOfFirst { kotlin.math.abs(it - Prefs.pdfZoom) < 0.01f }.coerceAtLeast(0)
                val label = activity.text("", 16f, colors.onSurface).apply { gravity = Gravity.CENTER }
                fun update() { label.text = "${(ZOOMS[index] * 100).toInt()}%" }
                fun step(delta: Int) {
                    val next = (index + delta).coerceIn(0, ZOOMS.lastIndex)
                    if (next == index) return
                    index = next
                    update()
                    Prefs.pdfZoom = ZOOMS[index]
                    listener.onLayoutChanged()
                }
                update()
                val row = activity.horizontal()
                row.addView(stepButton(activity, colors, "−", 18f) { step(-1) }, weight())
                row.addView(label, weight())
                row.addView(stepButton(activity, colors, "+", 18f) { step(+1) }, weight())
                root.addView(row)
            } else {
                root.addView(activity.text("Este PDF é escaneado (sem texto), então só dá para ampliar com pinça.", 13f, colors.onSurfaceVariant))
            }
            section("Cores")
            root.addView(toggleRow(activity, colors, "Inverter cores no tema escuro", Prefs.pdfDarkInvert) {
                Prefs.pdfDarkInvert = it
                listener.onLayoutChanged()
            })
        } else {
            section("Tamanho do texto")
            val sizeLabel = activity.text("", 16f, colors.onSurface).apply { gravity = Gravity.CENTER }
            fun updateSize() { sizeLabel.text = "${Prefs.fontSize.toInt()}" }
            updateSize()
            val sizeRow = activity.horizontal()
            sizeRow.addView(stepButton(activity, colors, "A−", 14f) {
                Prefs.fontSize = Prefs.fontSize - 1
                updateSize(); listener.onLayoutChanged()
            }, weight())
            sizeRow.addView(sizeLabel, weight())
            sizeRow.addView(stepButton(activity, colors, "A+", 20f) {
                Prefs.fontSize = Prefs.fontSize + 1
                updateSize(); listener.onLayoutChanged()
            }, weight())
            root.addView(sizeRow)

            section("Espaçamento entre linhas")
            val spacings = listOf(1.25f, 1.45f, 1.7f)
            root.addView(activity.segmented(dark, listOf("Compacto", "Normal", "Amplo"), nearest(spacings, Prefs.lineSpacing)) {
                Prefs.lineSpacing = spacings[it]; listener.onLayoutChanged()
            })

            section("Margens")
            val margins = listOf(14f, 24f, 40f)
            root.addView(activity.segmented(dark, listOf("Estreitas", "Normais", "Largas"), nearest(margins, Prefs.margin.toFloat())) {
                Prefs.margin = margins[it].toInt(); listener.onLayoutChanged()
            })
        }
        return root
    }

    private fun nearest(options: List<Float>, value: Float) =
        options.indices.minByOrNull { kotlin.math.abs(options[it] - value) } ?: 0

    private fun stepButton(activity: Activity, colors: AppColors, label: String, size: Float, onClick: () -> Unit) =
        activity.text(label, size, colors.onSecondaryContainer, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, activity.dp(12), 0, activity.dp(12))
            ripple(colors.stateLayer(colors.onSecondaryContainer), roundRect(colors.secondaryContainer, activity.dpf(24)))
            setOnClickListener { onClick() }
        }

    private fun toggleRow(activity: Activity, colors: AppColors, label: String, initial: Boolean, onChange: (Boolean) -> Unit): View {
        val row = activity.horizontal()
        row.addView(activity.text(label, 16f, colors.onSurface), weight())
        val sw = android.widget.Switch(activity).apply {
            isChecked = initial
            tintM3(colors)
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        row.addView(sw)
        return row
    }

    /** Botão circular de cor usado no menu de seleção. */
    fun colorDot(activity: Activity, color: HighlightColor, ring: Int, onClick: () -> Unit): View = View(activity).apply {
        background = oval(color.solid, ring, activity.dp(2))
        contentDescription = color.label
        setOnClickListener { onClick() }
    }

    fun menuButton(activity: Activity, label: String, color: Int, onClick: () -> Unit): TextView =
        activity.flatButton(label, color, onClick).apply { textSize = 14f }
}
