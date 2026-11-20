package dev.reisarthur.papyrus.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.reisarthur.papyrus.data.BookFiles
import dev.reisarthur.papyrus.data.BookFormat
import dev.reisarthur.papyrus.data.BookStatus
import dev.reisarthur.papyrus.data.Database
import dev.reisarthur.papyrus.data.Prefs
import dev.reisarthur.papyrus.dict.DictFile
import dev.reisarthur.papyrus.dict.DictionaryImporter
import dev.reisarthur.papyrus.epub.EpubImporter
import dev.reisarthur.papyrus.pdf.PdfImporter
import dev.reisarthur.papyrus.util.Bg
import dev.reisarthur.papyrus.util.TextUtil
import dev.reisarthur.papyrus.reader.EpubContent
import dev.reisarthur.papyrus.reader.readerSettings
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Varre a pasta escolhida (via SAF), sincroniza com o banco e importa o que for novo.
 * Também importa dicionários colocados na subpasta "Dicionário".
 */
object LibraryManager {

    /** [listChanged] = false: só o texto de progresso mudou (a lista não precisa ser relida). */
    fun interface Listener { fun onLibraryChanged(status: String?, listChanged: Boolean) }

    private val listeners = LinkedHashSet<Listener>()
    private val running = AtomicBoolean(false)
    @Volatile var status: String? = null
        private set

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    private fun notify(newStatus: String?, listChanged: Boolean = true) {
        status = newStatus
        Bg.main { listeners.toList().forEach { it.onLibraryChanged(newStatus, listChanged) } }
    }

    private val PLACEHOLDER_TITLE = Regex("import_\\d+")

    private data class FoundFile(val uri: Uri, val name: String, val size: Long, val parentName: String)

    fun scan(context: Context) {
        val tree = Prefs.libraryTreeUri?.let(Uri::parse) ?: return
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        Bg.io {
            try {
                notify("Procurando livros…")
                val files = listTree(app, tree)
                syncBooks(app, files)
                importDictionaries(app, files)
            } catch (t: Throwable) {
                notify("Não foi possível ler a pasta. Escolha-a novamente.")
                running.set(false)
                return@io
            }
            running.set(false)
            notify(null)
        }
    }

    private fun syncBooks(context: Context, files: List<FoundFile>) {
        val db = Database.instance
        val books = files.filter { it.name.endsWith(".pdf", true) || it.name.endsWith(".epub", true) }
        val byKey = db.allBooksIncludingMissing().associateBy { it.key }
        // conserta livros importados antes da correção, que ficaram com o nome da cópia temporária
        byKey.values.filter { PLACEHOLDER_TITLE.matches(it.title) }
            .forEach { db.fixTitle(it.id, it.fileName.substringBeforeLast('.')) }
        val seen = HashSet<String>()
        val toImport = ArrayList<Pair<Long, FoundFile>>()

        for (f in books) {
            val key = "${f.name}|${f.size}"
            seen += key
            val existing = byKey[key]
            when {
                existing == null -> {
                    val format = if (f.name.endsWith(".pdf", true)) BookFormat.PDF else BookFormat.EPUB
                    val id = db.insertBook(key, f.uri.toString(), f.name, format)
                    if (id > 0) toImport += id to f
                }
                existing.status == BookStatus.MISSING || existing.uri != f.uri.toString() -> {
                    db.updateBookUri(existing.id, f.uri.toString(), if (existing.sections > 0) BookStatus.READY else BookStatus.IMPORTING)
                    if (existing.sections == 0) toImport += existing.id to f
                }
                existing.status == BookStatus.IMPORTING -> toImport += existing.id to f
                // PDFs de uma versão antiga do formato: reimporta UMA vez (a marca é gravada sempre)
                existing.format == BookFormat.PDF && existing.status == BookStatus.READY &&
                    !File(BookFiles.dir(context.filesDir, existing.id), PdfImporter.FORMAT_MARKER).exists() ->
                    toImport += existing.id to f
            }
        }
        // Livros que sumiram da pasta ficam ocultos (as notas são preservadas caso o arquivo volte).
        byKey.values.filter { it.key !in seen && it.status != BookStatus.MISSING }
            .forEach { db.setStatus(it.id, BookStatus.MISSING) }
        notify(status)

        importAll(context, toImport)
    }

