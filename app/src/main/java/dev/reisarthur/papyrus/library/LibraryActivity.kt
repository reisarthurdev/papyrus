package dev.reisarthur.papyrus.library

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.reisarthur.papyrus.R
import dev.reisarthur.papyrus.data.Book
import dev.reisarthur.papyrus.data.BookFiles
import dev.reisarthur.papyrus.data.BookStatus
import dev.reisarthur.papyrus.data.Database
import dev.reisarthur.papyrus.data.Prefs
import dev.reisarthur.papyrus.reader.ReaderActivity
import dev.reisarthur.papyrus.ui.Fonts
import dev.reisarthur.papyrus.ui.MenuEntry
import dev.reisarthur.papyrus.ui.alert
import dev.reisarthur.papyrus.ui.showM3Menu
import dev.reisarthur.papyrus.ui.appColors
import dev.reisarthur.papyrus.ui.dp
import dev.reisarthur.papyrus.ui.dpf
import dev.reisarthur.papyrus.ui.flatButton
import dev.reisarthur.papyrus.ui.horizontal
import dev.reisarthur.papyrus.ui.isNightMode
import dev.reisarthur.papyrus.ui.oval
import dev.reisarthur.papyrus.ui.ripple
import dev.reisarthur.papyrus.ui.spacer
import dev.reisarthur.papyrus.ui.roundRect
import dev.reisarthur.papyrus.ui.text
import dev.reisarthur.papyrus.ui.vertical
import dev.reisarthur.papyrus.ui.weight
import dev.reisarthur.papyrus.util.Bg

/**
 * Biblioteca: grade de capas com o progresso de leitura, como a aba "Biblioteca" do Play Livros.
 * Os livros vêm da pasta escolhida pelo usuário; nada é baixado da internet.
 */
class LibraryActivity : Activity(), LibraryManager.Listener {

