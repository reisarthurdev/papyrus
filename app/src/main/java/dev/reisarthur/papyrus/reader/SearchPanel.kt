package dev.reisarthur.papyrus.reader

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import dev.reisarthur.papyrus.R
import dev.reisarthur.papyrus.data.Locator
import dev.reisarthur.papyrus.ui.AppColors
import dev.reisarthur.papyrus.ui.dp
import dev.reisarthur.papyrus.ui.fullScreenDialog
import dev.reisarthur.papyrus.ui.horizontal
import dev.reisarthur.papyrus.ui.matchWrap
import dev.reisarthur.papyrus.ui.text
import dev.reisarthur.papyrus.ui.vertical
import dev.reisarthur.papyrus.ui.weight
import dev.reisarthur.papyrus.util.Bg
import dev.reisarthur.papyrus.util.TextUtil
import java.util.concurrent.Future

/** Resultado de busca: onde está, em que página e o trecho ao redor. */
data class SearchHit(val start: Locator, val end: Locator, val page: Int, val chapter: String, val snippet: CharSequence)

/**
 * Busca no livro inteiro estilo Play Livros: ignora maiúsculas e acentos e lista cada
 * ocorrência com o trecho e o número da página.
 */
class SearchPanel(
    private val activity: Activity,
    private val content: BookContent,
    private val dark: Boolean,
    private val onPick: (SearchHit) -> Unit,
) {
    private val colors = AppColors(dark)
    private val hits = ArrayList<SearchHit>()
    private var task: Future<*>? = null
    private var generation = 0
    private val status = activity.text("", 13f, colors.secondary)
    private val progress = ProgressBar(activity).apply { visibility = View.GONE; isIndeterminate = true }
    private val adapter = HitAdapter()
    private lateinit var dialog: Dialog

    fun show(initialQuery: String = "") {
        val root = activity.vertical()
        root.fitsSystemWindows = true

        val bar = activity.horizontal().apply { setPadding(activity.dp(4), activity.dp(6), activity.dp(12), activity.dp(6)) }
        val back = ImageView(activity).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(colors.text)
            setPadding(activity.dp(12), activity.dp(12), activity.dp(12), activity.dp(12))
            setOnClickListener { dialog.dismiss() }
        }
        bar.addView(back)
        val input = EditText(activity).apply {
            hint = "Pesquisar neste livro"
            setText(initialQuery)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(colors.text)
            setHintTextColor(colors.secondary)
            background = null
            textSize = 18f
            setOnEditorActionListener { v, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    search(v.text.toString()); hideKeyboard(v); true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                private val debounce = Runnable { search(text.toString()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    Bg.cancel(debounce)
                    Bg.mainDelayed(400, debounce)
                }
            })
        }
        bar.addView(input, weight())
        bar.addView(progress, LinearLayout.LayoutParams(activity.dp(24), activity.dp(24)))
        root.addView(bar, matchWrap())
        root.addView(status.apply { setPadding(activity.dp(20), 0, activity.dp(20), activity.dp(8)) })

        val list = ListView(activity).apply {
            adapter = this@SearchPanel.adapter
            divider = android.graphics.drawable.ColorDrawable(colors.divider)
            dividerHeight = 1
            setOnItemClickListener { _, _, pos, _ ->
                dialog.dismiss()
                onPick(hits[pos])
            }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        dialog = activity.fullScreenDialog(dark, root)
        dialog.setOnDismissListener { task?.cancel(true) }
        dialog.show()
        input.requestFocus()
        if (initialQuery.isNotBlank()) search(initialQuery)
    }

    private fun hideKeyboard(v: View) {
        (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun search(rawQuery: String) {
        val query = rawQuery.trim()
        task?.cancel(true)
        val gen = ++generation
        hits.clear()
        adapter.notifyDataSetChanged()
        if (query.length < 2) {
            status.text = if (query.isEmpty()) "" else "Digite pelo menos 2 letras"
            progress.visibility = View.GONE
            return
        }
        if (!content.hasText) {
            status.text = "Este PDF não tem texto pesquisável (provavelmente é escaneado)."
            return
        }
        progress.visibility = View.VISIBLE
        status.text = "Pesquisando…"
        val needle = TextUtil.foldSameLength(query)
        task = Bg.io {
            val found = ArrayList<SearchHit>()
            for (section in 0 until content.sectionCount) {
                if (Thread.currentThread().isInterrupted || gen != generation) return@io
                val text = content.sectionText(section)
                if (text.isEmpty()) continue
                val hay = TextUtil.foldSameLength(text)
                var idx = hay.indexOf(needle)
                while (idx >= 0 && found.size < MAX_HITS) {
                    val start = Locator(section, idx)
                    found += SearchHit(
                        start, Locator(section, idx + needle.length), content.pageOf(start),
                        content.sectionTitle(section), snippet(text, idx, needle.length),
                    )
                    idx = hay.indexOf(needle, idx + needle.length)
                }
                if (found.size >= MAX_HITS) break
            }
            Bg.main {
                if (gen != generation) return@main
                hits.clear(); hits.addAll(found)
                adapter.notifyDataSetChanged()
                progress.visibility = View.GONE
                status.text = when {
                    found.isEmpty() -> "Nenhum resultado para \"$query\""
                    found.size >= MAX_HITS -> "Mais de $MAX_HITS resultados (mostrando os primeiros)"
                    found.size == 1 -> "1 resultado"
                    else -> "${found.size} resultados"
                }
            }
        }
    }

    private fun snippet(text: String, idx: Int, len: Int): CharSequence {
        val from = maxOf(0, idx - 60).let { s -> text.indexOf(' ', s).takeIf { it in s until idx } ?: s }
        val to = minOf(text.length, idx + len + 80)
        val sb = SpannableStringBuilder()
        if (from > 0) sb.append("…")
        val offset = sb.length - from
        sb.append(text.substring(from, to).replace('\n', ' '))
        if (to < text.length) sb.append("…")
        sb.setSpan(StyleSpan(Typeface.BOLD), idx + offset, idx + len + offset, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(BackgroundColorSpan(0x55FDD663), idx + offset, idx + len + offset, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private inner class HitAdapter : BaseAdapter() {
        override fun getCount() = hits.size
        override fun getItem(position: Int) = hits[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: activity.horizontal().apply {
                setPadding(activity.dp(20), activity.dp(14), activity.dp(20), activity.dp(14))
                gravity = Gravity.TOP
                val texts = activity.vertical()
                texts.addView(activity.text("", 12f, colors.secondary).apply { maxLines = 1; tag = "chapter" })
                texts.addView(activity.text("", 15f, colors.text).apply { maxLines = 3; tag = "snippet" })
                addView(texts, weight())
                addView(activity.text("", 13f, colors.secondary).apply {
                    tag = "page"; setPadding(activity.dp(16), 0, 0, 0)
                })
            }
            val hit = hits[position]
            row.findViewWithTag<TextView>("chapter").text = hit.chapter
            row.findViewWithTag<TextView>("snippet").text = hit.snippet
            row.findViewWithTag<TextView>("page").text = "pág. ${hit.page + 1}"
            return row
        }
    }

    private companion object {
        const val MAX_HITS = 1000
    }
}
