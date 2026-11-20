package dev.reisarthur.papyrus.reader

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import dev.reisarthur.papyrus.R
import dev.reisarthur.papyrus.data.Annotation
import dev.reisarthur.papyrus.data.Bookmark
import dev.reisarthur.papyrus.data.Locator
import dev.reisarthur.papyrus.data.TocEntry
import dev.reisarthur.papyrus.ui.AppColors
import dev.reisarthur.papyrus.ui.HighlightColor
import dev.reisarthur.papyrus.ui.dp
import dev.reisarthur.papyrus.ui.dpf
import dev.reisarthur.papyrus.ui.fullScreenDialog
import dev.reisarthur.papyrus.ui.horizontal
import dev.reisarthur.papyrus.ui.matchWrap
import dev.reisarthur.papyrus.ui.roundRect
import dev.reisarthur.papyrus.ui.text
import dev.reisarthur.papyrus.ui.vertical
import dev.reisarthur.papyrus.ui.weight

/**
 * Painel com três abas: Sumário, Marcadores e Notas (destaques), cada item com o número da página.
 */
class ContentsPanel(
    private val activity: Activity,
    private val dark: Boolean,
    private val content: BookContent,
    private val toc: List<TocEntry>,
    private val bookmarks: List<Bookmark>,
    private val annotations: List<Annotation>,
    private val currentPage: Int,
    private val actions: Actions,
) {
    interface Actions {
        fun goTo(at: Locator)
        fun deleteBookmark(b: Bookmark)
        fun editAnnotation(a: Annotation)
    }

    private val colors = AppColors(dark)
    private lateinit var dialog: Dialog
    private var tab = 0
    private val list = ListView(activity)
    private val empty = activity.text("", 15f, colors.secondary).apply {
        gravity = Gravity.CENTER
        setPadding(activity.dp(32), activity.dp(48), activity.dp(32), 0)
    }

    fun show(initialTab: Int = 0) {
        tab = initialTab
        val root = activity.vertical()
        root.fitsSystemWindows = true
        val bar = activity.horizontal().apply { setPadding(activity.dp(4), activity.dp(6), activity.dp(12), 0) }
        bar.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(colors.text)
            setPadding(activity.dp(12), activity.dp(12), activity.dp(12), activity.dp(12))
            setOnClickListener { dialog.dismiss() }
        })
        root.addView(bar)

        val tabs = activity.horizontal().apply { setPadding(activity.dp(12), 0, activity.dp(12), activity.dp(8)) }
        val labels = listOf("Sumário", "Marcadores (${bookmarks.size})", "Notas (${annotations.size})")
        val tabViews = labels.mapIndexed { i, label ->
            activity.text(label, 14f, colors.text, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(activity.dp(8), activity.dp(10), activity.dp(8), activity.dp(10))
                setOnClickListener { select(i, tabsRef) }
            }.also { tabs.addView(it, weight().apply { marginEnd = activity.dp(6) }) }
        }
        tabsRef = tabViews
        root.addView(tabs, matchWrap())
        root.addView(empty, matchWrap())
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        list.divider = null
        list.setOnItemClickListener { _, _, pos, _ -> onItemClick(pos) }
        list.setOnItemLongClickListener { _, _, pos, _ -> onItemLongClick(pos); true }

        dialog = activity.fullScreenDialog(dark, root)
        select(tab, tabViews)
        dialog.show()
    }

    private var tabsRef: List<TextView> = emptyList()

    private fun select(i: Int, tabViews: List<TextView>) {
        tab = i
        tabViews.forEachIndexed { k, v ->
            v.setTextColor(if (k == i) colors.onSecondaryContainer else colors.onSurfaceVariant)
            v.background = if (k == i) roundRect(colors.secondaryContainer, activity.dpf(20)) else null
        }
        list.adapter = when (i) {
            0 -> Adapter(toc.size) { pos, row -> bindToc(toc[pos], row) }
            1 -> Adapter(bookmarks.size) { pos, row -> bindBookmark(bookmarks[pos], row) }
            else -> Adapter(annotations.size) { pos, row -> bindAnnotation(annotations[pos], row) }
        }
        empty.text = when (i) {
            0 -> "Este livro não tem sumário."
            1 -> "Nenhum marcador ainda.\nToque no ícone de marcador no topo para marcar a página."
            else -> "Nenhuma nota ainda.\nToque e segure numa palavra para destacar ou anotar."
        }
        empty.visibility = if (list.adapter.count == 0) View.VISIBLE else View.GONE
        if (i == 0) {
            // rola até o capítulo atual
            val current = toc.indexOfLast { content.pageOf(it.at) <= currentPage }
            if (current > 2) list.setSelection(current - 2)
        }
    }

    private fun onItemClick(pos: Int) {
        val at = when (tab) {
            0 -> toc[pos].at
            1 -> bookmarks[pos].at
            else -> annotations[pos].start
        }
        dialog.dismiss()
        actions.goTo(at)
    }

    private fun onItemLongClick(pos: Int) {
        when (tab) {
            1 -> { dialog.dismiss(); actions.deleteBookmark(bookmarks[pos]) }
            2 -> { dialog.dismiss(); actions.editAnnotation(annotations[pos]) }
        }
    }

    // ---------------- Linhas ----------------

    private class Row(val root: LinearLayout, val stripe: View, val title: TextView, val sub: TextView, val page: TextView)

    private fun newRow(): Row {
        val root = activity.horizontal().apply {
            setPadding(activity.dp(20), activity.dp(14), activity.dp(20), activity.dp(14))
            gravity = Gravity.CENTER_VERTICAL
        }
        val stripe = View(activity)
        root.addView(stripe, LinearLayout.LayoutParams(activity.dp(4), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = activity.dp(12) })
        val texts = activity.vertical()
        val title = activity.text("", 15f, colors.text).apply { maxLines = 3 }
        val sub = activity.text("", 13f, colors.secondary).apply { maxLines = 3 }
        texts.addView(title); texts.addView(sub)
        root.addView(texts, weight())
        val page = activity.text("", 13f, colors.secondary).apply { setPadding(activity.dp(16), 0, 0, 0) }
        root.addView(page)
        return Row(root, stripe, title, sub, page)
    }

    private fun bindToc(e: TocEntry, r: Row) {
        r.stripe.visibility = View.GONE
        r.root.setPadding(activity.dp(20 + 18 * e.level.coerceAtMost(4)), activity.dp(14), activity.dp(20), activity.dp(14))
        val page = content.pageOf(e.at)
        val isCurrent = page <= currentPage && toc.none { it !== e && content.pageOf(it.at) in (page + 1)..currentPage }
        r.title.text = e.title
        r.title.setTextColor(if (isCurrent) colors.accent else colors.text)
        r.sub.visibility = View.GONE
        r.page.text = "${page + 1}"
    }

    private fun bindBookmark(b: Bookmark, r: Row) {
        r.stripe.visibility = View.VISIBLE
        r.stripe.background = roundRect(colors.accent, activity.dpf(2))
        val page = content.pageOf(b.at)
        r.title.text = content.sectionTitle(b.at.section)
        r.sub.visibility = View.VISIBLE
        r.sub.text = content.sectionText(b.at.section).drop(b.at.offset).take(140).replace('\n', ' ').trim()
        r.page.text = "${page + 1}"
    }

    private fun bindAnnotation(a: Annotation, r: Row) {
        r.stripe.visibility = View.VISIBLE
        r.stripe.background = roundRect(HighlightColor.fromOrdinal(a.color).solid, activity.dpf(2))
        r.title.text = "“${a.quote}”"
        r.sub.visibility = if (a.note.isBlank()) View.GONE else View.VISIBLE
        r.sub.text = a.note
        r.page.text = "${content.pageOf(a.start) + 1}"
    }

    private inner class Adapter(private val n: Int, private val bind: (Int, Row) -> Unit) : BaseAdapter() {
        override fun getCount() = n
        override fun getItem(position: Int) = position
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView?.tag as? Row) ?: newRow().also { it.root.tag = it }
            bind(position, row)
            return row.root
        }
    }
}