    private val colors by lazy { appColors }
    private val db get() = Database.instance
    private var books: List<Book> = emptyList()
    private lateinit var list: ListView
    private lateinit var dictFooter: TextView
    private lateinit var status: TextView
    private lateinit var statusBar: LinearLayout
    private lateinit var empty: LinearLayout
    /** Rolagem em volta da tela vazia: só existe para dar a "esticadinha" ao puxar, como na lista. */
    private lateinit var emptyScroll: ScrollView
    private val adapter = BookAdapter()
    private val covers = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildViews())
        loadDictionaryFooter()
        LibraryManager.addListener(this)
        // importação que já estava rodando (app reaberto no meio dela): mostra o progresso atual
        showStatus(LibraryManager.status)
        if (Prefs.libraryTreeUri != null) LibraryManager.scan(this)
    }

    override fun onResume() {
        super.onResume()
        showStatus(LibraryManager.status)
        reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        LibraryManager.removeListener(this)
    }

    override fun onLibraryChanged(status: String?, listChanged: Boolean) {
        showStatus(status)
        if (listChanged) reload()
    }

    private fun reload() {
        Bg.load({ db.books() }) { r ->
            val fresh = r.getOrDefault(emptyList())
            // a importação avisa o progresso várias vezes: só redesenha a lista se algo mudou de fato
            if (fresh != books) {
                books = fresh
                adapter.notifyDataSetChanged()
            }
            updateEmptyState()
        }
    }

    // ---------------- Interface ----------------

    private fun buildViews(): View {
        val root = vertical().apply { setBackgroundColor(colors.surface) }

        // barra superior grande do Material 3 (título à esquerda, ações à direita)
        val bar = horizontal().apply { setPadding(dp(4), dp(8), dp(4), 0) }
        bar.addView(spacer())
        bar.addView(icon(R.drawable.ic_refresh) { rescan() }.apply { contentDescription = "Atualizar" })
        // com uma única opção, o menu ⋮ virou um botão direto
        bar.addView(icon(R.drawable.ic_folder) { pickFolder() }.apply { contentDescription = "Escolher pasta de livros" })
        root.addView(bar)
        root.addView(text("Biblioteca", 30f, colors.onSurface).apply {
            setPadding(dp(20), dp(4), dp(20), dp(12))
        })

        statusBar = horizontal().apply {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.GONE
            background = roundRect(colors.secondaryContainer, dpf(16))
            addView(ProgressBar(context).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(colors.onSecondaryContainer)
            }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(12) })
        }
        status = text("", 13f, colors.onSecondaryContainer).apply { maxLines = 2 }
        statusBar.addView(status, weight())
        root.addView(statusBar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), 0, dp(16), dp(8)) })

        val frame = FrameLayout(this)
        list = ListView(this).apply {
            // "esticadinha" ao puxar além do fim (padrão do Android 12+), mesmo com poucos livros
            overScrollMode = View.OVER_SCROLL_ALWAYS
            divider = null
            dividerHeight = 0
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            setPadding(dp(12), dp(4), dp(12), dp(24))
            clipToPadding = false
            adapter = this@LibraryActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> open(books[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> bookOptions(books[pos]); true }
        }
        frame.addView(list)
        empty = buildEmptyState()
        emptyScroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_ALWAYS // estica mesmo sem nada para rolar
            isFillViewport = true                    // ocupa a tela toda: o conteúdo fica centralizado
            isVerticalScrollBarEnabled = false
            addView(FrameLayout(context).apply {
                addView(empty, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            }, FrameLayout.LayoutParams(-1, -1))
        }
        frame.addView(emptyScroll, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        dictFooter = text("", 12f, (colors.onSurfaceVariant and 0x00FFFFFF) or 0x99000000.toInt()).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(6), dp(16), dp(10))
            visibility = View.GONE
        }
        root.addView(dictFooter, LinearLayout.LayoutParams(-1, -2))

        // conteúdo desenha atrás das barras do sistema: compensa com padding
        root.setOnApplyWindowInsetsListener { v, insets ->
            val (top, bottom) = systemBarInsets(insets)
            v.setPadding(0, top, 0, bottom)
            list.setPadding(dp(12), dp(4), dp(12), dp(16))
            insets
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            // Android 8 a 10: desenha atrás da barra de status, como no 11+
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                (if (isNightMode()) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
        }
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(colors.surface))
        return root
    }

    private fun systemBarInsets(insets: WindowInsets): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) {
            insets.getInsets(WindowInsets.Type.systemBars()).let { it.top to it.bottom }
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetTop to insets.systemWindowInsetBottom
        }

    private fun buildEmptyState() = vertical(dp(32)).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_folder)
            setColorFilter(colors.onPrimaryContainer)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = oval(colors.primaryContainer)
        }, LinearLayout.LayoutParams(dp(88), dp(88)))
        addView(text("", 16f, colors.secondary).apply {
            gravity = Gravity.CENTER
            tag = "msg"
            setPadding(0, dp(16), 0, dp(16))
        })
        addView(flatButton("Escolher pasta de livros") { pickFolder() }.apply {
            setTextColor(colors.onPrimary)
            ripple(colors.stateLayer(colors.onPrimary), roundRect(colors.primary, dpf(24)))
            setPadding(dp(24), dp(12), dp(24), dp(12))
        })
    }

    private fun updateEmptyState() {
        val hasFolder = Prefs.libraryTreeUri != null
        emptyScroll.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
        empty.findViewWithTag<TextView>("msg").text = if (!hasFolder) {
            "Escolha a pasta onde ficam seus livros (PDF e EPUB).\nSubpastas também são lidas."
        } else if (LibraryManager.status != null) {
            "Procurando livros…"
        } else {
            "Nenhum PDF ou EPUB encontrado nessa pasta.\nCopie seus livros para lá e toque em atualizar."
        }
    }

    private fun showStatus(msg: String?) {
        statusBar.visibility = if (msg == null) View.GONE else View.VISIBLE
        status.text = msg.orEmpty()
    }

    private fun icon(res: Int, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(colors.onSurfaceVariant)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        ripple(colors.stateLayer(colors.onSurface), mask = oval(Color.WHITE))
        setOnClickListener { onClick() }
    }

    private fun rescan() {
        if (Prefs.libraryTreeUri == null) pickFolder() else LibraryManager.scan(this)
    }

    private fun open(book: Book) {
        when (book.status) {
            BookStatus.READY -> {
                startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id))
                if (Build.VERSION.SDK_INT < 34) {
                    @Suppress("DEPRECATION")
                    overridePendingTransition(R.anim.slide_in_right, R.anim.hold)
                }
            }
            BookStatus.IMPORTING -> Toast.makeText(this, "Ainda importando este livro…", Toast.LENGTH_SHORT).show()
            else -> bookOptions(book)
        }
    }

    private fun bookOptions(book: Book) {
        val items = arrayOf("Renomear", "Reimportar (refazer a formatação)", "Excluir o arquivo do aparelho")
        alert(isNightMode())
            .setTitle(book.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showRename(book)
                    1 -> LibraryManager.reimport(this, book.id)
                    2 -> confirmDelete(book)
                }
            }
            .show()
    }

    /** Menu do ⋮ de cada linha da lista. */
    private fun bookMenu(anchor: View, book: Book) {
        showM3Menu(anchor, isNightMode(), listOf(
            MenuEntry(R.drawable.ic_text, "Renomear") { showRename(book) },
            MenuEntry(R.drawable.ic_refresh, "Reimportar") { LibraryManager.reimport(this, book.id) },
            MenuEntry(R.drawable.ic_close, "Excluir") { confirmDelete(book) },
        ))
    }

    // ---------------- Renomear ----------------

    private fun showRename(book: Book) {
        val input = android.widget.EditText(this).apply {
            setText(book.title)
            setSelectAllOnFocus(true)
            setSingleLine()
            setTextColor(colors.onSurface)
            background = roundRect(colors.surfaceContainerHighest, dpf(12))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val box = vertical().apply { setPadding(dp(24), 0, dp(24), 0); addView(input, LinearLayout.LayoutParams(-1, -2)) }
        alert(isNightMode())
            .setTitle("Renomear livro")
            .setMessage("O arquivo na pasta também é renomeado.")
            .setView(box)
            .setPositiveButton("Salvar") { _, _ ->
                val title = input.text.toString().trim()
                if (title.isNotEmpty() && title != book.title) rename(book, title)
            }
            .setNegativeButton("Cancelar", null)
            .showKeyboardFor(input)
            .show()
    }

    private fun rename(book: Book, title: String) {
        Bg.load({
            val ext = book.fileName.substringAfterLast('.', "")
            val wanted = safeFileName(title) + if (ext.isNotEmpty()) ".$ext" else ""
            var uri = Uri.parse(book.uri)
            if (wanted != book.fileName) {
                uri = DocumentsContract.renameDocument(contentResolver, uri, wanted) ?: error("renomear falhou")
            }
            // o sistema pode ajustar o nome (ex.: "Livro (1).epub" se já existir): usa o nome real
            val finalName = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: wanted
            val size = book.key.substringAfterLast('|')
            db.renameBook(book.id, title, finalName, uri.toString(), "$finalName|$size")
        }) { r ->
            if (r.isFailure) Toast.makeText(this, "Não foi possível renomear o arquivo.", Toast.LENGTH_LONG).show()
            reload()
        }
    }

    /** Remove caracteres proibidos em nomes de arquivo. */
    private fun safeFileName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "-").trim().trimEnd('.').take(150).ifEmpty { "Livro" }

    private fun confirmDelete(book: Book) {
        alert(isNightMode())
            .setTitle("Excluir \"${book.title}\"?")
            .setMessage("O arquivo será apagado da pasta, junto com notas e marcadores deste livro.")
            .setPositiveButton("Excluir") { _, _ ->
                Bg.load({
                    DocumentsContract.deleteDocument(contentResolver, Uri.parse(book.uri))
                    db.deleteBook(book.id)
                    BookFiles.dir(filesDir, book.id).deleteRecursively()
                }) { r ->
                    if (r.isFailure) Toast.makeText(this, "Não foi possível apagar o arquivo.", Toast.LENGTH_LONG).show()
                    reload()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------- Pasta (SAF) ----------------

    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_FOLDER)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_FOLDER -> {
                val uri = data.data ?: return
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
                    .onFailure { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                Prefs.libraryTreeUri = uri.toString()
                updateEmptyState()
                LibraryManager.scan(this)
            }
        }
    }

    // ---------------- Rodapé do dicionário ----------------

    /** Nome do(s) dicionário(s) instalado(s), discreto no fim da tela inicial. */
    private fun loadDictionaryFooter() {
        Bg.load({ dev.reisarthur.papyrus.dict.Dictionary(this).sourceNames() }) { r ->
            val sources = r.getOrDefault(emptyList())
            dictFooter.text = sources.joinToString("  •  ")
            dictFooter.visibility = if (sources.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    // ---------------- Lista de livros ----------------

    private class Holder(
        val cover: ImageView,
        val title: TextView,
        val author: TextView,
        val progress: ProgressTrack,
        val percent: TextView,
        val more: ImageView,
    )

    /** Barra de progresso fina e arredondada no estilo do Material 3. */
    private class ProgressTrack(context: Context, private val track: Int, private val fill: Int) : View(context) {
        var value = 0f
            set(v) { field = v.coerceIn(0f, 1f); invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            paint.color = track
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
            if (value > 0f) {
                paint.color = fill
                canvas.drawRoundRect(0f, 0f, maxOf(height.toFloat(), width * value), height.toFloat(), r, r, paint)
            }
        }
    }

    private inner class BookAdapter : BaseAdapter() {
        override fun getCount() = books.size
        override fun getItem(position: Int) = books[position]
        override fun getItemId(position: Int) = books[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: createRow()
            val h = view.tag as Holder
            val book = books[position]
            h.title.text = book.title
            h.author.text = when (book.status) {
                BookStatus.IMPORTING -> "Importando…"
                BookStatus.FAILED -> "Erro ao importar"
                // "autor • tipo do arquivo" (só o tipo quando o livro não informa o autor)
                else -> listOf(book.author, book.format.name).filter { it.isNotBlank() }.joinToString(" • ")
            }
            val started = book.lastOpened > 0L && book.status == BookStatus.READY
            h.progress.visibility = if (started) View.VISIBLE else View.GONE
            h.percent.visibility = h.progress.visibility
            h.progress.value = book.progress
            h.percent.text = "${(book.progress * 100).toInt()}%"
            h.more.setOnClickListener { bookMenu(h.more, book) }
            // a capa muda quando a importação termina (sai a capa provisória, entra a do livro):
            // por isso a chave inclui o estado do livro
            val key = "${book.id}:${book.status}"
            val cached = covers.get(key)
            if (cached != null) {
                h.cover.setImageBitmap(cached)
            } else {
                // só limpa se esta linha estava mostrando outro livro; senão, a capa atual fica
                // na tela até a nova estar pronta (sem "piscar")
                if (h.cover.getTag(R.id.cover_book) != book.id) h.cover.setImageDrawable(null)
                Bg.load({ loadCover(book) }) { r ->
                    val bmp = r.getOrNull() ?: return@load
                    covers.put(key, bmp)
                    if (h.cover.tag == key) h.cover.setImageBitmap(bmp)
                }
            }
            h.cover.tag = key
            h.cover.setTag(R.id.cover_book, book.id)
            view.alpha = if (book.status == BookStatus.READY) 1f else 0.6f
            return view
        }

        private fun createRow(): View {
            val row = horizontal().apply {
                setPadding(dp(12), dp(12), dp(16), dp(12))
                ripple(colors.stateLayer(colors.onSurface), roundRect(colors.surfaceContainerLow, dpf(16)), roundRect(Color.WHITE, dpf(16)))
                // o clique é da ListView: o cartão só espelha o estado de "pressionado" do item
                isClickable = false
                isFocusable = false
                isDuplicateParentStateEnabled = true
            }
            val cover = ImageView(this@LibraryActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundRect(colors.surfaceContainerHighest, dpf(8))
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) =
                        outline.setRoundRect(0, 0, view.width, view.height, dpf(8))
                }
            }
            row.addView(cover, LinearLayout.LayoutParams(dp(56), dp(84)))
            val texts = vertical().apply { setPadding(dp(16), 0, 0, 0) }
            val title = text("", 16f, colors.onSurface).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
            val author = text("", 14f, colors.onSurfaceVariant).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            texts.addView(title)
            texts.addView(author, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            val progressRow = horizontal().apply { setPadding(0, dp(10), 0, 0) }
            val progress = ProgressTrack(this@LibraryActivity, colors.secondaryContainer, colors.primary)
            progressRow.addView(progress, LinearLayout.LayoutParams(0, dp(4), 1f))
            val percent = text("", 12f, colors.onSurfaceVariant).apply { setPadding(dp(10), 0, 0, 0) }
            progressRow.addView(percent)
            texts.addView(progressRow, LinearLayout.LayoutParams(-1, -2))
            row.addView(texts, weight())
            // ⋮ da linha: clicável, mas sem foco, para não bloquear o clique do item na ListView
            val more = ImageView(this@LibraryActivity).apply {
                setImageResource(R.drawable.ic_more)
                setColorFilter(colors.onSurfaceVariant)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = RippleDrawableCompat.circle(colors.stateLayer(colors.onSurface))
                isFocusable = false
            }
            row.addView(more, LinearLayout.LayoutParams(dp(44), dp(44)))
            // ListView não aceita margem direta nos itens: o espaço entre cartões vem de um contêiner
            val wrapper = FrameLayout(this@LibraryActivity).apply { setPadding(0, dp(4), 0, dp(4)) }
            wrapper.addView(row, FrameLayout.LayoutParams(-1, -2))
            wrapper.tag = Holder(cover, title, author, progress, percent, more)
            return wrapper
        }
    }

    private fun loadCover(book: Book): Bitmap {
        val file = BookFiles.cover(filesDir, book.id)
        // PDF pronto sem capa (ex.: perdida numa versão antiga): gera de novo a partir da 1ª página
        if (!file.exists() && book.status == BookStatus.READY && book.format == dev.reisarthur.papyrus.data.BookFormat.PDF) {
            runCatching {
                contentResolver.openFileDescriptor(android.net.Uri.parse(book.uri), "r")?.use {
                    dev.reisarthur.papyrus.pdf.PdfImporter.renderCover(it, file)
                }
            }
        }
        if (file.exists()) BitmapFactory.decodeFile(file.path)?.let { return it }
        return generatedCover(book)
    }

    /** Capa gerada quando o livro não tem uma: cor derivada do título + nome do livro. */
    private fun generatedCover(book: Book): Bitmap {
        val w = 300
        val h = 450
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val hue = (book.title.hashCode() and 0x7FFFFFFF) % 360
        c.drawColor(Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.45f, 0.55f)))
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f; typeface = Fonts.bold }
        val layout = StaticLayout.Builder.obtain(book.title, 0, book.title.length, tp, w - 48)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(6).build()
        c.save()
        c.translate(24f, (h - layout.height) / 2.5f)
        layout.draw(c)
        c.restore()
        return bmp
    }

    /** Ripple circular sem fundo (botões de ícone). */
    private object RippleDrawableCompat {
        fun circle(color: Int) = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(color), null, oval(Color.WHITE),
        )
    }

    private companion object {
        const val REQ_FOLDER = 10
    }
}