    /**
     * Importa um livro por vez, e cada PDF divide as páginas entre [importThreads] threads.
     * Dá a mesma velocidade total de vários livros em paralelo, mas cada livro fica pronto antes,
     * usa metade da memória e nunca passa de 2 threads (mais que isso travava a interface).
     */
    private fun importAll(context: Context, toImport: List<Pair<Long, FoundFile>>) {
        if (toImport.isEmpty()) return
        val progress = ImportProgress(toImport.size, toImport.singleOrNull()?.second?.name)
        notify(progress.label())
        val threads = importThreads(context)
        // a varredura já roda em segundo plano: a fila é processada aqui mesmo, em ordem
        val previous = android.os.Process.getThreadPriority(android.os.Process.myTid())
        // um pouco abaixo do normal: a interface tem prioridade, mas a importação não vai para os
        // núcleos lentos (prioridade "background"), o que a deixava muito mais lenta
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT + 4)
        try {
            toImport.forEachIndexed { i, (id, file) ->
                importBook(context, id, file.uri, file.name, threads) { done, total ->
                    // progresso só troca o texto da barra: a lista não é relida a cada 1%
                    if (progress.update(i, done, total)) notify(progress.label(), listChanged = false)
                }
                progress.finish(i)
                notify(progress.label()) // a lista atualiza e o livro pronto já aparece com a capa
                prepaginate(context, id)
            }
        } finally {
            android.os.Process.setThreadPriority(previous)
        }
    }

    /**
     * Deixa o livro recém-importado paginado para a tela e as preferências atuais (o resultado fica
     * no cache em disco do próprio livro). Assim, ao abrir, ele aparece na hora.
     * Só roda se algum livro já foi aberto antes (é quando se conhece o tamanho da tela).
     */
    private fun prepaginate(context: Context, id: Long) {
        val g = Prefs.readerGeometry ?: return
        val book = Database.instance.book(id)?.takeIf { it.status == BookStatus.READY } ?: return
        if (book.format == BookFormat.PDF) return // PDF mostra a página inteira: nada a paginar
        notify("Preparando ${book.title} para leitura…", listChanged = false)
        runCatching {
            val settings = readerSettings(context, book.format, g[0], g[1], g[2], g[3])
            val dir = BookFiles.dir(context.filesDir, id)
            val content = EpubContent(dir)
            try { content.layout(settings) } finally { content.close() }
        }.onFailure { android.util.Log.w("papyrus", "Pré-paginação falhou", it) }
    }

    /** Progresso somado de vários livros importando ao mesmo tempo (cada livro pesa igual). */
    private class ImportProgress(private val count: Int, private val singleName: String?) {
        private val fractions = FloatArray(count)
        private var finished = 0
        private var lastPercent = -1

        /** Devolve true quando a porcentagem inteira mudou (evita atualizar a tela à toa). */
        @Synchronized
        fun update(book: Int, done: Int, total: Int): Boolean {
            if (total <= 0) return false
            fractions[book] = (done.toFloat() / total).coerceIn(0f, 1f)
            val p = percent()
            if (p == lastPercent) return false
            lastPercent = p
            return true
        }

        @Synchronized
        fun finish(book: Int) {
            fractions[book] = 1f
            finished++
            lastPercent = percent()
        }

        private fun percent() = (fractions.sum() * 100 / count).toInt().coerceIn(0, 100)

        @Synchronized
        fun label(): String = if (singleName != null) "Importando $singleName · ${percent()}%"
        else "Importando livros · $finished de $count · ${percent()}%"
    }

    /** Threads por importação: 2 (1 em aparelho com pouca memória, onde o pdfbox pesa demais). */
    private fun importThreads(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return if (am.isLowRamDevice || Runtime.getRuntime().availableProcessors() < 4) 1 else 2
    }

    /** Importa (ou reimporta) um livro. Roda na thread atual. */
    fun importBook(
        context: Context, id: Long, uri: Uri, name: String, parallelism: Int = 1,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ) {
        val db = Database.instance
        db.setStatus(id, BookStatus.IMPORTING) // ninguém abre o livro enquanto os arquivos são refeitos
        val outDir = BookFiles.dir(context.filesDir, id)
        // a capa antiga fica até a nova ser gerada: a lista nunca mostra a capa provisória num livro já conhecido
        outDir.listFiles()?.filter { it.name != "cover.jpg" }?.forEach { it.deleteRecursively() }
        val tmp = File(context.cacheDir, "import_${id}." + name.substringAfterLast('.', "bin"))
        try {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                tmp.outputStream().use { input.copyTo(it, 1 shl 16) }
            }
            val result = if (name.endsWith(".pdf", true)) {
                PdfImporter(tmp, outDir, name).import(onProgress, parallelism)
            } else {
                EpubImporter(tmp, outDir, name).import(onProgress)
            }
            db.finishImport(id, result.title, result.author, result.sections)
        } catch (t: Throwable) {
            android.util.Log.e("papyrus", "Falha ao importar $name", t)
            db.setStatus(id, BookStatus.FAILED)
        } finally {
            tmp.delete()
        }
    }

    fun reimport(context: Context, id: Long) {
        val book = Database.instance.book(id) ?: return
        Database.instance.setStatus(id, BookStatus.IMPORTING)
        notify("Reimportando ${book.fileName} · 0%")
        Bg.io {
            var last = -1
            val app = context.applicationContext
            importBook(app, id, Uri.parse(book.uri), book.fileName, importThreads(app)) { done, total ->
                val p = if (total > 0) done * 100 / total else 0
                if (p != last) { last = p; notify("Reimportando ${book.fileName} · $p%", listChanged = false) }
            }
            prepaginate(app, id)
            notify(null)
        }
    }

    private fun importDictionaries(context: Context, files: List<FoundFile>) {
        val dictFiles = files.filter { f ->
            val folder = TextUtil.foldKey(f.parentName)
            (folder.startsWith("dicionario") || f.name.endsWith(".ifo", true)) && DictionaryImporter.isDictionaryFile(f.name)
        }
        if (dictFiles.isEmpty()) return
        DictionaryImporter(context).import(dictFiles.map { DictFile(it.uri, it.name, it.size) }, force = false) { notify(it) }
    }

    /** Percorre a árvore do SAF recursivamente (ignora pastas ocultas). */
    private fun listTree(context: Context, tree: Uri): List<FoundFile> {
        val out = ArrayList<FoundFile>()
        val resolver = context.contentResolver
        val stack = ArrayDeque<Pair<String, String>>()
        stack.add(DocumentsContract.getTreeDocumentId(tree) to "")
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        while (stack.isNotEmpty()) {
            val (docId, dirName) = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            resolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2).orEmpty()
                    if (name.startsWith(".")) continue
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        stack.add(id to name)
                    } else {
                        out += FoundFile(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, c.getLong(3), dirName)
                    }
                }
            }
        }
        return out
    }
}
